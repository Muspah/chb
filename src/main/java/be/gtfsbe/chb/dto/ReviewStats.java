package be.gtfsbe.chb.dto;

import java.util.List;

/** Quay-niveau voortgang plus de StopPlace-werkvoorraad; zie StopViewerRepository.reviewStats(). */
public record ReviewStats(long totalQuays, long grouped, long standaloneOk, long unreviewedStopPlaces,
                          /** Hoort leeg te zijn; rijen betekenen dat iets buiten de triggers van V142 om is gegaan. */
                          List<ReviewMismatch> reviewMismatches) {
}
