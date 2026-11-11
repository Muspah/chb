package be.gtfsbe.chb.repository;

import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * De volledige kaartset ({@link StopViewerRepository#findMapRows}) als kant-en-klare JSON per taal; de
 * filters (doorlichtingskaart, uitgeschakelde tonen) past de kaart zelf toe. Opbouwen kost een volle tabelscan (~1-2 s),
 * dus pas opnieuw na een curator-schrijfactie ({@link #invalidate()}, synchroon) of een nieuwe namenversie: de
 * {@link StopNameCache} ververst elke 5 minuten, zo komen ook imports binnen (op de achtergrond, ondertussen de
 * vorige set). De ETag is een hash van de inhoud, dus een herbouw zonder echte wijziging geeft gewoon een 304.
 */
@Component
public class StopMapCache {

    public record Snapshot(byte[] json, String etag) {
    }

    private record Entry(Snapshot snapshot, long namesVersion, long edits) {
    }

    // De UI-talen (i18n.js); elke andere taal toont toch de defaultnaam, één variant volstaat.
    private static final Set<String> LANGS = Set.of("nl", "fr", "de", "en");

    private final StopViewerRepository repository;
    private final StopNameCache names;
    private final JsonMapper jsonMapper;
    private final AtomicLong edits = new AtomicLong();
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Set<String> rebuilding = ConcurrentHashMap.newKeySet();
    private final ExecutorService background = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "stop-map-cache");
        t.setDaemon(true);
        return t;
    });

    public StopMapCache(StopViewerRepository repository, StopNameCache names, JsonMapper jsonMapper) {
        this.repository = repository;
        this.names = names;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Na elke curator-schrijfactie (EditorTransactionRunner), ná de commit. De talen die al in de cache zaten,
     * meteen op de achtergrond opnieuw opbouwen: de kaart ververst eerst enkel de omgeving van de actie
     * (/api/stops/bbox) en haalt de volledige set pas later op, die ligt dan klaar.
     */
    public void invalidate() {
        edits.incrementAndGet();
        for (String key : entries.keySet()) {
            background.execute(() -> build(key));
        }
    }

    public Snapshot get(String lang) {
        String key = LANGS.contains(lang) ? lang : "";
        Entry entry = entries.get(key);
        if (entry != null && entry.edits() == edits.get()) {
            if (entry.namesVersion() != names.version() && rebuilding.add(key)) {
                // Enkel nieuwe namen (de 5-minutenverversing, dus ook imports): de vorige set nu, de nieuwe op de
                // achtergrond. Na een curatoractie (edits) wel wachten, de curator wil zijn wijziging zien.
                background.execute(() -> {
                    try {
                        build(key);
                    } finally {
                        rebuilding.remove(key);
                    }
                });
            }
            return entry.snapshot();
        }
        return build(key);
    }

    // ponytail: één slot voor alle opbouw; per taal locken als talen elkaar merkbaar ophouden.
    private synchronized Snapshot build(String key) {
        // Versies vóór het lezen vastleggen: een wijziging tijdens de opbouw maakt deze entry meteen weer oud.
        long namesVersion = names.version();
        long edited = edits.get();
        Entry entry = entries.get(key);
        if (entry != null && entry.namesVersion() == namesVersion && entry.edits() == edited) {
            return entry.snapshot(); // intussen al door een andere aanvraag opgebouwd
        }
        byte[] json = jsonMapper.writeValueAsBytes(Map.of("stops", repository.findMapRows(key)));
        // Zwak: Tomcat comprimeert geen antwoord met een sterke ETag (de gzip-bytes zijn niet dezelfde).
        Snapshot snapshot = new Snapshot(json, "W/\"" + DigestUtils.md5DigestAsHex(json) + "\"");
        entries.put(key, new Entry(snapshot, namesVersion, edited));
        return snapshot;
    }
}
