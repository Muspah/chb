package be.gtfsbe.chb.repository;

import be.gtfsbe.chb.dto.DuplicateNameGroup;
import be.gtfsbe.chb.dto.GeoJsonFeature;
import be.gtfsbe.chb.dto.GeoJsonFeatureCollection;
import be.gtfsbe.chb.dto.GeoJsonGeometry;
import be.gtfsbe.chb.dto.MapBounds;
import be.gtfsbe.chb.dto.ReviewStats;
import be.gtfsbe.chb.dto.StopChildSummary;
import be.gtfsbe.chb.dto.StopDetail;
import be.gtfsbe.chb.dto.StopHistoryEntry;
import be.gtfsbe.chb.dto.StopPlaceTypeOption;
import be.gtfsbe.chb.dto.StopSearchResult;
import be.gtfsbe.chb.dto.StopSource;
import be.gtfsbe.chb.dto.UnreviewedStop;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Alle leesoperaties, publiek en beveiligd, gaan via de chb_viewer-pool (build-instruction.md §5)
 * — ook wanneer de aanroeper ingelogd is, want er is geen reden om lezers via de schrijf-pool te
 * sturen.
 *
 * <p>Kolomnamen van {@code chb.stop}, {@code chb.stop_attribute}, {@code chb.stop_change_log},
 * {@code chb.raw_stop}, {@code chb.stop_tree} (V7) en {@code chb.stop_sources} (V7) zijn
 * overgenomen uit de effectieve DDL uit de gtfs-import-repo.
 */
@Repository
public class StopViewerRepository {

    /**
     * Gereserveerde {@code chb.stop_attribute}-sleutel voor "nagekeken tijdens de haltebestand-
     * doorlichting" (geen aparte kolom/migratie nodig — zie het antwoord in de conversatie).
     * Waarde is een ISO-8601-tijdstip. Wordt uit de generieke attributenlijst gefilterd en apart
     * op {@link StopDetail#reviewedAt()} gezet, zodat curatoren hem niet als een gewoon
     * domein-attribuut (wheelchair_accessible e.d.) zien of per ongeluk bewerken.
     */
    public static final String REVIEWED_ATTRIBUTE_KEY = "reviewed_at";

    /**
     * Gereserveerde {@code chb.stop_attribute}-sleutel voor het soft-verwijderen van een lege
     * synthetische groeperingsknoop (build-instruction.md §3.3 verbiedt een echte hard delete van
     * een {@code chb.stop}-rij — dit is bewust een aparte, expliciete markering, geen hergebruik
     * van {@code merged_into_id}). Enkel toegelaten op knopen zonder kinderen en zonder brondata,
     * zie {@link be.gtfsbe.chb.repository.StopEditorRepository#softDeleteStop}.
     */
    public static final String DELETED_ATTRIBUTE_KEY = "deleted_at";

    /**
     * Gereserveerde {@code chb.stop_attribute}-sleutel voor de "hint" die {@code
     * chb.sync_from_staging()} (import-repo, V11) bijhoudt wanneer {@code
     * raw_stop.source_parent_station} wijzigt t.o.v. de vorige sync. Overschrijft bewust nóóit
     * {@code parent_id}/een curator-groepering (die blijft 100% curator-eigendom) — dit is puur
     * informatief. Wordt hier enkel uit de generieke attributenlijst gefilterd zodat hij niet als
     * een gewoon domein-attribuut tussen bv. wheelchair_accessible verschijnt; een eigen
     * {@link StopDetail}-veld/front-end-weergave is nog niet gebouwd.
     */
    public static final String SOURCE_PARENT_HINT_ATTRIBUTE_KEY = "_source_parent_station_hint";

    private final JdbcTemplate jdbcTemplate;

    public StopViewerRepository(JdbcTemplate viewerJdbcTemplate) {
        this.jdbcTemplate = viewerJdbcTemplate;
    }

