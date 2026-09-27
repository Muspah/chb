package be.gtfsbe.chb.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record StopDetail(
    long id,
    String name,
    String stopType,
    String publicCode,
    Double lat,
    Double lon,
    boolean isSynthetic,
    boolean isWrapper,
    boolean isQuay,
    Long parentId,
    String path,
    Map<String, String> attributes,
    List<StopSource> sources,
    List<StopChildSummary> children,
    String reviewedAt,
    String deletedAt,
    OffsetDateTime disabledAt,
    OffsetDateTime firstSeenAt,
    OffsetDateTime lastSeenAt
) {
}
