package be.gtfsbe.chb.dto;

import java.util.Map;

public record GeoJsonFeature(String type, GeoJsonGeometry geometry, Map<String, Object> properties) {

    public GeoJsonFeature(GeoJsonGeometry geometry, Map<String, Object> properties) {
        this("Feature", geometry, properties);
    }
}