    /**
     * Toegelaten waarden voor {@code chb.stop.stop_type}, uit de {@code chb.stop_place_type}-
     * lookup-tabel (import-repo, V12) — voedt de dropdown in de webinterface.
     */
    public List<StopPlaceTypeOption> findStopPlaceTypes() {
        return jdbcTemplate.query(
            "SELECT code, label_nl FROM chb.stop_place_type ORDER BY sort_order",
            (rs, rowNum) -> new StopPlaceTypeOption(rs.getString("code"), rs.getString("label_nl")));
    }

    // Bovengrens op het aantal features per kaartaanvraag: bij een ver uitgezoomd viewport (bv.
    // heel Vlaanderen) bevat de bbox alsnog een groot deel van de ~50K haltes. In plaats van die
    // allemaal te versturen (net het probleem dat dit vervangt) knippen we af en laat de frontend
    // een "zoom in voor meer detail"-melding zien (GeoJsonFeatureCollection.truncated()).
    private static final int MAP_FEATURES_LIMIT = 2000;

    // Een halte met coördinaten wordt van de kaart geweerd zodra haar eigen (directe) parent ook
    // coördinaten heeft — dat betekent dat die parent haar al visueel vertegenwoordigt als één
    // punt (bv. een "snel groeperen"-groeperingsknoop over twee haltes aan weerszijden van
    // dezelfde straat). Pas bij doorklikken op die parent worden de onderliggende haltes zichtbaar
    // (zie findChildren). Een parent zonder eigen coördinaten (bv. een groot stationscomplex)
    // verandert niets aan het bestaande gedrag: de kinderen blijven dan elk apart zichtbaar, want
    // er is geen enkel punt dat ze kan vertegenwoordigen. Gedeeld door findAllAsMapFeatures en
    // findUnreviewedAsMapFeatures, die enkel verschillen in een extra filter erbovenop.
    //
    // s.geom && ST_MakeEnvelope(...) filtert op het huidige kaartviewport via de GiST-index op
    // chb.stop.geom (db/add_stop_geom_postgis.sql) — zonder deze bbox-filter (en zonder cap
    // hieronder) laadt de kaart bij elke actie de volledige tabel, wat >1s duurde.
    private static final String MAP_FEATURES_BASE_SQL = """
        SELECT s.id, s.name, s.stop_type, s.public_code, s.lat, s.lon, s.parent_id, s.is_synthetic, s.is_wrapper, s.disabled_at
        FROM chb.stop s
        WHERE s.lat IS NOT NULL AND s.lon IS NOT NULL AND s.merged_into_id IS NULL
        AND s.geom && ST_MakeEnvelope(?, ?, ?, ?, 4326)
        AND NOT EXISTS (
            SELECT 1 FROM chb.stop p
            WHERE p.id = s.parent_id AND p.lat IS NOT NULL AND p.lon IS NOT NULL
        )
        AND NOT EXISTS (
            SELECT 1 FROM chb.stop_attribute a WHERE a.stop_id = s.id AND a.key = ?
        )
        """;

    // disabled_at wordt server-side (import-repo) automatisch gezet/gecascadeerd op basis van
    // bronfeed-aanwezigheid — deze app leest het enkel. Standaard uitgefilterd (net als
    // deleted_at hierboven), tenzij de "toon uitgeschakelde haltes"-toggle in de frontend aanstaat.
    private static String disabledFilterSql(boolean showDisabled) {
        return showDisabled ? "" : "AND s.disabled_at IS NULL\n";
    }

    public GeoJsonFeatureCollection findAllAsMapFeatures(MapBounds bounds, boolean showDisabled) {
        String sql = MAP_FEATURES_BASE_SQL + disabledFilterSql(showDisabled);
        return queryMapFeatures(sql, bounds, DELETED_ATTRIBUTE_KEY);
    }

