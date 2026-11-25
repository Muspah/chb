package be.gtfsbe.chb.dto;

import java.time.OffsetDateTime;

/**
 * chb.identifier_worklist (ndov V193, chb#31): een id die een importer nodig heeft en nog niemand
 * gekoppeld heeft. suggested*: dichtstbijzijnde StopPlace (of Quay), met zijn soort zodat een
 * suggestie van het verkeerde type (bushalte voor een station) opvalt.
 */
public record IdentifierWorklistItem(String scheme, String value, String source, String name, Double lat, Double lon,
                                     OffsetDateTime lastRequestedAt, Long suggestedId, String suggestedName,
                                     String suggestedStopType, String suggestedTransportMode, Boolean suggestedIsWrapper,
                                     Long suggestedDistanceM) {
}
