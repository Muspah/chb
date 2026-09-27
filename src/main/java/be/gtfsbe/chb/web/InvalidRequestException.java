package be.gtfsbe.chb.web;

/**
 * Vertaalt een ongeldige aanvraag (validatiefout, of een door de database geweigerde wijziging
 * zoals de cirkel-preventie-trigger op herparenten) naar een schone 400 in plaats van een
 * onverklaarde 500 (build-instruction.md §3.2, §8).
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }

    public InvalidRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
