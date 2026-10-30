package be.gtfsbe.chb.dto;

/**
 * Een live TEC-quay waarvoor chb.tec_city_candidates() (ndov V115/V136) geen plaats kon afleiden en die nog geen
 * 'city'-attribuut heeft. {@code reason} is een code (zie StopViewerRepository#findTecCityIssues), {@code suggestion}
 * een voorstel om het invulveld mee te vullen (kan null zijn), {@code municipality} de gemeente uit _resolved_meta.
 */
public record TecCityIssue(long quayId, String name, String reason, String suggestion, String municipality) {
}
