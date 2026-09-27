package be.gtfsbe.chb.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Telt door de database geweigerde curator-acties per SQLSTATE (UC00x, 23514 = hiërarchie-guard,
 * 55P03/40P01 = lock/deadlock). De database kan dit zelf niet: een geweigerde transactie rolt haar
 * eigen teller mee terug. Sinds de start van deze JVM; elke weigering staat ook als WARN in het log
 * (dat blijft over herstarts heen bestaan). Zichtbaar via GET /api/review/rejections.
 */
@Component
public class RejectionCounter {

    private static final Logger log = LoggerFactory.getLogger(RejectionCounter.class);
    private final Map<String, LongAdder> counts = new ConcurrentHashMap<>();

    public void record(String sqlState, String user, String message) {
        counts.computeIfAbsent(sqlState, k -> new LongAdder()).increment();
        log.warn("curator-actie geweigerd: sqlstate={} user={} melding={}", sqlState, user, message);
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> out = new TreeMap<>();
        counts.forEach((k, v) -> out.put(k, v.sum()));
        return out;
    }
}
