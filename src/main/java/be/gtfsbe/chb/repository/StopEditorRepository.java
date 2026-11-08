package be.gtfsbe.chb.repository;

import be.gtfsbe.chb.dto.CreateStopRequest;
import be.gtfsbe.chb.dto.GroupStopsRequest;
import be.gtfsbe.chb.dto.UpdateStopRequest;
import be.gtfsbe.chb.service.EditorTransactionRunner;
import org.springframework.stereotype.Repository;

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
        call(currentUser, "SELECT chb.update_stop(?, ?, ?, ?, ?::bigint)",
            id, r.name(), r.stopType(), r.publicCode(), r.parentId());
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
        return runner.runAs(currentUser, jdbc -> jdbc.queryForObject(
            "SELECT chb.group_stops(?, ?, ?::numeric, ?::numeric, ?::bigint[], ?)", Long.class,
            r.name(), r.stopType(), r.lat(), r.lon(), ids, r.allowDuplicateName()));
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

    private void call(String currentUser, String sql, Object... args) {
        runner.runAs(currentUser, jdbc -> {
            jdbc.query(sql, rs -> { }, args);
            return null;
        });
    }
}
