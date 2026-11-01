package be.gtfsbe.chb.dto;

/** Rij uit chb.wrapper_review_mismatch (ndov V142): reviewed_at van wrapper en quay verschilt. */
public record ReviewMismatch(long stopId, String name, long quayId, String stopReviewedAt, String quayReviewedAt) {
}
