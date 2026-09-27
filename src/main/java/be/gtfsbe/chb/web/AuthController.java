package be.gtfsbe.chb.web;

import be.gtfsbe.chb.config.SecurityConfig;
import be.gtfsbe.chb.dto.MeResponse;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publiek endpoint (build-instruction.md §4) — de frontend gebruikt dit om zonder redirect te
 * bepalen of de bewerkingsknoppen getoond moeten worden. {@code curator} laat de frontend ook het
 * onderscheid maken tussen "enkel ingelogd" (geschiedenis mag) en "mag ook schrijven" — zonder dat
 * onderscheid zou een ingelogde niet-curator bewerkformulieren zien die toch op een 403 stuiten.
 */
@RestController
public class AuthController {

    @GetMapping("/api/me")
    public MeResponse me(@AuthenticationPrincipal OidcUser user) {
        if (user == null) {
            return MeResponse.anonymous();
        }
        boolean curator = user.getAuthorities().stream()
            .anyMatch(authority -> SecurityConfig.CURATOR_AUTHORITY.equals(authority.getAuthority()));
        return new MeResponse(true, user.getFullName(), CurrentUser.email(user), curator);
    }
}
