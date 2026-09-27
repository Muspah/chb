package be.gtfsbe.chb.dto;

/**
 * {@code sourceCodes} is een kommagescheiden lijst van {@code bronschema:broncode}
 * (bv. {@code sncbnmbs:8893013_2}), leeg voor een synthetische groeperingsknoop zonder brondata.
 */
public record StopSearchResult(long id, String path, String sourceCodes) {
}
