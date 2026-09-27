package be.gtfsbe.chb.dto;

import java.time.OffsetDateTime;

public record StopHistoryEntry(
    long id,
    Long rawStopId,
    String changedBy,
    OffsetDateTime changedAt,
    String changeType,
    String oldValue,
    String newValue,
    String note
) {
}
