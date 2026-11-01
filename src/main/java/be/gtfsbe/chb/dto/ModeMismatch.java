package be.gtfsbe.chb.dto;

/** Rij uit chb.stop_place_mode_mismatch (ndov V149). quayMode en stopMode kunnen null zijn. */
public record ModeMismatch(long quayId, String name, String quayMode, long stopId, String stopMode, boolean isWrapper) {
}
