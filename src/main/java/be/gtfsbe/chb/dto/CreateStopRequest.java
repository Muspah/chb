package be.gtfsbe.chb.dto;

/**
 * Nieuwe StopPlace (chb.create_stop_place; de viewer maakt nooit een quay). {@code stopType} is
 * verplicht (chb.stop.stop_type is NOT NULL voor levende StopPlaces).
 *
 * <p>{@code allowDuplicateName} is de bevestiging van de curator dat een reeds bestaande
 * groeperingsknoop met dezelfde naam geen vergissing is — zonder die vlag weigert de database
 * met UC001 (HTTP 409), zodat de frontend een annuleren-of-toch-doorgaan-keuze kan tonen.
 */
public record CreateStopRequest(String name, String stopType, Long parentId, Double lat, Double lon,
                                boolean allowDuplicateName) {
}
