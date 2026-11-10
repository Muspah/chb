package be.gtfsbe.chb.dto;

import java.util.List;

/**
 * Rapport Brusselse StopPlace-namen (chb#24, Business Rules CHB §7, ndov contrib/brussel-namen-overzicht.sql).
 * {@code stopPlaces} hoort leeg te zijn; {@code pairs} is een werklijst ter info.
 */
public record BrusselsNames(List<StopPlace> stopPlaces, List<Pair> pairs) {

    /**
     * {@code blocking}: een taal valt weg (bru_disagree), de export faalt. Anders wijkt de naam af van §7.
     * {@code mivbFr}/{@code mivbNl}: de verwachte MIVB-namen, null als er geen (eenduidige) verwachting is.
     */
    public record StopPlace(long id, boolean blocking, String kind, String transportMode, String defaultName,
                            String expected, String mivbFr, String mivbNl, String nameReason,
                            List<String> disagreeLangs, String curatorFr, String curatorNl, List<String> sources) {
    }

    /** Nog niet gegroepeerde MIVB-quay met binnen 30 m een De Lijn-/TEC-quay van dezelfde modus en een andere naam. */
    public record Pair(long mivbQuay, String mivbFr, String mivbNl, String transportMode, long otherQuay,
                       String otherSource, String otherFr, String otherNl, int distanceM) {
    }
}
