package be.gtfsbe.chb.repository;

import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * De volledige kaartset ({@link StopViewerRepository#findMapRows}) als kant-en-klare JSON per taal; de
 * filters (doorlichtingskaart, uitgeschakelde tonen) past de kaart zelf toe. Opbouwen kost een volle tabelscan (>1 s), dus pas opnieuw
 * na een curator-schrijfactie ({@link #invalidate()}) of een nieuwe namenversie: de {@link StopNameCache}
 * ververst elke 5 minuten, zo komen ook imports binnen. De ETag is een hash van de inhoud, dus een
 * herbouw zonder echte wijziging geeft de browser gewoon een 304.
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
    private final Map<String, Entry> entries = new HashMap<>();

    public StopMapCache(StopViewerRepository repository, StopNameCache names, JsonMapper jsonMapper) {
        this.repository = repository;
        this.names = names;
        this.jsonMapper = jsonMapper;
    }

    /** Na elke curator-schrijfactie (EditorTransactionRunner), ná de commit. */
    public void invalidate() {
        edits.incrementAndGet();
    }

    // ponytail: één slot voor alle talen; per taal locken als een opbouw anderen merkbaar ophoudt.
    public synchronized Snapshot get(String lang) {
        String key = LANGS.contains(lang) ? lang : "";
        // Versies vóór het lezen vastleggen: een wijziging tijdens de opbouw maakt deze entry meteen weer oud.
        long namesVersion = names.version();
        long edited = edits.get();
        Entry entry = entries.get(key);
        if (entry != null && entry.namesVersion() == namesVersion && entry.edits() == edited) {
            return entry.snapshot();
        }
        byte[] json = jsonMapper.writeValueAsBytes(Map.of("stops", repository.findMapRows(key)));
        // Zwak: Tomcat comprimeert geen antwoord met een sterke ETag (de gzip-bytes zijn niet dezelfde).
        Snapshot snapshot = new Snapshot(json, "W/\"" + DigestUtils.md5DigestAsHex(json) + "\"");
        entries.put(key, new Entry(snapshot, namesVersion, edited));
        return snapshot;
    }
}
