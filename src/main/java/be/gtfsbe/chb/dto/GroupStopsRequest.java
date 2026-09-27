package be.gtfsbe.chb.dto;

import java.util.List;

/**
 * "Snel groeperen" via {@code chb.group_stops} (één transactie): {@code stopIds} komen onder een
 * nieuwe StopPlace — quays onder een nieuwe monomodale StopPlace, monomodale StopPlaces onder een
 * nieuwe general (mengen kan niet). {@code stopType} is verplicht. {@code lat}/{@code lon} zijn
 * optioneel: bij ontbreken berekent de database het zwaartepunt van de gekozen haltes, zodat de
 * nieuwe groep meteen als één punt op de kaart verschijnt.
 *
 * <p>{@code allowDuplicateName} is de bevestiging van de curator dat een reeds bestaande
 * groeperingsknoop met dezelfde naam geen vergissing is — zonder die vlag weigert de database
 * met UC001 (HTTP 409), zodat de frontend een annuleren-of-toch-doorgaan-keuze kan tonen.
 */
public record GroupStopsRequest(List<Long> stopIds, String name, String stopType, Double lat, Double lon,
                                boolean allowDuplicateName) {
}
