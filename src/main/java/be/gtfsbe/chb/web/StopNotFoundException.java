package be.gtfsbe.chb.web;

public class StopNotFoundException extends RuntimeException {

    public StopNotFoundException(long id) {
        super("Halte " + id + " bestaat niet");
    }
}
