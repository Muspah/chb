package be.gtfsbe.chb.web;

import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * Enige plek die de OIDC-claim naar de string omzet die als {@code app.current_user} in de audit
 * trail terechtkomt (build-instruction.md §2, §4) — gebruikt door zowel {@link AuthController} als
 * {@link StopEditController} zodat "wie de gebruiker is" op precies één manier bepaald wordt.
 */
final class CurrentUser {

    private CurrentUser() {
    }

    static String email(OidcUser user) {
        String email = user.getEmail();
        return email != null ? email : user.getPreferredUsername();
    }
}
