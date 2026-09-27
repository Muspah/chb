package be.gtfsbe.chb.dto;

public record MeResponse(boolean authenticated, String name, String email, boolean curator) {

    public static MeResponse anonymous() {
        return new MeResponse(false, null, null, false);
    }
}
