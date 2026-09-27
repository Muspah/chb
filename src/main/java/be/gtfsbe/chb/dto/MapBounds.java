package be.gtfsbe.chb.dto;

/** Kaartviewport in WGS84-graden, gebruikt om {@code chb.stop} te filteren op de PostGIS-kolom
 *  {@code geom} (zie db/add_stop_geom_postgis.sql) i.p.v. steeds de volledige tabel te laden. */
public record MapBounds(double minLon, double minLat, double maxLon, double maxLat) {
}
