package be.gtfsbe.chb.dto;

/**
 * {@code sourceStopCode}: haltenummer uit de bron, null als de bron er geen heeft (TEC, NMBS).
 * {@code uic}: UIC van 7 cijfers als dit een rail-quay is (zelfde regel als chb.rail_uic_quay, ndov V185), anders null.
 */
public record StopSource(String sourceSchema, String sourceId, String sourceName, String sourceStopCode, String uic) {
}
