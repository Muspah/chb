package be.gtfsbe.chb.dto;

import java.util.List;

/**
 * Eén UIC uit chb.rail_uic_worklist (ndov V185, chb#29): rail-quays die nog in een wrapper staan.
 * {@code suggestedStopPlaceId}: de gecureerde StopPlace van dit UIC, null als er nog geen is.
 */
public record RailUicWorklistItem(String uic, String name, List<Long> quayIds, List<Long> wrapperIds, Long suggestedStopPlaceId) {
}
