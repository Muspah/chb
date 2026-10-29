package be.gtfsbe.chb.repository;

import be.gtfsbe.chb.dto.DuplicateNameGroup;
import be.gtfsbe.chb.dto.GeoJsonFeature;
import be.gtfsbe.chb.dto.GeoJsonFeatureCollection;
import be.gtfsbe.chb.dto.GeoJsonGeometry;
import be.gtfsbe.chb.dto.MapBounds;
import be.gtfsbe.chb.dto.NameIssue;
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
        SELECT s.id, s.name, s.stop_type, s.public_code, s.lat, s.lon, s.parent_id, s.is_synthetic, s.is_wrapper, s.disabled_at,
               CASE WHEN NOT s.is_wrapper THEN (
                   SELECT ST_AsText(ST_Buffer(ST_ConvexHull(ST_Collect(q.geom))::geography, 8)::geometry, 6)
                   FROM (SELECT s.id AS sid UNION ALL SELECT c.id FROM chb.stop c WHERE c.parent_id = s.id) x
                   JOIN chb.quay q ON q.stop_id = x.sid
                   WHERE q.merged_into_id IS NULL AND q.geom IS NOT NULL
               ) END AS area_wkt
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
        return queryMapFeatures(sql, bounds, showDisabled, DELETED_ATTRIBUTE_KEY);
    }

    /** Voor de "doorlichtingskaart" (build-instruction.md-uitbreiding): enkel nog niet nagekeken haltes. */
    public GeoJsonFeatureCollection findUnreviewedAsMapFeatures(MapBounds bounds, boolean showDisabled) {
        String sql = MAP_FEATURES_BASE_SQL + disabledFilterSql(showDisabled) + """
            AND NOT EXISTS (
                SELECT 1 FROM chb.stop_attribute a WHERE a.stop_id = s.id AND a.key = ?
            )
            """;
        return queryMapFeatures(sql, bounds, showDisabled, DELETED_ATTRIBUTE_KEY, REVIEWED_ATTRIBUTE_KEY);
    }

    // Vraagt telkens één rij méér op dan de cap: als die erbij zit, weten we dat afgekapt is,
    // zonder een aparte COUNT(*)-query (die op een groot uitgezoomd viewport zelf ook traag zou
    // zijn — precies wat we vermijden).
    private GeoJsonFeatureCollection queryMapFeatures(String baseSql, MapBounds bounds, boolean showDisabled, Object... extraParams) {
        String sql = baseSql + "LIMIT ?"; // geen ORDER BY: boven de limiet gooien we toch alles weg
        Object[] params = new Object[4 + extraParams.length + 1];
        params[0] = bounds.minLon();
        params[1] = bounds.minLat();
        params[2] = bounds.maxLon();
        params[3] = bounds.maxLat();
        System.arraycopy(extraParams, 0, params, 4, extraParams.length);
        params[params.length - 1] = MAP_FEATURES_LIMIT + 1;
        List<GeoJsonFeature> rows = jdbcTemplate.query(sql, (rs, rowNum) -> mapFeatureRow(rs), params);
        boolean truncated = rows.size() > MAP_FEATURES_LIMIT;
        // Te veel in beeld: bewust niets tonen (geen willekeurig deel), de gebruiker zoomt in.
        if (truncated) return new GeoJsonFeatureCollection(List.of(), true);
        List<GeoJsonFeature> features = new ArrayList<>(rows);
        features.addAll(quayDotsFor(features, showDisabled));
        return new GeoJsonFeatureCollection(features, truncated);
    }

    // Geen OR/IN-subselect op q.stop_id: dat gaf per StopPlace een seq scan over alle quays (5,7 s op 466
    // stops, gemeten 2026-10-13); de UNION-ALL-join gebruikt ix_quay_stop (45 ms).
    // Een groep (StopPlace met vlak) heeft geen eigen stip meer: haar quays zijn de stippen, het
    // vlak toont dat ze bij elkaar horen. Quays van een general: die van zijn monomodals (één niveau).
    private List<GeoJsonFeature> quayDotsFor(List<GeoJsonFeature> stops, boolean showDisabled) {
        String ids = stops.stream().filter(f -> "Polygon".equals(f.geometry().type()))
                .map(f -> String.valueOf(f.properties().get("id"))).collect(Collectors.joining(",", "{", "}"));
        if (ids.equals("{}")) return List.of();
        return jdbcTemplate.query("""
            SELECT q.id, q.name, q.public_code, q.lat, q.lon, q.stop_id, q.is_synthetic, q.disabled_at
            FROM (SELECT unnest(?::bigint[]) AS sid
                  UNION ALL SELECT c.id FROM chb.stop c WHERE c.parent_id = ANY(?::bigint[])) x
            JOIN chb.quay q ON q.stop_id = x.sid
            WHERE q.merged_into_id IS NULL AND q.lat IS NOT NULL AND q.lon IS NOT NULL
            """ + (showDisabled ? "" : "AND q.disabled_at IS NULL\n"), (rs, n) -> {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", rs.getLong("id"));
            p.put("name", rs.getString("name"));
            p.put("publicCode", rs.getString("public_code"));
            p.put("parentId", rs.getLong("stop_id"));
            p.put("isSynthetic", rs.getBoolean("is_synthetic"));
            p.put("isWrapper", false);
            p.put("isQuay", true);
            p.put("disabledAt", rs.getObject("disabled_at", OffsetDateTime.class));
            return new GeoJsonFeature(GeoJsonGeometry.point(rs.getDouble("lon"), rs.getDouble("lat")), p);
        }, ids, ids);
    }

    // "POLYGON((x y,x y,...))" -> één buitenring; de buffer levert nooit gaten.
    private static double[][][] polygonFromWkt(String wkt) {
        String[] pts = wkt.substring(wkt.indexOf("((") + 2, wkt.lastIndexOf("))")).split(",");
        double[][] ring = new double[pts.length][];
        for (int i = 0; i < pts.length; i++) {
            String[] xy = pts[i].trim().split(" ");
            ring[i] = new double[] {Double.parseDouble(xy[0]), Double.parseDouble(xy[1])};
        }
        return new double[][][] {ring};
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
        String areaWkt = rs.getString("area_wkt");
        if (areaWkt != null && areaWkt.startsWith("POLYGON")) {
            properties.put("isArea", true);
            return new GeoJsonFeature(GeoJsonGeometry.polygon(polygonFromWkt(areaWkt)), properties);
        }
        return new GeoJsonFeature(GeoJsonGeometry.point(rs.getDouble("lon"), rs.getDouble("lat")), properties);
    }

    /**
     * Detail van een StopPlace ({@code chb.stop}: general/monomodal/wrapper) of van een quay
     * ({@code chb.quay}); de id-ruimtes zijn disjunct (gedeelde reeks, D6). De kaart toont
     * StopPlaces; quays bereik je via de kinderenlijst van hun StopPlace. Bronnen en
     * first/last-seen van een StopPlace zijn de samenvatting over al zijn quays.
     */
    public Optional<StopDetail> findDetail(long id) {
        // Id-ruimtes zijn disjunct: een StopPlace-treffer (veelvoorkomend) kost 1 round-trip, een quay 2.
        Optional<StopDetail> stopPlace = fetchDetail(id, false);
        return stopPlace.isPresent() ? stopPlace : fetchDetail(id, true);
    }

    private static final String STOP_PLACE_SQL = """
        SELECT s.id, s.name, s.stop_type, s.public_code, s.lat, s.lon, s.parent_id, s.is_synthetic, s.is_wrapper, s.disabled_at,
               concat_ws(' > ', g.name, s.name) AS path,
               CASE WHEN s.is_wrapper THEN 'wrapper' ELSE s.kind END AS kind, s.transport_mode, s.other_transport_modes
        FROM chb.stop s LEFT JOIN chb.stop g ON g.id = s.parent_id
        WHERE s.id = ?
        """;

    // Het type is dat van de StopPlace (een quay heeft zelf geen stop_type); parentId = die StopPlace,
    // ook bij een wrapper (chb.update_stop leest "zelfde StopPlace" als geen wijziging).
    private static final String QUAY_SQL = """
        SELECT q.id, q.name, s.stop_type, q.public_code, q.lat, q.lon, q.stop_id AS parent_id, q.is_synthetic, false AS is_wrapper,
               q.disabled_at, NULL::text AS kind, q.transport_mode, q.other_transport_modes,
               CASE WHEN s.is_wrapper THEN concat_ws(' > ', g.name, q.name) ELSE concat_ws(' > ', g.name, s.name, q.name) END AS path
        FROM chb.quay q JOIN chb.stop s ON s.id = q.stop_id LEFT JOIN chb.stop g ON g.id = s.parent_id
        WHERE q.id = ?
        """;

    // raw_stop.stop_id wijst naar een QUAY; een StopPlace vat zijn quays samen.
    private static final String QUAYS_OF_STOP_PLACE = "SELECT id FROM chb.quay WHERE stop_id = ?";

    // Uitgeschakelde kinderen worden bewust NIET uitgefilterd (anders dan verwijderde) — dit lijstje
    // in het detailpaneel heeft geen eigen toon-toggle; de frontend markeert ze via disabledAt.
    private static final String CHILDREN_SQL = """
        SELECT s.id, s.name, s.public_code, s.disabled_at, false AS is_quay,
               CASE WHEN s.is_wrapper THEN 'wrapper' ELSE s.kind END AS kind, s.transport_mode, s.other_transport_modes
        FROM chb.stop s
        WHERE s.parent_id = ? AND s.merged_into_id IS NULL
        AND NOT EXISTS (SELECT 1 FROM chb.stop_attribute a WHERE a.stop_id = s.id AND a.key = ?)
        UNION ALL
        SELECT q.id, q.name, q.public_code, q.disabled_at, true, NULL::text, q.transport_mode, q.other_transport_modes FROM chb.quay q
        WHERE q.stop_id = ? AND q.merged_into_id IS NULL
        ORDER BY 2
        """;

    /**
     * Haalt hoofdrij, first/last-seen, attributen, bronnen en kinderen op als één multi-statement
     * call: pgjdbc pipelinet die in één netwerk-round-trip. Dat is de moeite zodra de database
     * achter een tunnel zit, waar elke aparte query de volle latency kost.
     */
    private Optional<StopDetail> fetchDetail(long id, boolean quay) {
        String scope = quay ? "stop_id = ?" : "stop_id IN (" + QUAYS_OF_STOP_PLACE + ")";
        String sql = (quay ? QUAY_SQL : STOP_PLACE_SQL)
            + ";SELECT min(first_seen_at) AS first_seen, max(last_seen_at) AS last_seen FROM chb.raw_stop WHERE " + scope
            + ";" + (quay
                ? "SELECT key, value FROM chb.quay_attribute WHERE quay_id = ? ORDER BY key"
                : "SELECT key, value FROM chb.stop_attribute WHERE stop_id = ? ORDER BY key")
            // Bewust niet via view chb.stop_sources: het ORDER BY daarin blokkeert predicate pushdown
            // (sorteert alle ~130K raw_stop-rijen: ~570 ms per call i.p.v. <1 ms).
            + ";SELECT rs.source_schema, rs.source_id, rs.name AS source_name FROM chb.quay q"
            + " JOIN chb.raw_stop rs ON rs.stop_id = q.id WHERE q.merged_into_id IS NULL AND q."
            + (quay ? "id" : "stop_id") + " = ? ORDER BY q.name, rs.source_schema"
            + (quay ? "" : ";" + CHILDREN_SQL);
        Object[] params = quay
            ? new Object[] {id, id, id, id}
            : new Object[] {id, id, id, id, id, DELETED_ATTRIBUTE_KEY, id};
        return jdbcTemplate.execute((java.sql.Connection con) -> {
            try (java.sql.PreparedStatement ps = con.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
                ps.execute();
                return readDetail(ps, id, quay);
            }
        });
    }

    private Optional<StopDetail> readDetail(java.sql.PreparedStatement ps, long id, boolean quay) throws SQLException {
        ResultSet main = ps.getResultSet();
        if (!main.next()) {
            return Optional.empty();
        }
        // rs.wasNull() reflecteert enkel de laatst-gelezen kolom, dus elke nullable kolom
        // moet meteen na het lezen omgezet worden.
        long parentIdRaw = main.getLong("parent_id");
        Long parentId = main.wasNull() ? null : parentIdRaw;
        double latRaw = main.getDouble("lat");
        Double lat = main.wasNull() ? null : latRaw;
        double lonRaw = main.getDouble("lon");
        Double lon = main.wasNull() ? null : lonRaw;
        OffsetDateTime disabledAt = main.getObject("disabled_at", OffsetDateTime.class);
        long rowId = main.getLong("id");
        String name = main.getString("name");
        String stopType = main.getString("stop_type");
        String publicCode = main.getString("public_code");
        boolean synthetic = main.getBoolean("is_synthetic");
        boolean wrapper = main.getBoolean("is_wrapper");
        String path = main.getString("path");
        String kind = main.getString("kind");
        String transportMode = main.getString("transport_mode");
        List<String> otherModes = textArray(main, "other_transport_modes");

        ps.getMoreResults();
        ResultSet seenRs = ps.getResultSet();
        seenRs.next();
        OffsetDateTime firstSeen = seenRs.getObject("first_seen", OffsetDateTime.class);
        OffsetDateTime lastSeen = seenRs.getObject("last_seen", OffsetDateTime.class);

        ps.getMoreResults();
        Map<String, String> attributes = new LinkedHashMap<>();
        ResultSet attrRs = ps.getResultSet();
        while (attrRs.next()) {
            attributes.put(attrRs.getString("key"), attrRs.getString("value"));
        }
        String reviewedAt = attributes.remove(REVIEWED_ATTRIBUTE_KEY);
        String deletedAt = attributes.remove(DELETED_ATTRIBUTE_KEY);
        attributes.remove(SOURCE_PARENT_HINT_ATTRIBUTE_KEY);
        attributes.remove("_resolved_meta");

        ps.getMoreResults();
        List<StopSource> sources = new ArrayList<>();
        ResultSet srcRs = ps.getResultSet();
        while (srcRs.next()) {
            sources.add(new StopSource(srcRs.getString("source_schema"), srcRs.getString("source_id"), srcRs.getString("source_name")));
        }

        List<StopChildSummary> children = new ArrayList<>();
        if (!quay) {
            ps.getMoreResults();
            ResultSet childRs = ps.getResultSet();
            while (childRs.next()) {
                children.add(new StopChildSummary(childRs.getLong("id"), childRs.getString("name"),
                    childRs.getString("public_code"), childRs.getObject("disabled_at", OffsetDateTime.class),
                    childRs.getBoolean("is_quay"), childRs.getString("kind"), childRs.getString("transport_mode"),
                    textArray(childRs, "other_transport_modes")));
            }
        }
        return Optional.of(new StopDetail(rowId, name, stopType, publicCode, lat, lon, synthetic, wrapper, quay,
            parentId, path, attributes, sources, children, reviewedAt, deletedAt, disabledAt, firstSeen, lastSeen,
            kind, transportMode, otherModes));
    }

    private static List<String> textArray(ResultSet rs, String column) throws SQLException {
        java.sql.Array array = rs.getArray(column);
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    public List<StopSearchResult> search(String query) {
        // StopPlaces (incl. wrappers, die de naam van hun quay dragen); het pad is "general > StopPlace". Een
        // StopPlace kan meerdere bronnen hebben (meerdere quays/feeds), vandaar de string_agg i.p.v. een join.
        String sql = """
            SELECT s.id, concat_ws(' > ', g.name, s.name) AS path,
                (SELECT string_agg(ss.source_schema || ':' || ss.source_id, ', ' ORDER BY ss.source_schema, ss.source_id)
                 FROM chb.raw_stop ss JOIN chb.quay q ON q.id = ss.stop_id WHERE q.stop_id = s.id AND q.merged_into_id IS NULL) AS source_codes
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

    /**
     * Filter voor het namenrapport (issue #3): reden → SQL-predicaat op {@code n} (chb.quay_names of chb.stop_names).
     * Bron van waarheid zijn de views; hier wordt niets herberekend. bru_single/dg_no_de zijn geen fout, alleen info.
     */
    private static final Map<String, String> QUAY_NAME_REASONS = Map.of(
        "region_unknown", "n.name_reason = 'region_unknown'",
        "no_region_language", "n.name_reason = 'no_region_language'",
        "no_language", "n.name_reason = 'no_language'",
        "bare_code", "n.name_reason = 'bare_code'",
        // Brussel: default_lang is NULL bij tweetalig, gezet als de bron maar één van fr/nl levert.
        "bru_single", "n.region = 'bru' AND n.name_reason IS NULL AND n.default_lang IS NOT NULL",
        // Duitstalige Gemeenschap: default de, anders fr.
        "dg_no_de", "n.region = 'dg' AND n.name_reason IS NULL AND n.default_lang = 'fr'");
    private static final Map<String, String> STOP_NAME_REASONS = Map.of(
        "disagree", "n.disagree_langs IS NOT NULL",
        "region_mixed", "n.name_reason = 'region_mixed'",
        "general_region_unknown", "n.name_reason = 'general_region_unknown'",
        "general_no_language", "n.name_reason = 'general_no_language'");

    public static boolean isNameIssueReason(String reason) {
        return QUAY_NAME_REASONS.containsKey(reason) || STOP_NAME_REASONS.containsKey(reason);
    }

    public List<String> findFeeds() {
        return jdbcTemplate.queryForList("SELECT DISTINCT source_schema FROM chb.raw_stop ORDER BY 1", String.class);
    }

    /**
     * Live quays/StopPlaces met het gevraagde naamprobleem. De views lezen volledig (~4-7 s; de reden is niet
     * naar binnen te duwen), dus altijd gepagineerd. Feeds en gemeente: van de quay zelf, of voor een StopPlace
     * van zijn quays en die van zijn directe kinderen (een general).
     */
    public List<NameIssue> findNameIssues(String reason, String feed, int limit, int offset) {
        String namesText = "(SELECT string_agg(key || ': ' || value, ' · ' ORDER BY key) FROM jsonb_each_text(n.names)) AS names_text";
        String sql;
        String quayPredicate = QUAY_NAME_REASONS.get(reason);
        if (quayPredicate != null) {
            sql = """
                SELECT q.id, true AS is_quay, q.name, %s, n.default_name, n.default_lang, n.name_reason, n.disagree_langs,
                    f.feeds, m.value::jsonb->>'municipality' AS municipality
                FROM (SELECT *, NULL::text[] AS disagree_langs FROM chb.quay_names) n
                JOIN chb.quay q ON q.id = n.quay_id
                LEFT JOIN chb.quay_attribute m ON m.quay_id = q.id AND m.key = '_resolved_meta'
                CROSS JOIN LATERAL (SELECT array_agg(DISTINCT rs.source_schema ORDER BY rs.source_schema) AS feeds
                    FROM chb.raw_stop rs WHERE rs.stop_id = q.id) f
                WHERE q.merged_into_id IS NULL AND q.disabled_at IS NULL AND %s
                AND (? = '' OR ? = ANY(f.feeds))
                ORDER BY q.name, q.id LIMIT ? OFFSET ?
                """.formatted(namesText, quayPredicate);
        } else {
            // Geen OR met subselect op quay.stop_id (seq scan per StopPlace), zie de kaartquery.
            sql = """
                SELECT s.id, false AS is_quay, s.name, %s, n.default_name, n.default_lang, n.name_reason, n.disagree_langs,
                    f.feeds, f.municipality
                FROM chb.stop_names n
                JOIN chb.stop s ON s.id = n.stop_id
                CROSS JOIN LATERAL (
                    SELECT array_agg(DISTINCT rs.source_schema ORDER BY rs.source_schema) AS feeds,
                        min(m.value::jsonb->>'municipality') AS municipality
                    FROM (SELECT s.id AS sid UNION ALL
                          SELECT c.id FROM chb.stop c WHERE c.parent_id = s.id AND c.merged_into_id IS NULL) x
                    JOIN chb.quay q ON q.stop_id = x.sid AND q.merged_into_id IS NULL
                    LEFT JOIN chb.raw_stop rs ON rs.stop_id = q.id
                    LEFT JOIN chb.quay_attribute m ON m.quay_id = q.id AND m.key = '_resolved_meta') f
                WHERE s.merged_into_id IS NULL AND s.disabled_at IS NULL AND %s
                AND (? = '' OR ? = ANY(f.feeds))
                ORDER BY s.name, s.id LIMIT ? OFFSET ?
                """.formatted(namesText, STOP_NAME_REASONS.get(reason));
        }
        return jdbcTemplate.query(sql,
            (rs, rowNum) -> new NameIssue(rs.getLong("id"), rs.getBoolean("is_quay"), rs.getString("name"),
                rs.getString("names_text"), rs.getString("default_name"), rs.getString("default_lang"),
                rs.getString("name_reason"), textArray(rs, "disagree_langs"), textArray(rs, "feeds"),
                rs.getString("municipality")),
            feed, feed, limit, offset);
    }

    public boolean exists(long id) {
        Boolean result = jdbcTemplate.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM chb.stop WHERE id = ?) OR EXISTS(SELECT 1 FROM chb.quay WHERE id = ?)", Boolean.class, id, id);
        return Boolean.TRUE.equals(result);
    }
}
