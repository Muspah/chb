package be.gtfsbe.chb.service;

import be.gtfsbe.chb.repository.StopMapCache;
import be.gtfsbe.chb.repository.StopNameCache;
import be.gtfsbe.chb.web.CuratorActionException;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Herbruikbare wikkel rond de chb_editor-pool (build-instruction.md §6): opent een transactie,
 * zet er als eerste actie {@code app.current_user} in (zodat de audit-triggers en de
 * curator-functies de juiste gebruiker loggen, zie §2), voert dan de eigenlijke schrijfactie uit
 * en commit.
 *
 * <p>{@code SET LOCAL app.current_user = ?} zelf ondersteunt geen bind-parameter in PostgreSQL
 * (SET is een utility-statement, geen gewone expressie) — {@code set_config(name, value, true)}
 * is de SQL-functie die hetzelfde doet (transactie-lokaal dankzij {@code is_local = true}) en wel
 * een echte, parametrized PreparedStatement toelaat.
 *
 * <p>Een door de database geweigerde actie (eigen SQLSTATE-klasse UC, zie
 * contrib/stap4b-ontwerp-write-api.md §6 in de ndov-repo, plus 23514/55P03/40P01) wordt hier
 * centraal vertaald naar een {@link CuratorActionException} en geteld ({@link RejectionCounter}).
 */
@Component
public class EditorTransactionRunner {

    /** HINT "Wijzig quay 123" / "Wijzig het type van StopPlace 45": het id waar de curator wel moet zijn. */
    private static final Pattern HINT_TARGET = Pattern.compile("^Wijzig\\b.*?(\\d+)\\s*$");

    private final JdbcTemplate editorJdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final RejectionCounter rejections;
    private final StopNameCache names;
    private final StopMapCache mapCache;

    public EditorTransactionRunner(JdbcTemplate editorJdbcTemplate, PlatformTransactionManager editorTransactionManager,
                                   RejectionCounter rejections, StopNameCache names, StopMapCache mapCache) {
        this.editorJdbcTemplate = editorJdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(editorTransactionManager);
        this.rejections = rejections;
        this.names = names;
        this.mapCache = mapCache;
    }

    public <T> T runAs(String currentUser, Function<JdbcTemplate, T> action) {
        try {
            T result = transactionTemplate.execute(status -> {
                editorJdbcTemplate.query("SELECT set_config('app.current_user', ?, true)", rs -> { }, currentUser);
                return action.apply(editorJdbcTemplate);
            });
            // Elke schrijfactie kan een naam raken (name_<taal>, naam, groeperen, samenvoegen).
            names.refreshSoon();
            mapCache.invalidate();
            return result;
        } catch (DataAccessException e) {
            CuratorActionException translated = translate(e);
            if (translated == null) {
                throw e;
            }
            rejections.record(translated.sqlState(), currentUser, translated.getMessage());
            throw translated;
        }
    }

    /** {@code null} = geen weigering die we kennen (bv. verbindingsfout): laat de 500 maar staan. */
    static CuratorActionException translate(DataAccessException e) {
        PSQLException pg = null;
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof PSQLException p) {
                pg = p;
                break;
            }
        }
        if (pg == null || pg.getSQLState() == null) {
            return null;
        }
        String state = pg.getSQLState();
        HttpStatus status = switch (state) {
            case "UC001", "55P03", "40P01" -> HttpStatus.CONFLICT;
            case "UC002" -> HttpStatus.NOT_FOUND;
            case "UC003", "UC004", "UC005", "UC006", "UC007", "UC008", "23514", "23503", "23505" -> HttpStatus.BAD_REQUEST;
            default -> null;
        };
        if (status == null) {
            return null;
        }
        ServerErrorMessage server = pg.getServerErrorMessage();
        String message = server != null && server.getMessage() != null ? server.getMessage() : pg.getMessage();
        String detail = server != null ? server.getDetail() : null;
        String hint = server != null ? server.getHint() : null;
        if (state.equals("55P03") || state.equals("40P01")) {
            message = "Een andere wijziging aan dezelfde halte loopt nog; probeer het over enkele seconden opnieuw.";
        }
        Long targetId = null;
        if (hint != null) {
            Matcher m = HINT_TARGET.matcher(hint);
            if (m.matches()) {
                targetId = Long.valueOf(m.group(1));
            }
        }
        return new CuratorActionException(status, state, message, detail, targetId, e);
    }
}
