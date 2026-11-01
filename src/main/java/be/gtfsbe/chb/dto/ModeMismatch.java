package be.gtfsbe.chb.dto;

/**
 * Rij uit chb.stop_place_mode_mismatch (ndov V149) en/of chb.source_station_mode_mismatch (V144).
 * quayMode en stopMode kunnen null zijn; sourceStation is alleen gezet bij een automatische bronstationgroep.
 */
public record ModeMismatch(long quayId, String name, String quayMode, long stopId, String stopMode, boolean isWrapper,
                           String sourceStation) {
}
