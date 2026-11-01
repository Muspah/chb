package be.gtfsbe.chb.dto;

public record UnreviewedStop(long id, String name, String path, String stopType, boolean isSynthetic,
                             /** Automatisch uit een bronstation gegroepeerd (attribuut _source_station, ndov V143). */
                             boolean autoGroup) {
}
