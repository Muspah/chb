package be.gtfsbe.chb.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record StopDetail(
    long id,
    /** Kale chb.stop.name/chb.quay.name: intern label, enkel voor curatoren bewerkbaar. */
    String name,
    /** Naam in de UI-taal, anders de default van het taalgebied (chb.quay_names/stop_names), anders {@code name}. */
    String displayName,
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
    OffsetDateTime lastSeenAt,
    /** general | monomodal | wrapper; null voor een quay. */
    String kind,
    String transportMode,
    List<String> otherTransportModes,
    /** {taal: naam}, alleen wat de bron of een curator levert. Leeg zolang de namencache niet geladen is. */
    Map<String, String> names,
    String defaultName,
    /** NULL = tweetalig (Brussel) of onbekend. */
    String defaultLang,
    String nameReason,
    /** Attribuut _source_station ({@code <schema>:<station>}): automatisch gegroepeerd uit het bronstation (ndov V143). */
    String sourceStation
) {
}
