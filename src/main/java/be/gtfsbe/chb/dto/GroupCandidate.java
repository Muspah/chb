package be.gtfsbe.chb.dto;

import java.util.List;

/** Groepeervoorstel uit chb.stop_group_candidate (ndov V143, #67): zelfde bronnaam en modus, keten binnen 150 m. */
public record GroupCandidate(String name, String transportMode, List<Long> quayIds, long maxDistanceM) {
}
