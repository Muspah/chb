package be.gtfsbe.chb.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code isQuay}: het kind is een quay (chb.quay) i.p.v. een StopPlace; beide zijn via /api/stops/{id} te lezen.
 * {@code sourceStopCodes}: haltenummers uit de bronnen (raw_stop.source_stop_code), leeg voor een StopPlace;
 * twee levende quays met een gemeenschappelijk nummer zijn een dubbel (chb#22).
 */
public record StopChildSummary(long id, String name, String displayName, String publicCode, OffsetDateTime disabledAt, boolean isQuay,
                               String kind, String transportMode, List<String> otherTransportModes, List<String> sourceStopCodes) {
}
