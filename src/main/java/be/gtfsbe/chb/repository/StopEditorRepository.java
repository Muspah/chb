package be.gtfsbe.chb.repository;

import be.gtfsbe.chb.dto.CreateStopRequest;
import be.gtfsbe.chb.dto.GroupStopsRequest;
import be.gtfsbe.chb.dto.UpdateStopRequest;
import be.gtfsbe.chb.service.EditorTransactionRunner;
import be.gtfsbe.chb.web.CuratorActionException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Alle schrijfoperaties, enkel aangeroepen vanuit beveiligde endpoints, via de chb_editor-pool en
 * altijd binnen een transactie die eerst {@code app.current_user} zet
 * ({@link EditorTransactionRunner}, build-instruction.md §2, §5, §6).
 *
 * <p>Elke actie is exact één aanroep van een curator-functie in de database (ndov-repo, V110,
 * {@code chb.create_stop_place} e.a.): validatie, dubbele-naam-guard, zwaartepunt, soft-delete-
 * voorwaarden en het {@code reviewed_at}-tijdstip (database-tijd, issue #8) zitten dáár, atomair
 * met de schrijfactie. Geweigerde acties komen als {@code CuratorActionException} uit
 * {@link EditorTransactionRunner#runAs}. De {@code ?::type}-casts zijn nodig omdat de JDBC-driver
 * een {@code Double}/{@code null} anders niet aan de functiesignatuur (numeric/bigint) kan koppelen.
 */
@Repository
public class StopEditorRepository {

    /** Eentalige gebieden: daar is stop.name van een general de naam in de gebiedstaal. */
    private static final Set<String> REGION_LANGUAGES = Set.of("nl", "fr", "de");

    /** Zoals StopNames.UNUSABLE_REASONS in ndov: redenen waarop de exports hard falen. */
    private static final Set<String> UNUSABLE_REASONS = Set.of("bare_code", "general_region_unknown",
        "general_no_language", "general_no_region_language", "bru_disagree");

    /** Zelfde sleutelpatroon als de curated-CTE's in chb.quay_names / chb.stop_names. */
    private static final Pattern NAME_KEY = Pattern.compile("^name_([a-z]{2,3})$");

    private final EditorTransactionRunner runner;
    private final StopNameCache names;

    public StopEditorRepository(EditorTransactionRunner runner, StopNameCache names) {
        this.runner = runner;
        this.names = names;
    }

    public long createStop(String currentUser, CreateStopRequest r) {
        return runner.runAs(currentUser, jdbc -> jdbc.queryForObject(
            "SELECT chb.create_stop_place(?, ?, ?::bigint, ?::numeric, ?::numeric, ?)", Long.class,
            r.name(), r.stopType(), r.parentId(), r.lat(), r.lon(), r.allowDuplicateName()));
    }

    public void updateStop(String currentUser, long id, UpdateStopRequest r) {
        runner.runAs(currentUser, jdbc -> {
            String oldName = jdbc.query("SELECT name FROM chb.stop WHERE id = ? AND kind = 'general'",
                rs -> rs.next() ? rs.getString(1) : null, id);
            jdbc.query("SELECT chb.update_stop(?, ?, ?, ?, ?::bigint)", rs -> { },
                id, r.name(), r.stopType(), r.publicCode(), r.parentId());
            if (oldName != null && r.name() != null && !oldName.equals(r.name().strip())) {
                ensureGeneralName(jdbc, id, r.name().strip());
            }
            return null;
        });
    }

    public void setLocation(String currentUser, long id, double lat, double lon) {
        call(currentUser, "SELECT chb.set_location(?, ?::numeric, ?::numeric)", id, lat, lon);
    }

    public void resetLocation(String currentUser, long id) {
        call(currentUser, "SELECT chb.reset_location(?)", id);
    }

    public void upsertAttribute(String currentUser, long stopId, String key, String value) {
        call(currentUser, "SELECT chb.set_attribute(?, ?, ?)", stopId, key, value);
        Matcher m = NAME_KEY.matcher(key);
        if (m.matches() && value != null && !value.isEmpty()) {
            names.putCuratedName(stopId, m.group(1), value);
        }
    }

    public void deleteAttribute(String currentUser, long stopId, String key) {
        call(currentUser, "SELECT chb.delete_attribute(?, ?)", stopId, key);
    }

    /** "Nagekeken": de database zet zelf het tijdstip, de waarde die hier meegaat wordt genegeerd. */
    public void markReviewed(String currentUser, long id) {
        call(currentUser, "SELECT chb.set_attribute(?, 'reviewed_at', NULL)", id);
    }

    public void unmarkReviewed(String currentUser, long id) {
        call(currentUser, "SELECT chb.delete_attribute(?, 'reviewed_at')", id);
    }

    /** Quay → quay (het bronnen-duplicaat-geval); voor "dezelfde plek, aparte perrons" is er {@link #groupStops}. */
    public void mergeQuays(String currentUser, long sourceId, long targetId) {
        call(currentUser, "SELECT chb.merge_quays(?, ?)", sourceId, targetId);
    }

    public long groupStops(String currentUser, GroupStopsRequest r) {
        // Array-literal als tekst i.p.v. java.sql.Array: geen connectiegebonden object nodig; een null-element
        // ("null" in het literal) wordt door de database als UC008 geweigerd.
        String ids = r.stopIds() == null ? null
            : r.stopIds().stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
        return runner.runAs(currentUser, jdbc -> {
            long id = jdbc.queryForObject(
                "SELECT chb.group_stops(?, ?, ?::numeric, ?::numeric, ?::bigint[], ?)", Long.class,
                r.name(), r.stopType(), r.lat(), r.lon(), ids, r.allowDuplicateName());
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT kind = 'general' FROM chb.stop WHERE id = ?", Boolean.class, id))) {
                ensureGeneralName(jdbc, id, r.name().strip());
            }
            return id;
        });
    }

    /**
     * Issue #28: een general krijgt zijn naam per taal niet uit stop.name; zonder name_<taal> geeft
     * chb.stop_names general_no_language en weigert de volgende export (StopNames, ndov). Na aanmaken of
     * hernoemen, in dezelfde transactie: in een eentalig gebied (nl/fr/de) wordt name_<gebiedstaal> de nieuwe
     * naam. Elders (bru: fr én nl, dg, gemengd) kan de viewer die niet raden; geeft stop_names dan een reden
     * waarop de export faalt, dan wordt de actie geweigerd (rollback) met dezelfde boodschap.
     */
    private static void ensureGeneralName(JdbcTemplate jdbc, long id, String name) {
        // ponytail: chb.stop_names is een view over alle haltes (~6 s ook voor één id); goed genoeg voor groeperen/hernoemen.
        Map<String, Object> n = jdbc.queryForMap(
            "SELECT region, name_reason, default_name FROM chb.stop_names WHERE stop_id = ?", id);
        String region = (String) n.get("region");
        if (REGION_LANGUAGES.contains(region)) {
            jdbc.query("SELECT chb.set_attribute(?, ?, ?)", rs -> { }, id, "name_" + region, name);
            return;
        }
        String reason = (String) n.get("name_reason");
        String defaultName = (String) n.get("default_name");
        boolean unusable = defaultName == null || defaultName.isBlank() || (reason != null && UNUSABLE_REASONS.contains(reason));
        boolean hasLiveChildren = Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM chb.stop WHERE parent_id = ? AND merged_into_id IS NULL AND disabled_at IS NULL)",
            Boolean.class, id));
        if (unusable && hasLiveChildren) {
            throw new CuratorActionException(HttpStatus.BAD_REQUEST, "UC008",
                "Geen bruikbare standaardnaam voor deze general (" + reason + "): de export zou weigeren.",
                "Taalgebied " + (region == null ? "onbekend" : region) + ". Geef de kinderen eerst dezelfde name_<taal>"
                    + " (Brussel: name_fr én name_nl) en probeer dan opnieuw.", null, null);
        }
    }

    public void recomputeLocation(String currentUser, long id) {
        call(currentUser, "SELECT chb.recompute_location(?)", id);
    }

    public void softDeleteStop(String currentUser, long id) {
        call(currentUser, "SELECT chb.soft_delete_stop(?)", id);
    }

    public void restoreStop(String currentUser, long id) {
        call(currentUser, "SELECT chb.restore_stop(?)", id);
    }

    /** Wrapper → curated StopPlace met een eigen (verplichte) naam. */
    public void promoteWrapper(String currentUser, long id, String name) {
        call(currentUser, "SELECT chb.promote_wrapper(?, ?)", id, name);
    }

    /** Externe id (ndov V193, chb#31) aan een StopPlace of Quay; regex, niveau en uniciteit bewaakt de database. */
    public long addIdentifier(String currentUser, long objectId, String scheme, String value, String note) {
        return runner.runAs(currentUser, jdbc -> jdbc.queryForObject(
            "SELECT chb.add_identifier(?, ?, ?, ?)", Long.class, scheme, value, objectId, note));
    }

    public void removeIdentifier(String currentUser, long identifierId) {
        call(currentUser, "SELECT chb.remove_identifier(?)", identifierId);
    }

    private void call(String currentUser, String sql, Object... args) {
        runner.runAs(currentUser, jdbc -> {
            jdbc.query(sql, rs -> { }, args);
            return null;
        });
    }
}
