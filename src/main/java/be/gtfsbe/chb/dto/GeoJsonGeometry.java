package be.gtfsbe.chb.dto;

public record GeoJsonGeometry(String type, double[] coordinates) {

    public static GeoJsonGeometry point(double lon, double lat) {
        return new GeoJsonGeometry("Point", new double[] {lon, lat});
    }
}
