package be.gtfsbe.chb.dto;

import java.util.List;

/** Rij uit chb.uic_stop_place_conflict (ndov V185, chb#29); hoort leeg te zijn. */
public record UicConflict(String kind, String uic, List<Long> stopPlaceIds, List<Long> quayIds) {
}
