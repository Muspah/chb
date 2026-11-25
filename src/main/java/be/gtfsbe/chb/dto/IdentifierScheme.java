package be.gtfsbe.chb.dto;

/** chb.identifier_scheme (ndov V193): level stop_place | quay, valueRegex = PostgreSQL-regex. */
public record IdentifierScheme(String scheme, String level, String valueRegex, String description) {
}
