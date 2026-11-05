package be.gtfsbe.chb.dto;

/** {@code sourceStopCode}: haltenummer uit de bron, null als de bron er geen heeft (TEC, NMBS). */
public record StopSource(String sourceSchema, String sourceId, String sourceName, String sourceStopCode) {
}
