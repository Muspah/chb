package be.gtfsbe.chb.dto;

import java.time.OffsetDateTime;

/**
 * Eén rij uit chb.identifier_resolved (ndov V193, chb#31). origin curated | derived; {@code id} is
 * null voor een afgeleide (uit een feed, alleen-lezen). Geen created_by: het detail is publiek, wie het
 * deed staat in de (afgeschermde) geschiedenis.
 */
public record StopIdentifier(Long id, String scheme, String value, String origin, String note, OffsetDateTime createdAt) {
}
