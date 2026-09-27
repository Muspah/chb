package be.gtfsbe.chb.dto;

/** Quay-niveau voortgang plus de StopPlace-werkvoorraad; zie StopViewerRepository.reviewStats(). */
public record ReviewStats(long totalQuays, long grouped, long standaloneOk, long unreviewedStopPlaces) {
}
