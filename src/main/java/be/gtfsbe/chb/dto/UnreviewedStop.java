package be.gtfsbe.chb.dto;

public record UnreviewedStop(long id, String name, String path, String stopType, boolean isSynthetic) {
}
