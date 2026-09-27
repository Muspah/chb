package be.gtfsbe.chb.dto;

/** Wrapper promoveren tot curated StopPlace: de eigen naam is verplicht (chb.promote_wrapper). */
public record PromoteWrapperRequest(String name) {
}
