package be.gtfsbe.chb.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Namen per taal uit {@code chb.quay_names} / {@code chb.stop_names} (ndov V132/V137), in het geheugen.
 * Eén volledige lezing kost 5-9 s en filteren op id versnelt {@code stop_names} niet (gematerialiseerde CTE's),
 * dus per request lezen kan niet. Ververst elke 5 minuten (import) en kort na elke curator-schrijfactie
 * ({@link #refreshSoon()}). Quay- en StopPlace-id's zijn disjunct, dus één lookup per id volstaat.
 * Zolang de cache leeg is (opstart, of de views ontbreken) valt alles terug op de kale naam.
 */
@Component
public class StopNameCache {

    /** {@code disagreeLangs} alleen voor StopPlaces (quay_names heeft geen botsing), anders leeg. */
    public record Names(Map<String, String> names, String defaultName, String defaultLang, String nameReason,
                        String region, List<String> disagreeLangs) {
    }

    private static final Logger log = LoggerFactory.getLogger(StopNameCache.class);

    // names jsonb als "taal=naam"-array: geen JSON-parser nodig; een taalcode bevat nooit '='.
    private static final String SQL = """
        SELECT %s AS id, ARRAY(SELECT key || '=' || value FROM jsonb_each_text(names) ORDER BY key) AS names,
               default_name, default_lang, name_reason, region, %s AS disagree_langs
        FROM %s
        """;

    private final JdbcTemplate jdbcTemplate;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "stop-name-cache");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean pending = new AtomicBoolean();
    private volatile Map<Long, Names> stops = Map.of();
    private volatile Map<Long, Names> quays = Map.of();

    public StopNameCache(JdbcTemplate viewerJdbcTemplate) {
        this.jdbcTemplate = viewerJdbcTemplate;
        executor.scheduleWithFixedDelay(this::refresh, 0, 5, TimeUnit.MINUTES);
    }

    /** Na een curator-wijziging: één extra verversing, samengevoegd als er al één wacht. */
    public void refreshSoon() {
        if (pending.compareAndSet(false, true)) {
            executor.execute(() -> {
                pending.set(false);
                refresh();
            });
        }
    }

    /**
     * Curator zette {@code name_<taal>} op {@code id}: zet die naam meteen in de cache, zodat de wijziging niet
     * op de volledige verversing (5-10 s) hoeft te wachten. Per id herladen kan niet, {@code stop_names WHERE
     * stop_id = ?} kost evenveel als alles. Een curatornaam wint in beide views altijd voor zijn taal; de default
     * volgt alleen als het de taal van de default is. Reden, parent-general en verwijderen: {@link #refreshSoon()}.
     */
    public void putCuratedName(long id, String lang, String value) {
        if (stops.containsKey(id)) {
            stops = withName(stops, id, lang, value);
        } else if (quays.containsKey(id)) {
            quays = withName(quays, id, lang, value);
        }
    }

    // ponytail: kopie van de hele map (~70K items, enkele ms) per curatornaam; volstaat bij menselijk tempo.
    private static Map<Long, Names> withName(Map<Long, Names> map, long id, String lang, String value) {
        Names n = map.get(id);
        Map<String, String> names = new TreeMap<>(n.names());
        names.put(lang, value);
        boolean isDefault = lang.equals(n.defaultLang());
        Map<Long, Names> copy = new HashMap<>(map);
        copy.put(id, new Names(names, isDefault ? value : n.defaultName(), n.defaultLang(), n.nameReason(), n.region(),
            n.disagreeLangs()));
        return copy;
    }

    private void refresh() {
        try {
            long start = System.nanoTime();
            quays = load("quay_id", "chb.quay_names", "NULL::text[]");
            stops = load("stop_id", "chb.stop_names", "disagree_langs");
            log.info("Namencache ververst: {} StopPlaces, {} quays in {} ms", stops.size(), quays.size(),
                (System.nanoTime() - start) / 1_000_000);
        } catch (RuntimeException e) {
            log.warn("Namencache niet ververst, de vorige (of de kale naam) blijft gelden: {}", e.getMessage());
        }
    }

    private Map<Long, Names> load(String idColumn, String view, String disagreeColumn) {
        Map<Long, Names> result = new HashMap<>();
        jdbcTemplate.query(SQL.formatted(idColumn, disagreeColumn, view), rs -> {
            Map<String, String> names = new LinkedHashMap<>();
            java.sql.Array disagree = rs.getArray("disagree_langs");
            for (String pair : (String[]) rs.getArray("names").getArray()) {
                int eq = pair.indexOf('=');
                names.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
            result.put(rs.getLong("id"), new Names(names, rs.getString("default_name"), rs.getString("default_lang"),
                rs.getString("name_reason"), rs.getString("region"), disagree == null ? List.of() : List.of((String[]) disagree.getArray())));
        });
        return result;
    }

    public Names get(long id) {
        Names n = stops.get(id);
        return n != null ? n : quays.get(id);
    }

    /** Id's van StopPlaces ({@code stops}) of quays in {@code region}, uit de laatste verversing. */
    public Map<Long, Names> stopsInRegion(String region) {
        return inRegion(stops, region);
    }

    public Map<Long, Names> quaysInRegion(String region) {
        return inRegion(quays, region);
    }

    private static Map<Long, Names> inRegion(Map<Long, Names> map, String region) {
        return map.entrySet().stream().filter(e -> region.equals(e.getValue().region()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** Naam in de UI-taal als de bron die levert, anders de default van het taalgebied, anders de kale naam. */
    public String display(long id, String lang, String bareName) {
        Names n = get(id);
        if (n == null) {
            return bareName;
        }
        String inLang = n.names().get(lang);
        return inLang != null ? inLang : n.defaultName() != null ? n.defaultName() : bareName;
    }

    /** StopPlaces met {@code query} in een van hun namen (elke taal of de default); hoofdletterongevoelig. */
    public List<Long> stopIdsMatching(String query) {
        String q = query.toLowerCase(Locale.ROOT);
        // ponytail: lineaire scan over ~70K StopPlaces (enkele ms); een index pas als zoeken traag wordt.
        return stops.entrySet().stream()
            .filter(e -> matches(e.getValue(), q))
            .map(Map.Entry::getKey)
            .limit(10_000)
            .collect(Collectors.toList());
    }

    private static boolean matches(Names n, String q) {
        if (n.defaultName() != null && n.defaultName().toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        return n.names().values().stream().anyMatch(v -> v.toLowerCase(Locale.ROOT).contains(q));
    }
}
