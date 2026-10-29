package be.gtfsbe.chb.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** {@code isQuay}: het kind is een quay (chb.quay) i.p.v. een StopPlace; beide zijn via /api/stops/{id} te lezen. */
public record StopChildSummary(long id, String name, String publicCode, OffsetDateTime disabledAt, boolean isQuay,
                               String kind, String transportMode, List<String> otherTransportModes) {
}
