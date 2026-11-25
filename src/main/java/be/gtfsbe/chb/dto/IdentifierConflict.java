package be.gtfsbe.chb.dto;

import java.util.List;

/** chb.identifier_conflict (ndov V193): één id die naar meer dan één object wijst. Hoort leeg te zijn. */
public record IdentifierConflict(String scheme, String value, List<Long> objectIds, List<String> origins) {
}
