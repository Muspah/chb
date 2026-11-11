package be.gtfsbe.chb.dto;

/** Kaartkader in WGS84-graden, voor de verversing rond een curatoractie (zie StopViewerRepository#findMapRows). */
public record MapBounds(double minLon, double minLat, double maxLon, double maxLat) {
}
