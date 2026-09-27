package be.gtfsbe.chb.dto;

import java.util.List;

public record GeoJsonFeatureCollection(String type, List<GeoJsonFeature> features, boolean truncated) {

    public GeoJsonFeatureCollection(List<GeoJsonFeature> features, boolean truncated) {
        this("FeatureCollection", features, truncated);
    }
}