    /** Voor de "doorlichtingskaart" (build-instruction.md-uitbreiding): enkel nog niet nagekeken haltes. */
    public GeoJsonFeatureCollection findUnreviewedAsMapFeatures(MapBounds bounds, boolean showDisabled) {
        String sql = MAP_FEATURES_BASE_SQL + disabledFilterSql(showDisabled) + """
            AND NOT EXISTS (
                SELECT 1 FROM chb.stop_attribute a WHERE a.stop_id = s.id AND a.key = ?
            )
            """;
        return queryMapFeatures(sql, bounds, DELETED_ATTRIBUTE_KEY, REVIEWED_ATTRIBUTE_KEY);
    }

    // Vraagt telkens één rij méér op dan de cap: als die erbij zit, weten we dat afgekapt is,
    // zonder een aparte COUNT(*)-query (die op een groot uitgezoomd viewport zelf ook traag zou
    // zijn — precies wat we vermijden).
    private GeoJsonFeatureCollection queryMapFeatures(String baseSql, MapBounds bounds, Object... extraParams) {
        String sql = baseSql + "ORDER BY s.id LIMIT ?";
        Object[] params = new Object[4 + extraParams.length + 1];
        params[0] = bounds.minLon();
        params[1] = bounds.minLat();
        params[2] = bounds.maxLon();
        params[3] = bounds.maxLat();
        System.arraycopy(extraParams, 0, params, 4, extraParams.length);
        params[params.length - 1] = MAP_FEATURES_LIMIT + 1;
        List<GeoJsonFeature> rows = jdbcTemplate.query(sql, (rs, rowNum) -> mapFeatureRow(rs), params);
        boolean truncated = rows.size() > MAP_FEATURES_LIMIT;
        List<GeoJsonFeature> features = truncated ? rows.subList(0, MAP_FEATURES_LIMIT) : rows;
        return new GeoJsonFeatureCollection(features, truncated);
    }

    private static GeoJsonFeature mapFeatureRow(ResultSet rs) throws SQLException {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("id", rs.getLong("id"));
        properties.put("name", rs.getString("name"));
        properties.put("stopType", rs.getString("stop_type"));
        properties.put("publicCode", rs.getString("public_code"));
        long parentId = rs.getLong("parent_id");
        properties.put("parentId", rs.wasNull() ? null : parentId);
        properties.put("isSynthetic", rs.getBoolean("is_synthetic"));
        properties.put("isWrapper", rs.getBoolean("is_wrapper"));
        properties.put("disabledAt", rs.getObject("disabled_at", OffsetDateTime.class));
        return new GeoJsonFeature(GeoJsonGeometry.point(rs.getDouble("lon"), rs.getDouble("lat")), properties);
    }

    /**
     * Detail van een StopPlace ({@code chb.stop}: general/monomodal/wrapper) of van een quay
     * ({@code chb.quay}); de id-ruimtes zijn disjunct (gedeelde reeks, D6). De kaart toont
     * StopPlaces; quays bereik je via de kinderenlijst van hun StopPlace. Bronnen en
     * first/last-seen van een StopPlace zijn de samenvatting over al zijn quays.
     */
    public Optional<StopDetail> findDetail(long id) {
        Optional<StopDetail> stopPlace = findStopPlaceDetail(id);
        return stopPlace.isPresent() ? stopPlace : findQuayDetail(id);
    }

