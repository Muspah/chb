package be.gtfsbe.chb.dto;

/**
 * Vervangt (geen JSON-merge-patch) de vier bewerkbare velden via {@code chb.update_stop}, dat op niveau dispatcht
 * (StopPlace of quay; voor een quay is {@code parentId} de StopPlace waaronder hij hangt en betekent {@code stopType = null}
 * "geen wijziging"). De frontend
 * vult het formulier voor met de actuele waarden en stuurt bij opslaan altijd alle vier terug —
 * {@code parentId = null} betekent dus expliciet "loskoppelen" (build-instruction.md §3.2), niet
 * "onveranderd laten".
 */
public record UpdateStopRequest(String name, String stopType, String publicCode, Long parentId) {
}
