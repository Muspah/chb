package be.gtfsbe.chb.dto;

/** {@code commit}/{@code run}: de gedeployde build (git short hash, GitHub Actions run-id), of null. */
public record MeResponse(boolean authenticated, String name, String email, boolean curator, String commit, String run) {

    public static MeResponse anonymous(String commit, String run) {
        return new MeResponse(false, null, null, false, commit, run);
    }
}
