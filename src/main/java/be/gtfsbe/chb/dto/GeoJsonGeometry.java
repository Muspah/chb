package be.gtfsbe.chb.dto;

/** coordinates: {@code double[]} voor een Point, {@code double[][][]} (ringen) voor een Polygon. */
public record GeoJsonGeometry(String type, Object coordinates) {

    public static GeoJsonGeometry point(double lon, double lat) {
        return new GeoJsonGeometry("Point", new double[] {lon, lat});
    }

    public static GeoJsonGeometry polygon(double[][][] rings) {
        return new GeoJsonGeometry("Polygon", rings);
    }
}
