package be.gtfsbe.chb.dto;

/** Twee levende quays in dezelfde StopPlace met hetzelfde haltenummer (chb#22); voorstel: quayId samenvoegen in keepId. */
public record DuplicateQuay(long quayId, long keepId, long stopId, String name, String sourceStopCode) {
}