    private Optional<StopDetail> findStopPlaceDetail(long id) {
        String sql = """
            SELECT s.id, s.name, s.stop_type, s.public_code, s.lat, s.lon, s.parent_id, s.is_synthetic, s.is_wrapper, s.disabled_at,
                   concat_ws(' > ', g.name, s.name) AS path
            FROM chb.stop s LEFT JOIN chb.stop g ON g.id = s.parent_id
            WHERE s.id = ?
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> buildDetail(rs, id, false), id).stream().findFirst();
    }

    private Optional<StopDetail> findQuayDetail(long id) {
        // Het type is dat van de StopPlace (een quay heeft zelf geen stop_type); parentId = die StopPlace,
        // ook bij een wrapper (chb.update_stop leest "zelfde StopPlace" als geen wijziging).
        String sql = """
            SELECT q.id, q.name, s.stop_type, q.public_code, q.lat, q.lon, q.stop_id AS parent_id, q.is_synthetic, false AS is_wrapper,
                   q.disabled_at,
                   CASE WHEN s.is_wrapper THEN concat_ws(' > ', g.name, q.name) ELSE concat_ws(' > ', g.name, s.name, q.name) END AS path
            FROM chb.quay q JOIN chb.stop s ON s.id = q.stop_id LEFT JOIN chb.stop g ON g.id = s.parent_id
            WHERE q.id = ?
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> buildDetail(rs, id, true), id).stream().findFirst();
    }

    private StopDetail buildDetail(ResultSet rs, long id, boolean quay) throws SQLException {
        // rs.wasNull() reflecteert enkel de laatst-gelezen kolom, dus elke nullable kolom
        // moet meteen na het lezen omgezet worden — niet pas verderop in de constructor-call.
        long parentIdRaw = rs.getLong("parent_id");
        Long parentId = rs.wasNull() ? null : parentIdRaw;
        double latRaw = rs.getDouble("lat");
        Double latValue = rs.wasNull() ? null : latRaw;
        double lonRaw = rs.getDouble("lon");
        Double lonValue = rs.wasNull() ? null : lonRaw;
        OffsetDateTime disabledAt = rs.getObject("disabled_at", OffsetDateTime.class);
        SeenRange seen = findSeenRange(id, quay);
        Map<String, String> attributes = findAttributes(id, quay);
        String reviewedAt = attributes.remove(REVIEWED_ATTRIBUTE_KEY);
        String deletedAt = attributes.remove(DELETED_ATTRIBUTE_KEY);
        attributes.remove(SOURCE_PARENT_HINT_ATTRIBUTE_KEY);
        return new StopDetail(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("stop_type"),
            rs.getString("public_code"),
            latValue,
            lonValue,
            rs.getBoolean("is_synthetic"),
            rs.getBoolean("is_wrapper"),
            quay,
            parentId,
            rs.getString("path"),
            attributes,
            findSources(id, quay),
            findChildren(id, quay),
            reviewedAt,
            deletedAt,
            disabledAt,
            seen.firstSeen(),
            seen.lastSeen()
        );
    }

    private record SeenRange(OffsetDateTime firstSeen, OffsetDateTime lastSeen) {
    }

    // raw_stop.stop_id wijst naar een QUAY; een StopPlace vat zijn quays samen.
    private static final String QUAYS_OF_STOP_PLACE = "SELECT id FROM chb.quay WHERE stop_id = ?";

    /**
     * Vroegste {@code first_seen_at} en laatste {@code last_seen_at} over alle aan deze halte
     * (of, voor een StopPlace, aan zijn quays) gekoppelde {@code chb.raw_stop}-rijen. Voor een
     * groeperingsknoop zonder quays zijn beide {@code null}.
     */
    private SeenRange findSeenRange(long id, boolean quay) {
        String sql = "SELECT min(first_seen_at) AS first_seen, max(last_seen_at) AS last_seen FROM chb.raw_stop WHERE "
            + (quay ? "stop_id = ?" : "stop_id IN (" + QUAYS_OF_STOP_PLACE + ")");
        return jdbcTemplate.query(sql,
            rs -> rs.next()
                ? new SeenRange(rs.getObject("first_seen", OffsetDateTime.class),
                    rs.getObject("last_seen", OffsetDateTime.class))
                : new SeenRange(null, null),
            id);
    }

    private Map<String, String> findAttributes(long id, boolean quay) {
        String sql = quay
            ? "SELECT key, value FROM chb.quay_attribute WHERE quay_id = ? ORDER BY key"
            : "SELECT key, value FROM chb.stop_attribute WHERE stop_id = ? ORDER BY key";
        Map<String, String> attributes = new LinkedHashMap<>();
        jdbcTemplate.query(sql, rs -> {
            attributes.put(rs.getString("key"), rs.getString("value"));
        }, id);
        return attributes;
    }

    private List<StopSource> findSources(long id, boolean quay) {
        String sql = "SELECT source_schema, source_id, source_name FROM chb.stop_sources WHERE "
            + (quay ? "stop_id = ?" : "stop_id IN (" + QUAYS_OF_STOP_PLACE + ")");
        return jdbcTemplate.query(sql,
            (rs, rowNum) -> new StopSource(rs.getString("source_schema"), rs.getString("source_id"), rs.getString("source_name")),
            id);
    }

    private List<StopChildSummary> findChildren(long id, boolean quay) {
        if (quay) {
            return List.of();
        }
        // Kinderen van een StopPlace: StopPlaces eronder (general) of zijn quays (monomodal/wrapper).
        // Uitgeschakelde kinderen worden hier bewust NIET uitgefilterd (anders dan verwijderde) —
        // dit lijstje in het detailpaneel heeft geen eigen toon-toggle, dus in plaats van ze
        // stilzwijgend te verbergen krijgt de frontend disabledAt mee om ze zichtbaar te markeren.
        String sql = """
            SELECT s.id, s.name, s.public_code, s.disabled_at, false AS is_quay FROM chb.stop s
            WHERE s.parent_id = ? AND s.merged_into_id IS NULL
            AND NOT EXISTS (SELECT 1 FROM chb.stop_attribute a WHERE a.stop_id = s.id AND a.key = ?)
            UNION ALL
            SELECT q.id, q.name, q.public_code, q.disabled_at, true FROM chb.quay q
            WHERE q.stop_id = ? AND q.merged_into_id IS NULL
            ORDER BY 2
            """;
        return jdbcTemplate.query(sql,
            (rs, rowNum) -> new StopChildSummary(rs.getLong("id"), rs.getString("name"), rs.getString("public_code"),
                rs.getObject("disabled_at", OffsetDateTime.class), rs.getBoolean("is_quay")),
            id, DELETED_ATTRIBUTE_KEY, id);
    }

    public List<StopSearchResult> search(String query) {
        // StopPlaces (incl. wrappers, die de naam van hun quay dragen); het pad is "general > StopPlace". Een
        // StopPlace kan meerdere bronnen hebben (meerdere quays/feeds), vandaar de string_agg i.p.v. een join.
        String sql = """
            SELECT s.id, concat_ws(' > ', g.name, s.name) AS path,
                (SELECT string_agg(ss.source_schema || ':' || ss.source_id, ', ' ORDER BY ss.source_schema, ss.source_id)
                 FROM chb.stop_sources ss JOIN chb.quay q ON q.id = ss.stop_id WHERE q.stop_id = s.id) AS source_codes
            FROM chb.stop s LEFT JOIN chb.stop g ON g.id = s.parent_id
            WHERE s.merged_into_id IS NULL AND concat_ws(' > ', g.name, s.name) ILIKE ?
            AND NOT EXISTS (SELECT 1 FROM chb.stop_attribute a WHERE a.stop_id = s.id AND a.key = ?)
            ORDER BY path
            LIMIT 50
            """;
        return jdbcTemplate.query(sql,
            (rs, rowNum) -> new StopSearchResult(rs.getLong("id"), rs.getString("path"), rs.getString("source_codes")),
            "%" + query + "%", DELETED_ATTRIBUTE_KEY);
    }

    public List<StopHistoryEntry> findHistory(long stopId) {
        String sql = """
            SELECT id, raw_stop_id, changed_by, changed_at, change_type, old_value, new_value, note
            FROM chb.stop_change_log
            WHERE stop_id = ?
            ORDER BY changed_at DESC
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            long rawStopId = rs.getLong("raw_stop_id");
            Long rawStopIdValue = rs.wasNull() ? null : rawStopId;
            return new StopHistoryEntry(
                rs.getLong("id"),
                rawStopIdValue,
                rs.getString("changed_by"),
                rs.getObject("changed_at", OffsetDateTime.class),
                rs.getString("change_type"),
                rs.getString("old_value"),
                rs.getString("new_value"),
                rs.getString("note")
            );
        }, stopId);
    }

    /**
     * Voortgang op quay-niveau (de eenheid waarin het werk binnenkomt; groeperen verandert de noemer niet).
     * Afgehandeld = quay heeft zelf reviewed_at, of hangt onder een nagekeken StopPlace. "Gegroepeerd" = onder een
     * StopPlace met meerdere quays (niet-wrapper); "losstaand akkoord" = wrapper. De werkvoorraad blijft StopPlace-niveau.
     */
    public ReviewStats reviewStats() {
        String sql = """
            SELECT count(*) AS total_quays,
                count(*) FILTER (WHERE NOT s.is_wrapper AND (r.q_rev OR r.s_rev)) AS grouped,
                count(*) FILTER (WHERE s.is_wrapper AND (r.q_rev OR r.s_rev)) AS standalone_ok,
                (SELECT count(*) FROM chb.stop s2 WHERE s2.merged_into_id IS NULL
                    AND NOT EXISTS (SELECT 1 FROM chb.stop_attribute a WHERE a.stop_id = s2.id AND a.key IN (?, ?))) AS unreviewed_stop_places
            FROM chb.quay q
            JOIN chb.stop s ON s.id = q.stop_id AND s.merged_into_id IS NULL
            CROSS JOIN LATERAL (SELECT
                EXISTS (SELECT 1 FROM chb.quay_attribute a WHERE a.quay_id = q.id AND a.key = ?) AS q_rev,
                EXISTS (SELECT 1 FROM chb.stop_attribute a WHERE a.stop_id = s.id AND a.key = ?) AS s_rev) r
            WHERE q.merged_into_id IS NULL
            AND NOT EXISTS (SELECT 1 FROM chb.stop_attribute a WHERE a.stop_id = s.id AND a.key = ?)
            """;
        return jdbcTemplate.query(sql, rs -> {
            rs.next();
            return new ReviewStats(rs.getLong("total_quays"), rs.getLong("grouped"), rs.getLong("standalone_ok"),
                rs.getLong("unreviewed_stop_places"));
        }, REVIEWED_ATTRIBUTE_KEY, DELETED_ATTRIBUTE_KEY, REVIEWED_ATTRIBUTE_KEY, REVIEWED_ATTRIBUTE_KEY, DELETED_ATTRIBUTE_KEY);
    }

    public List<UnreviewedStop> findUnreviewed(String query, int limit, int offset) {
        // Zelfde populatie als reviewStats: levende StopPlaces (chb.stop). reviewed_at van een quay telt hier niet
        // apart: een quay-wijziging markeert ook zijn wrapper (chb.cur_mark_reviewed, V111).
        String sql = """
            SELECT id, name, path, stop_type, is_synthetic FROM (
                SELECT s.id, s.name, concat_ws(' > ', g.name, s.name) AS path, s.stop_type, s.is_synthetic
                FROM chb.stop s LEFT JOIN chb.stop g ON g.id = s.parent_id
                WHERE s.merged_into_id IS NULL
                AND NOT EXISTS (SELECT 1 FROM chb.stop_attribute a WHERE a.stop_id = s.id AND a.key = ?)
                AND NOT EXISTS (SELECT 1 FROM chb.stop_attribute a WHERE a.stop_id = s.id AND a.key = ?)
            ) t
            WHERE path ILIKE ?
            ORDER BY path
            LIMIT ? OFFSET ?
            """;
        String likePattern = "%" + (query == null ? "" : query) + "%";
        return jdbcTemplate.query(sql,
            (rs, rowNum) -> new UnreviewedStop(rs.getLong("id"), rs.getString("name"), rs.getString("path"),
                rs.getString("stop_type"), rs.getBoolean("is_synthetic")),
            REVIEWED_ATTRIBUTE_KEY, DELETED_ATTRIBUTE_KEY, likePattern, limit, offset);
    }

    /**
     * Namen die zowel een nagekeken als een niet-nagekeken StopPlace dragen — de niet-nagekeken
     * exemplaren zijn mogelijk gemist doordat een curator de naam al bij een gelijknamige halte
     * zag afvinken. Matcht op naam hoofdletter-/spatie-ongevoelig ({@code lower(btrim(name))}).
     * Pagineert op groep (naam), niet op individuele rij.
     */
    public List<DuplicateNameGroup> findDuplicateNames(String query, int limit, int offset) {
        String groupsSql = """
            SELECT norm_name, min(name) AS name, reviewed_count FROM (
                SELECT lower(btrim(s.name)) AS norm_name, s.name,
                    count(*) FILTER (WHERE EXISTS (SELECT 1 FROM chb.stop_attribute a WHERE a.stop_id = s.id AND a.key = ?))
                        OVER (PARTITION BY lower(btrim(s.name))) AS reviewed_count,
                    count(*) FILTER (WHERE NOT EXISTS (SELECT 1 FROM chb.stop_attribute a WHERE a.stop_id = s.id AND a.key = ?))
                        OVER (PARTITION BY lower(btrim(s.name))) AS unreviewed_count
                FROM chb.stop s
                WHERE s.merged_into_id IS NULL
                AND NOT EXISTS (SELECT 1 FROM chb.stop_attribute a WHERE a.stop_id = s.id AND a.key = ?)
            ) t
            WHERE reviewed_count > 0 AND unreviewed_count > 0 AND norm_name ILIKE ?
            GROUP BY norm_name, reviewed_count
            ORDER BY name
            LIMIT ? OFFSET ?
            """;
        String likePattern = "%" + (query == null ? "" : query) + "%";
        record NameGroup(String normName, String name, long reviewedCount) {
        }
        List<NameGroup> groups = jdbcTemplate.query(groupsSql,
            (rs, rowNum) -> new NameGroup(rs.getString("norm_name"), rs.getString("name"), rs.getLong("reviewed_count")),
            REVIEWED_ATTRIBUTE_KEY, REVIEWED_ATTRIBUTE_KEY, DELETED_ATTRIBUTE_KEY, likePattern, limit, offset);
        if (groups.isEmpty()) {
            return List.of();
        }

        // Array-literal als tekst i.p.v. java.sql.Array — zelfde patroon als StopEditorRepository#groupStops.
        String normNamesLiteral = groups.stream().map(NameGroup::normName)
            .map(n -> "\"" + n.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
            .collect(Collectors.joining(",", "{", "}"));
        String unreviewedSql = """
            SELECT s.id, s.name, concat_ws(' > ', g.name, s.name) AS path, s.stop_type, s.is_synthetic,
                lower(btrim(s.name)) AS norm_name
            FROM chb.stop s LEFT JOIN chb.stop g ON g.id = s.parent_id
            WHERE s.merged_into_id IS NULL
            AND NOT EXISTS (SELECT 1 FROM chb.stop_attribute a WHERE a.stop_id = s.id AND a.key = ?)
            AND NOT EXISTS (SELECT 1 FROM chb.stop_attribute a WHERE a.stop_id = s.id AND a.key = ?)
            AND lower(btrim(s.name)) = ANY(?::text[])
            ORDER BY path
            """;
        Map<String, List<UnreviewedStop>> byNormName = new LinkedHashMap<>();
        jdbcTemplate.query(unreviewedSql, rs -> {
            byNormName.computeIfAbsent(rs.getString("norm_name"), k -> new ArrayList<>())
                .add(new UnreviewedStop(rs.getLong("id"), rs.getString("name"), rs.getString("path"),
                    rs.getString("stop_type"), rs.getBoolean("is_synthetic")));
        }, REVIEWED_ATTRIBUTE_KEY, DELETED_ATTRIBUTE_KEY, normNamesLiteral);

        return groups.stream()
            .map(g -> new DuplicateNameGroup(g.name(), g.reviewedCount(), byNormName.getOrDefault(g.normName(), List.of())))
            .toList();
    }

    public boolean exists(long id) {
        Boolean result = jdbcTemplate.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM chb.stop WHERE id = ?) OR EXISTS(SELECT 1 FROM chb.quay WHERE id = ?)", Boolean.class, id, id);
        return Boolean.TRUE.equals(result);
    }
}
