package be.gtfsbe.chb.repository;

import be.gtfsbe.chb.dto.BrusselsNames;
import be.gtfsbe.chb.dto.DuplicateNameGroup;
import be.gtfsbe.chb.dto.DuplicateQuay;
import be.gtfsbe.chb.dto.MapBounds;
import be.gtfsbe.chb.dto.GroupCandidate;
import be.gtfsbe.chb.dto.ModeMismatch;
import be.gtfsbe.chb.dto.MultimodeQuay;
import be.gtfsbe.chb.dto.NameIssue;
import be.gtfsbe.chb.dto.ReviewMismatch;
import be.gtfsbe.chb.dto.ReviewStats;
import be.gtfsbe.chb.dto.StopChildSummary;
import be.gtfsbe.chb.dto.StopDetail;
import be.gtfsbe.chb.dto.StopHistoryEntry;
import be.gtfsbe.chb.dto.StopPlaceTypeOption;
import be.gtfsbe.chb.dto.StopSearchResult;
import be.gtfsbe.chb.dto.StopSource;
import be.gtfsbe.chb.dto.TecCityIssue;
import be.gtfsbe.chb.dto.UnreviewedStop;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
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
 * {@code chb.raw_stop} en {@code chb.stop_sources} (V7) zijn
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

    /** Markering van een automatische bronstationgroep (ndov V143); blijft staan als een curator de groep bewerkt. */
    private static final String SOURCE_STATION_ATTRIBUTE_KEY = "_source_station";

    private final JdbcTemplate jdbcTemplate;
    private final StopNameCache names;

    public StopViewerRepository(JdbcTemplate viewerJdbcTemplate, StopNameCache names) {
        this.jdbcTemplate = viewerJdbcTemplate;
        this.names = names;
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

    // Een halte met coördinaten wordt van de kaart geweerd zodra haar eigen (directe) parent ook
    // coördinaten heeft — dat betekent dat die parent haar al visueel vertegenwoordigt als één
    // punt (bv. een "snel groeperen"-groeperingsknoop over twee haltes aan weerszijden van
    // dezelfde straat). Pas bij doorklikken op die parent worden de onderliggende haltes zichtbaar
    // (zie findChildren). Een parent zonder eigen coördinaten (bv. een groot stationscomplex)
    // verandert niets aan het bestaande gedrag: de kinderen blijven dan elk apart zichtbaar, want
    // er is geen enkel punt dat ze kan vertegenwoordigen.
    //
    // Geen viewport-filter: de kaart haalt de volledige set één keer op, gecachet in StopMapCache (>1 s per opbouw).
    // Vlak: quad_segs=2 en 5 decimalen (~1 m) i.p.v. de standaard 8 en 6, gemiddeld 35 punten per ring
    // waren 70% van de payload; bij een buffer van 8 m zie je het verschil niet.
    // Bufferen in EPSG:3035 (ETRS89-LAEA, meter, heel Europa) i.p.v. op geography: de vlakken waren 1,9 van de
    // 2,2 s query, zo 0,8 s; vorm gelijk op <1 m (gemeten 2026-11-11). Lambert 72 dekt de haltes over de grens niet.
    private static final String MAP_FEATURES_BASE_SQL = """
        SELECT s.id, s.name, s.public_code, s.lat, s.lon, s.parent_id, s.is_synthetic, s.is_wrapper, s.disabled_at, s.transport_mode,
               EXISTS (SELECT 1 FROM chb.stop_attribute a WHERE a.stop_id = s.id AND a.key = ?) AS reviewed,
               CASE WHEN NOT s.is_wrapper THEN (
                   SELECT ST_AsText(ST_Transform(ST_Buffer(ST_Transform(ST_ConvexHull(ST_Collect(q.geom)), 3035), 8, 'quad_segs=2'), 4326), 5)
                   FROM (SELECT s.id AS sid UNION ALL SELECT c.id FROM chb.stop c WHERE c.parent_id = s.id) x
                   JOIN chb.quay q ON q.stop_id = x.sid
                   WHERE q.merged_into_id IS NULL AND q.geom IS NOT NULL
                     -- uitgeschakelde quays trekken geen punt in de vorm (#21), tenzij de hele StopPlace uit staat
                     AND (q.disabled_at IS NULL OR s.disabled_at IS NOT NULL)
               ) END AS area_wkt
        FROM chb.stop s
        WHERE s.lat IS NOT NULL AND s.lon IS NOT NULL AND s.merged_into_id IS NULL
        AND NOT EXISTS (
            SELECT 1 FROM chb.stop p
            WHERE p.id = s.parent_id AND p.lat IS NOT NULL AND p.lon IS NOT NULL
        )
        AND NOT EXISTS (
            SELECT 1 FROM chb.stop_attribute a WHERE a.stop_id = s.id AND a.key = ?
        )
        """;

    // Vlaggen per rij (bitmasker), zodat de kaart "enkel nog te bekijken" en "toon uitgeschakelde" in de
    // browser filtert i.p.v. per variant een nieuwe set op te halen. Voor een quay van een vlak tellen
    // ook die van de StopPlace van het vlak: een nagekeken of uitgeschakelde groep verbergt haar quays mee.
    static final int FLAG_DISABLED = 1;
    static final int FLAG_REVIEWED = 2;

    /**
     * Alle kaartpunten als compacte rijen (geen GeoJSON: ~92K features, de sleutels per feature waren het
     * grootste deel van de payload). Kolommen, zie {@code decodeMapRow} in app.js:
     * {@code [id, lon, lat, name, publicCode, parentId, transportMode, disabledAt, kind, flags, extra?]}, met kind
     * {@code q} quay, {@code w} wrapper, {@code g} curated groep (synthetisch), {@code p} andere StopPlace, en
     * flags {@link #FLAG_DISABLED}/{@link #FLAG_REVIEWED}. Ook uitgeschakelde en nagekeken haltes: filteren doet de kaart.
     * {@code extra} is bij een StopPlace een {@code ring} ({@code [[lon,lat],...]}): dan is het een vlak, en volgen
     * zijn quays als eigen rijen; bij zo'n quay is {@code extra} het id van dat vlak (ook als de quay onder een
     * monomodal ervan hangt), zodat de kaart bij een gedeeltelijke verversing weet welke quays bij een vlak horen.
     */
    public List<Object[]> findMapRows(String lang) {
        return findMapRows(lang, null);
    }

    /**
     * Enkel de StopPlaces met hun punt in {@code bounds} (en de quays van hun vlakken), voor de verversing rond
     * een curatoractie; {@code null} = alles. Snel dankzij de GiST-index op {@code chb.stop.geom}.
     */
    public List<Object[]> findMapRows(String lang, MapBounds bounds) {
        String sql = MAP_FEATURES_BASE_SQL;
        List<Object> params = new ArrayList<>(List.of(REVIEWED_ATTRIBUTE_KEY, DELETED_ATTRIBUTE_KEY));
        if (bounds != null) {
            sql += "AND s.geom && ST_MakeEnvelope(?, ?, ?, ?, 4326)\n";
            params.addAll(List.of(bounds.minLon(), bounds.minLat(), bounds.maxLon(), bounds.maxLat()));
        }
        List<Object[]> rows = new ArrayList<>(jdbcTemplate.query(sql, (rs, rowNum) -> mapRow(rs, lang), params.toArray()));
        rows.addAll(quayRowsFor(rows, lang));
        return rows;
    }

    // Geen OR/IN-subselect op q.stop_id: dat gaf per StopPlace een seq scan over alle quays (5,7 s op 466
    // stops, gemeten 2026-10-13); de UNION-ALL-join gebruikt ix_quay_stop (45 ms).
    // Een groep (StopPlace met vlak) heeft geen eigen stip meer: haar quays zijn de stippen, het
    // vlak toont dat ze bij elkaar horen. Quays van een general: die van zijn monomodals (één niveau).
    private List<Object[]> quayRowsFor(List<Object[]> stops, String lang) {
        Map<Long, Integer> areaFlags = new HashMap<>();
        for (Object[] r : stops) {
            if (r.length > 10) areaFlags.put((Long) r[0], (Integer) r[9]);
        }
        if (areaFlags.isEmpty()) return List.of();
        String ids = areaFlags.keySet().stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
        return jdbcTemplate.query("""
            SELECT q.id, q.name, q.public_code, q.lat, q.lon, q.stop_id, q.disabled_at, q.transport_mode, x.root
            FROM (SELECT a AS root, a AS sid FROM unnest(?::bigint[]) a
                  UNION ALL SELECT c.parent_id, c.id FROM chb.stop c WHERE c.parent_id = ANY(?::bigint[])) x
            JOIN chb.quay q ON q.stop_id = x.sid
            WHERE q.merged_into_id IS NULL AND q.lat IS NOT NULL AND q.lon IS NOT NULL
            """, (rs, n) -> {
                OffsetDateTime disabledAt = rs.getObject("disabled_at", OffsetDateTime.class);
                int flags = areaFlags.get(rs.getLong("root")) | (disabledAt != null ? FLAG_DISABLED : 0);
                return new Object[] {
                    rs.getLong("id"), round6(rs.getDouble("lon")), round6(rs.getDouble("lat")),
                    names.display(rs.getLong("id"), lang, rs.getString("name")), rs.getString("public_code"),
                    rs.getLong("stop_id"), rs.getString("transport_mode"), disabledAt, "q", flags, rs.getLong("root")};
            }, ids, ids);
    }

    // ~10 cm: meer decimalen zijn ruis en kosten enkel payload.
    private static double round6(double v) {
        return Math.round(v * 1e6) / 1e6;
    }

    // "POLYGON((x y,x y,...))" -> één buitenring; de buffer levert nooit gaten.
    private static double[][] ringFromWkt(String wkt) {
        String[] pts = wkt.substring(wkt.indexOf("((") + 2, wkt.lastIndexOf("))")).split(",");
        double[][] ring = new double[pts.length][];
        for (int i = 0; i < pts.length; i++) {
            String[] xy = pts[i].trim().split(" ");
            ring[i] = new double[] {Double.parseDouble(xy[0]), Double.parseDouble(xy[1])};
        }
        return ring;
    }

    // De kaart toont alleen: "name" is hier de weergavenaam (UI-taal/default), niet de kale naam.
    private Object[] mapRow(ResultSet rs, String lang) throws SQLException {
        long parentId = rs.getLong("parent_id");
        Long parent = rs.wasNull() ? null : parentId;
        String kind = rs.getBoolean("is_wrapper") ? "w" : rs.getBoolean("is_synthetic") ? "g" : "p";
        OffsetDateTime disabledAt = rs.getObject("disabled_at", OffsetDateTime.class);
        int flags = (disabledAt != null ? FLAG_DISABLED : 0) | (rs.getBoolean("reviewed") ? FLAG_REVIEWED : 0);
        Object[] row = {rs.getLong("id"), round6(rs.getDouble("lon")), round6(rs.getDouble("lat")),
            names.display(rs.getLong("id"), lang, rs.getString("name")), rs.getString("public_code"), parent,
            rs.getString("transport_mode"), disabledAt, kind, flags};
        String areaWkt = rs.getString("area_wkt");
        if (areaWkt == null || !areaWkt.startsWith("POLYGON")) return row;
        Object[] area = Arrays.copyOf(row, 11);
        area[10] = ringFromWkt(areaWkt);
        return area;
    }

    /**
     * Detail van een StopPlace ({@code chb.stop}: general/monomodal/wrapper) of van een quay
     * ({@code chb.quay}); de id-ruimtes zijn disjunct (gedeelde reeks, D6). De kaart toont
     * StopPlaces; quays bereik je via de kinderenlijst van hun StopPlace. Bronnen en
     * first/last-seen van een StopPlace zijn de samenvatting over al zijn quays.
     */
    public Optional<StopDetail> findDetail(long id, String lang) {
        // Id-ruimtes zijn disjunct: een StopPlace-treffer (veelvoorkomend) kost 1 round-trip, een quay 2.
        Optional<StopDetail> stopPlace = fetchDetail(id, false, lang);
        return stopPlace.isPresent() ? stopPlace : fetchDetail(id, true, lang);
    }

    private static final String STOP_PLACE_SQL = """
        SELECT s.id, s.name, s.stop_type, s.public_code, s.lat, s.lon, s.parent_id, s.is_synthetic, s.is_wrapper, s.disabled_at,
               ARRAY[g.id, s.id] AS path_ids, ARRAY[g.name, s.name] AS path_names,
               CASE WHEN s.is_wrapper THEN 'wrapper' ELSE s.kind END AS kind, s.transport_mode, s.other_transport_modes,
               ARRAY[]::text[] AS source_stop_codes
        FROM chb.stop s LEFT JOIN chb.stop g ON g.id = s.parent_id
        WHERE s.id = ?
        """;

    // Het type is dat van de StopPlace (een quay heeft zelf geen stop_type); parentId = die StopPlace,
    // ook bij een wrapper (chb.update_stop leest "zelfde StopPlace" als geen wijziging).
    private static final String QUAY_SQL = """
        SELECT q.id, q.name, s.stop_type, q.public_code, q.lat, q.lon, q.stop_id AS parent_id, q.is_synthetic, false AS is_wrapper,
               q.disabled_at, NULL::text AS kind, q.transport_mode, q.other_transport_modes,
               CASE WHEN s.is_wrapper THEN ARRAY[g.id, q.id] ELSE ARRAY[g.id, s.id, q.id] END AS path_ids,
               CASE WHEN s.is_wrapper THEN ARRAY[g.name, q.name] ELSE ARRAY[g.name, s.name, q.name] END AS path_names
        FROM chb.quay q JOIN chb.stop s ON s.id = q.stop_id LEFT JOIN chb.stop g ON g.id = s.parent_id
        WHERE q.id = ?
        """;

    // raw_stop.stop_id wijst naar een QUAY; een StopPlace vat zijn quays samen.
    private static final String QUAYS_OF_STOP_PLACE = "SELECT id FROM chb.quay WHERE stop_id = ?";

    // Uitgeschakelde kinderen worden bewust NIET uitgefilterd (anders dan verwijderde) — dit lijstje
    // in het detailpaneel heeft geen eigen toon-toggle; de frontend markeert ze via disabledAt.
    private static final String CHILDREN_SQL = """
        SELECT s.id, s.name, s.public_code, s.disabled_at, false AS is_quay,
               CASE WHEN s.is_wrapper THEN 'wrapper' ELSE s.kind END AS kind, s.transport_mode, s.other_transport_modes,
               ARRAY[]::text[] AS source_stop_codes
        FROM chb.stop s
        WHERE s.parent_id = ? AND s.merged_into_id IS NULL
        AND NOT EXISTS (SELECT 1 FROM chb.stop_attribute a WHERE a.stop_id = s.id AND a.key = ?)
        UNION ALL
        SELECT q.id, q.name, q.public_code, q.disabled_at, true, NULL::text, q.transport_mode, q.other_transport_modes,
               ARRAY(SELECT DISTINCT r.source_stop_code FROM chb.raw_stop r WHERE r.stop_id = q.id AND r.source_stop_code IS NOT NULL)
        FROM chb.quay q
        WHERE q.stop_id = ? AND q.merged_into_id IS NULL
        ORDER BY 2
        """;

    /**
     * Haalt hoofdrij, first/last-seen, attributen, bronnen en kinderen op als één multi-statement
     * call: pgjdbc pipelinet die in één netwerk-round-trip. Dat is de moeite zodra de database
     * achter een tunnel zit, waar elke aparte query de volle latency kost.
     */
    private Optional<StopDetail> fetchDetail(long id, boolean quay, String lang) {
        String scope = quay ? "stop_id = ?" : "stop_id IN (" + QUAYS_OF_STOP_PLACE + ")";
        String sql = (quay ? QUAY_SQL : STOP_PLACE_SQL)
            + ";SELECT min(first_seen_at) AS first_seen, max(last_seen_at) AS last_seen FROM chb.raw_stop WHERE " + scope
            + ";" + (quay
                ? "SELECT key, value FROM chb.quay_attribute WHERE quay_id = ? ORDER BY key"
                : "SELECT key, value FROM chb.stop_attribute WHERE stop_id = ? ORDER BY key")
            // Bewust niet via view chb.stop_sources: het ORDER BY daarin blokkeert predicate pushdown
            // (sorteert alle ~130K raw_stop-rijen: ~570 ms per call i.p.v. <1 ms).
            + ";SELECT rs.source_schema, rs.source_id, rs.name AS source_name, rs.source_stop_code FROM chb.quay q"
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
                return readDetail(ps, id, quay, lang);
            }
        });
    }

    private Optional<StopDetail> readDetail(java.sql.PreparedStatement ps, long id, boolean quay, String lang) throws SQLException {
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
        String path = path(main, lang);
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
        String sourceStation = attributes.remove(SOURCE_STATION_ATTRIBUTE_KEY);
        attributes.remove("_source_station_grouped");

        ps.getMoreResults();
        List<StopSource> sources = new ArrayList<>();
        ResultSet srcRs = ps.getResultSet();
        while (srcRs.next()) {
            sources.add(new StopSource(srcRs.getString("source_schema"), srcRs.getString("source_id"), srcRs.getString("source_name"),
                srcRs.getString("source_stop_code")));
        }

        List<StopChildSummary> children = new ArrayList<>();
        if (!quay) {
            ps.getMoreResults();
            ResultSet childRs = ps.getResultSet();
            while (childRs.next()) {
                long childId = childRs.getLong("id");
                String childName = childRs.getString("name");
                children.add(new StopChildSummary(childId, childName, names.display(childId, lang, childName),
                    childRs.getString("public_code"), childRs.getObject("disabled_at", OffsetDateTime.class),
                    childRs.getBoolean("is_quay"), childRs.getString("kind"), childRs.getString("transport_mode"),
                    textArray(childRs, "other_transport_modes"), textArray(childRs, "source_stop_codes")));
            }
        }
        StopNameCache.Names n = names.get(rowId);
        return Optional.of(new StopDetail(rowId, name, names.display(rowId, lang, name), stopType, publicCode, lat, lon,
            synthetic, wrapper, quay, parentId, path, attributes, sources, children, reviewedAt, deletedAt, disabledAt,
            firstSeen, lastSeen, kind, transportMode, otherModes,
            n == null ? Map.of() : n.names(), n == null ? null : n.defaultName(), n == null ? null : n.defaultLang(),
            n == null ? null : n.nameReason(), sourceStation));
    }

    /** "general > StopPlace [> quay]" met weergavenamen; path_ids/path_names zijn parallelle arrays (NULL = geen parent). */
    private String path(ResultSet rs, String lang) throws SQLException {
        Long[] ids = (Long[]) rs.getArray("path_ids").getArray();
        String[] bare = (String[]) rs.getArray("path_names").getArray();
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] != null) {
                parts.add(names.display(ids[i], lang, bare[i]));
            }
        }
        return String.join(" > ", parts);
    }

    private static List<String> textArray(ResultSet rs, String column) throws SQLException {
        java.sql.Array array = rs.getArray(column);
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    public List<StopSearchResult> search(String query, String lang) {
        // StopPlaces (incl. wrappers, die de naam van hun quay dragen); het pad is "general > StopPlace". Een
        // StopPlace kan meerdere bronnen hebben (meerdere quays/feeds), vandaar de string_agg i.p.v. een join.
        // Treffer op het kale pad (o.a. MIVB-codes) of op een naam in eender welke taal (namencache, issue #2).
        String sql = """
            SELECT s.id, ARRAY[g.id, s.id] AS path_ids, ARRAY[g.name, s.name] AS path_names,
                (SELECT string_agg(ss.source_schema || ':' || ss.source_id, ', ' ORDER BY ss.source_schema, ss.source_id)
                 FROM chb.raw_stop ss JOIN chb.quay q ON q.id = ss.stop_id WHERE q.stop_id = s.id AND q.merged_into_id IS NULL) AS source_codes
            FROM chb.stop s LEFT JOIN chb.stop g ON g.id = s.parent_id
            WHERE s.merged_into_id IS NULL
            AND (concat_ws(' > ', g.name, s.name) ILIKE ? OR s.id = ANY(?::bigint[]))
            AND NOT EXISTS (SELECT 1 FROM chb.stop_attribute a WHERE a.stop_id = s.id AND a.key = ?)
            ORDER BY concat_ws(' > ', g.name, s.name)
            LIMIT 50
            """;
        String nameMatches = names.stopIdsMatching(query).stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
        return jdbcTemplate.query(sql,
            (rs, rowNum) -> new StopSearchResult(rs.getLong("id"), path(rs, lang), rs.getString("source_codes")),
            "%" + query + "%", nameMatches, DELETED_ATTRIBUTE_KEY);
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
        // ponytail: geen LIMIT, de view hoort leeg te zijn; pagineren als hij ooit structureel rijen bevat.
        List<ReviewMismatch> mismatches = jdbcTemplate.query("""
            SELECT m.stop_id, s.name, m.quay_id, m.stop_reviewed_at, m.quay_reviewed_at
            FROM chb.wrapper_review_mismatch m JOIN chb.stop s ON s.id = m.stop_id
            ORDER BY m.stop_id
            """, (rs, rowNum) -> new ReviewMismatch(rs.getLong("stop_id"), rs.getString("name"), rs.getLong("quay_id"),
                rs.getString("stop_reviewed_at"), rs.getString("quay_reviewed_at")));
        return jdbcTemplate.query(sql, rs -> {
            rs.next();
            return new ReviewStats(rs.getLong("total_quays"), rs.getLong("grouped"), rs.getLong("standalone_ok"),
                rs.getLong("unreviewed_stop_places"), mismatches);
        }, REVIEWED_ATTRIBUTE_KEY, DELETED_ATTRIBUTE_KEY, REVIEWED_ATTRIBUTE_KEY, REVIEWED_ATTRIBUTE_KEY, DELETED_ATTRIBUTE_KEY);
    }

    public List<UnreviewedStop> findUnreviewed(String query, int limit, int offset) {
        // Zelfde populatie als reviewStats: levende StopPlaces (chb.stop). reviewed_at van een quay telt hier niet
        // apart: de triggers van V142 houden wrapper en quay gelijk (controle: chb.wrapper_review_mismatch).
        String sql = """
            SELECT id, name, path, stop_type, is_synthetic, auto_group FROM (
                SELECT s.id, s.name, concat_ws(' > ', g.name, s.name) AS path, s.stop_type, s.is_synthetic,
                    EXISTS (SELECT 1 FROM chb.stop_attribute a WHERE a.stop_id = s.id AND a.key = '_source_station') AS auto_group
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
                rs.getString("stop_type"), rs.getBoolean("is_synthetic"), rs.getBoolean("auto_group")),
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
                EXISTS (SELECT 1 FROM chb.stop_attribute a WHERE a.stop_id = s.id AND a.key = '_source_station') AS auto_group,
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
                    rs.getString("stop_type"), rs.getBoolean("is_synthetic"), rs.getBoolean("auto_group")));
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
        "general_no_language", "n.name_reason = 'general_no_language'",
        // ndov V156: knoopnaam uit het hoofdmodus-kind (nakijken) / nog steeds geen naam in de gebiedstaal (blokkeert export).
        "general_main_mode_child", "n.name_reason = 'general_main_mode_child'",
        "general_no_region_language", "n.name_reason = 'general_no_region_language'");

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
                    n.main_mode_langs, f.feeds, m.value::jsonb->>'municipality' AS municipality
                FROM (SELECT *, NULL::text[] AS disagree_langs, NULL::text[] AS main_mode_langs FROM chb.quay_names) n
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
                    n.main_mode_langs, f.feeds, f.municipality
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
                rs.getString("name_reason"), textArray(rs, "disagree_langs"), textArray(rs, "main_mode_langs"),
                textArray(rs, "feeds"), rs.getString("municipality")),
            feed, feed, limit, offset);
    }

    /**
     * Live TEC-quays zonder afleidbare plaats en nog zonder 'city' (chb#1). Bron is chb.tec_city_candidates()
     * (ndov V115/V136; voor chb_viewer leesbaar sinds V141). Een opgeslagen 'city' haalt de quay uit de lijst.
     * ~600 rijen, ~0,5 s: geen paginering. De NL-reden wordt een code; het voorstel is de kortere plaats, de
     * hoofdletterprefix of (alleen hoofdletters) het eerste woord.
     */
    public List<TecCityIssue> findTecCityIssues() {
        return jdbcTemplate.query("""
            SELECT c.quay_id, c.name,
                CASE WHEN c.reason LIKE 'kortere voorloop%' THEN 'shorter_prefix'
                     WHEN c.reason LIKE 'alleen hoofdletters%' THEN 'caps_only'
                     WHEN c.reason LIKE 'geen hoofdletterprefix%' THEN 'no_caps_prefix'
                     WHEN c.reason LIKE 'meerwoordige prefix%' THEN 'rare_prefix'
                     ELSE 'other' END AS reason,
                CASE WHEN c.reason LIKE 'kortere voorloop%' THEN substring(c.reason from ': (.*)$')
                     WHEN c.reason LIKE 'alleen hoofdletters%' THEN split_part(c.name, ' ', 1)
                     WHEN c.reason LIKE 'meerwoordige prefix%'
                         THEN substring(c.name from '^([A-ZÀ-ÖØ-Þ][A-ZÀ-ÖØ-Þ''./()-]*(?: [A-ZÀ-ÖØ-Þ][A-ZÀ-ÖØ-Þ''./()-]*)*) ')
                     END AS suggestion,
                m.value::jsonb->>'municipality' AS municipality
            FROM chb.tec_city_candidates() c
            JOIN chb.quay q ON q.id = c.quay_id AND q.merged_into_id IS NULL AND q.disabled_at IS NULL
            LEFT JOIN chb.quay_attribute m ON m.quay_id = q.id AND m.key = '_resolved_meta'
            WHERE c.city IS NULL AND c.stored_city IS NULL
            ORDER BY c.name, c.quay_id
            """,
            (rs, rowNum) -> new TecCityIssue(rs.getLong("quay_id"), rs.getString("name"), rs.getString("reason"),
                rs.getString("suggestion"), rs.getString("municipality")));
    }

    /** Controleview uit ndov V145 (#76): hoort leeg te zijn, dus geen paginering. */
    public List<MultimodeQuay> findMultimodeQuays() {
        return jdbcTemplate.query("SELECT quay_id, name, source_schemas, modes FROM chb.multimode_quay ORDER BY name, quay_id",
            (rs, rowNum) -> new MultimodeQuay(rs.getLong("quay_id"), rs.getString("name"),
                textArray(rs, "source_schemas"), textArray(rs, "modes")));
    }

    /** Controleviews uit ndov V149 (#78) en V144 (#75): horen leeg te zijn, dus geen paginering. Een quay in beide is één rij. */
    public List<ModeMismatch> findModeMismatches() {
        String sql = """
            SELECT quay_id, name, stop_id, coalesce(p.quay_mode, b.quay_mode) AS quay_mode,
                   coalesce(p.stop_mode, b.stop_mode) AS stop_mode, coalesce(p.is_wrapper, false) AS is_wrapper, b.source_station
            FROM chb.stop_place_mode_mismatch p
            FULL JOIN chb.source_station_mode_mismatch b USING (quay_id, name, stop_id)
            ORDER BY name, quay_id
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new ModeMismatch(rs.getLong("quay_id"), rs.getString("name"),
            rs.getString("quay_mode"), rs.getLong("stop_id"), rs.getString("stop_mode"), rs.getBoolean("is_wrapper"),
            rs.getString("source_station")));
    }

    /**
     * Dubbele quays binnen één StopPlace (chb#22): zelfde source_stop_code, allebei levend. Ontstaat als een
     * curator een tweeling-quay verplaatst i.p.v. samenvoegt. Hoort leeg te zijn. Alleen De Lijn (en een
     * handvol andere bronnen) levert een haltenummer; naam + afstand is geen bruikbaar criterium (TEC-palen
     * aan weerszijden en NMBS-perrons liggen even dicht bij elkaar). keepId = het oudste id.
     */
    public List<DuplicateQuay> findDuplicateQuays() {
        String sql = """
            SELECT DISTINCT q2.id AS quay_id, q1.id AS keep_id, q1.stop_id, q1.name, r1.source_stop_code
            FROM chb.quay q1
            JOIN chb.quay q2 ON q2.stop_id = q1.stop_id AND q2.id > q1.id
            JOIN chb.raw_stop r1 ON r1.stop_id = q1.id
            JOIN chb.raw_stop r2 ON r2.stop_id = q2.id AND r2.source_stop_code = r1.source_stop_code
            WHERE q1.merged_into_id IS NULL AND q2.merged_into_id IS NULL
              AND q1.disabled_at IS NULL AND q2.disabled_at IS NULL
            ORDER BY q1.name, q2.id
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new DuplicateQuay(rs.getLong("quay_id"), rs.getLong("keep_id"),
            rs.getLong("stop_id"), rs.getString("name"), rs.getString("source_stop_code")));
    }

    /**
     * Brusselse StopPlace-namen (chb#24), naar ndov contrib/brussel-namen-overzicht.sql. Regio, default_name,
     * name_reason en disagree_langs komen uit de namencache: stop_names lezen kost ~10-25 s en filtert niet op id.
     * quay_names filtert wel op id, dus de rest beperkt zich tot de Brusselse id's (~1-2 s per deel).
     */
    public BrusselsNames findBrusselsNames() {
        Map<Long, StopNameCache.Names> stops = names.stopsInRegion("bru");
        Map<Long, StopNameCache.Names> quays = names.quaysInRegion("bru");
        if (stops.isEmpty() || quays.isEmpty()) {
            // Lege cache (opstart): een leeg rapport zou "in orde" melden.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Namencache nog niet geladen");
        }
        Long[] stopIds = stops.keySet().toArray(Long[]::new);
        List<BrusselsNames.StopPlace> stopPlaces = new ArrayList<>();
        jdbcTemplate.query("""
            WITH live_stop AS (
                SELECT s.* FROM chb.stop s
                WHERE s.merged_into_id IS NULL AND s.disabled_at IS NULL
                  AND NOT EXISTS (SELECT 1 FROM chb.stop_attribute a WHERE a.stop_id = s.id AND a.key = 'deleted_at')
            ),
            -- elke live quay onder zijn monomodal én (indien aanwezig) de general
            stop_quay AS (
                SELECT s.id AS stop_id, q.id AS quay_id FROM live_stop s JOIN chb.quay q ON q.stop_id = s.id
                WHERE s.id = ANY(?) AND q.merged_into_id IS NULL AND q.disabled_at IS NULL
                UNION ALL
                SELECT s.parent_id, q.id FROM live_stop s JOIN chb.quay q ON q.stop_id = s.id
                WHERE s.parent_id = ANY(?) AND q.merged_into_id IS NULL AND q.disabled_at IS NULL
            ),
            per_stop AS (
                SELECT sq.stop_id,
                       bool_or(r.source_schema = 'mivbstib') AND NOT bool_or(r.source_schema IN ('nmbssncb', 'bmc_nmbssncb')) AS expect_mivb,
                       array_agg(DISTINCT r.source_schema ORDER BY r.source_schema) AS sources,
                       array_agg(DISTINCT qn.names ->> 'fr') FILTER (WHERE r.source_schema = 'mivbstib') AS mivb_fr,
                       array_agg(DISTINCT qn.names ->> 'nl') FILTER (WHERE r.source_schema = 'mivbstib') AS mivb_nl
                FROM stop_quay sq
                JOIN chb.raw_stop r ON r.stop_id = sq.quay_id
                JOIN chb.quay_names qn ON qn.quay_id = sq.quay_id AND qn.quay_id = ANY(ARRAY(SELECT quay_id FROM stop_quay))
                GROUP BY sq.stop_id
            )
            SELECT s.id, s.kind, s.transport_mode, p.sources, p.expect_mivb, p.mivb_fr, p.mivb_nl,
                   (SELECT a.value FROM chb.stop_attribute a WHERE a.stop_id = s.id AND a.key = 'name_fr') AS cur_fr,
                   (SELECT a.value FROM chb.stop_attribute a WHERE a.stop_id = s.id AND a.key = 'name_nl') AS cur_nl
            FROM live_stop s JOIN per_stop p ON p.stop_id = s.id
            WHERE s.id = ANY(?)
            """, rs -> {
            StopNameCache.Names n = stops.get(rs.getLong("id"));
            List<String> sources = textArray(rs, "sources");
            // Arrays.asList: een MIVB-quay zonder fr/nl geeft een null-element.
            List<String> fr = rs.getArray("mivb_fr") == null ? List.of() : Arrays.asList((String[]) rs.getArray("mivb_fr").getArray());
            List<String> nl = rs.getArray("mivb_nl") == null ? List.of() : Arrays.asList((String[]) rs.getArray("mivb_nl").getArray());
            String mivbFr = null;
            String mivbNl = null;
            String expected = null;
            if (rs.getBoolean("expect_mivb")) {
                if (fr.size() == 1 && nl.size() == 1 && fr.get(0) != null && nl.get(0) != null) {
                    mivbFr = fr.get(0);
                    mivbNl = nl.get(0);
                    expected = mivbFr.equals(mivbNl) ? mivbFr : mivbFr + " / " + mivbNl;
                } else {
                    // MIVB is het oneens met zichzelf: geen verwachting om over te nemen, wel tonen.
                    List<String> all = new ArrayList<>(fr);
                    all.addAll(nl);
                    expected = "(" + all.stream().map(String::valueOf).collect(Collectors.joining(" | ")) + ")";
                }
            }
            String reason = n.nameReason();
            boolean blocking = !n.disagreeLangs().isEmpty() || "bru_disagree".equals(reason);
            // niet getoond: general_main_mode_child (opgelost via V156) en Eurostar-only (besluit: zo laten)
            boolean otherReason = reason != null && !reason.equals("general_main_mode_child") && !sources.equals(List.of("eurostar"));
            if (blocking || otherReason || (expected != null && !expected.equals(n.defaultName()))) {
                stopPlaces.add(new BrusselsNames.StopPlace(rs.getLong("id"), blocking, rs.getString("kind"),
                    rs.getString("transport_mode"), n.defaultName(), expected, mivbFr, mivbNl, reason, n.disagreeLangs(),
                    rs.getString("cur_fr"), rs.getString("cur_nl"), sources));
            }
        }, stopIds, stopIds, stopIds);
        stopPlaces.sort(Comparator.comparing((BrusselsNames.StopPlace s) -> !s.blocking())
            .thenComparing(BrusselsNames.StopPlace::kind)
            .thenComparing(BrusselsNames.StopPlace::defaultName, Comparator.nullsLast(Comparator.naturalOrder())));

        List<BrusselsNames.Pair> pairs = jdbcTemplate.query("""
            WITH live_quay AS (
                SELECT q.id, q.geom, q.transport_mode, q.stop_id, qn.names,
                       (SELECT string_agg(DISTINCT r.source_schema, ',' ORDER BY r.source_schema) FROM chb.raw_stop r WHERE r.stop_id = q.id) AS source,
                       -- vooraf: een regex per paar in de self-join kost seconden
                       EXISTS (SELECT 1 FROM chb.raw_stop r WHERE r.stop_id = q.id AND r.source_schema ~ '(delijn|tec)') AS is_other
                FROM chb.quay q
                JOIN chb.stop s ON s.id = q.stop_id
                JOIN chb.quay_names qn ON qn.quay_id = q.id AND qn.quay_id = ANY(?)
                WHERE q.id = ANY(?) AND q.merged_into_id IS NULL AND q.disabled_at IS NULL AND s.merged_into_id IS NULL
            )
            SELECT m.id AS mivb_quay, m.names ->> 'fr' AS mivb_fr, m.names ->> 'nl' AS mivb_nl, m.transport_mode,
                   o.id AS other_quay, o.source AS other_source, o.names ->> 'fr' AS other_fr, o.names ->> 'nl' AS other_nl,
                   round(st_distance(m.geom::geography, o.geom::geography))::int AS distance_m
            FROM live_quay m
            JOIN live_quay o ON o.transport_mode = m.transport_mode AND o.stop_id <> m.stop_id
             AND m.geom && st_expand(o.geom, 0.0005) AND st_dwithin(m.geom::geography, o.geom::geography, 30)
            WHERE m.source = 'mivbstib' AND o.is_other
              -- alleen een taal die beide leveren kan botsen (De Lijn levert geen fr)
              AND ((o.names ->> 'nl' IS NOT NULL AND o.names ->> 'nl' IS DISTINCT FROM m.names ->> 'nl')
                OR (o.names ->> 'fr' IS NOT NULL AND o.names ->> 'fr' IS DISTINCT FROM m.names ->> 'fr'))
            ORDER BY m.names ->> 'fr', m.id, distance_m
            """,
            (rs, rowNum) -> new BrusselsNames.Pair(rs.getLong("mivb_quay"), rs.getString("mivb_fr"), rs.getString("mivb_nl"),
                rs.getString("transport_mode"), rs.getLong("other_quay"), rs.getString("other_source"),
                rs.getString("other_fr"), rs.getString("other_nl"), rs.getInt("distance_m")),
            (Object) quays.keySet().toArray(Long[]::new), (Object) quays.keySet().toArray(Long[]::new));
        return new BrusselsNames(stopPlaces, pairs);
    }

    /** Voorstellen uit ndov V143 (#67). Geen paginering: ~750 rijen in ~0,2 s; de lijst filtert client-side. */
    public List<GroupCandidate> findGroupCandidates() {
        return jdbcTemplate.query("SELECT name, transport_mode, quay_ids, max_distance_m FROM chb.stop_group_candidate ORDER BY name, transport_mode, quay_ids",
            (rs, rowNum) -> new GroupCandidate(rs.getString("name"), rs.getString("transport_mode"),
                List.of((Long[]) rs.getArray("quay_ids").getArray()), rs.getLong("max_distance_m")));
    }

    /** Faalt als de chb_viewer-pool geen verbinding krijgt; voor {@code /api/health}. */
    public void ping() {
        jdbcTemplate.queryForObject("SELECT 1", Integer.class);
    }

    public boolean exists(long id) {
        Boolean result = jdbcTemplate.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM chb.stop WHERE id = ?) OR EXISTS(SELECT 1 FROM chb.quay WHERE id = ?)", Boolean.class, id, id);
        return Boolean.TRUE.equals(result);
    }
}
