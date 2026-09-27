package be.gtfsbe.chb.dto;

/**
 * Eén toegelaten waarde voor {@code chb.stop.stop_type}, uit de {@code chb.stop_place_type}-
 * lookup-tabel (import-repo, V12) — de volledige, geverifieerde NeTEx {@code StopTypeEnumeration}.
 * Voedt de dropdown in de webinterface; {@code code} is de waarde die effectief opgeslagen wordt.
 */
public record StopPlaceTypeOption(String code, String label) {
}
