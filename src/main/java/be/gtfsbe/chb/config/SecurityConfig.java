package be.gtfsbe.chb.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Gemengde publieke/beveiligde opzet (build-instruction.md §4): de kaart en haltedetails zijn
 * voor iedereen leesbaar zonder login, de audit-geschiedenis vereist enkel een Office365/Entra
 * ID-sessie (elke ingelogde gebruiker, ook een B2B-gast), en de schrijvende endpoints vereisen
 * daarnaast de Entra ID app role {@value #CURATOR_APP_ROLE} (zie {@link #oidcUserService()}).
 *
 * <p>Deze app role wordt niet via een security group toegekend (group-based app role assignment
 * vereist Entra ID P1), maar per individuele gebruiker in het Enterprise Application-blad —
 * werkt identiek voor interne gebruikers en voor B2B-gastgebruikers, en blijft binnen de gratis
 * laag. Zie het antwoord in de conversatie voor de exacte Azure Portal-stappen.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Moet exact overeenkomen met het "Value"-veld van de app role in de Entra ID
     * app-registratie (App registrations → App roles).
     */
    private static final String CURATOR_APP_ROLE = "Curator";
    public static final String CURATOR_AUTHORITY = "ROLE_" + CURATOR_APP_ROLE.toUpperCase(Locale.ROOT);

    private static final Duration MAX_LOGIN_AGE = Duration.ofDays(7);

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        RequestMatcher apiMatcher = PathPatternRequestMatcher.pathPattern("/api/**");

        http
            // Login landt altijd op "/" (defaultSuccessUrl alwaysUse=true), dus een opgeslagen request is
            // nutteloos -- en het maakte voor elke anonieme hit op een beveiligd pad (crawler: /sitemap.xml)
            // een 72u-durende sessie-rij in chb.spring_session aan.
            .requestCache(rc -> rc.requestCache(new NullRequestCache()))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET,
                    "/", "/index.html", "/app.js", "/i18n.js", "/style.css", "/favicon.ico", "/robots.txt", "/static/**",
                    "/api/stops", "/api/stops/*", "/api/search", "/api/stop-place-types", "/api/health").permitAll()
                .requestMatchers("/api/me").permitAll()
                .requestMatchers("/oauth2/**", "/login/**", "/error").permitAll()
                // Enkel bekijken (geschiedenis): elke ingelogde gebruiker, geen curator-rol nodig.
                .requestMatchers(HttpMethod.GET, "/api/stops/*/history").authenticated()
                // Doorlichtingsrapport: enkel curatoren, dit is puur een werklijst voor hen.
                .requestMatchers(HttpMethod.GET, "/api/review/**").hasAuthority(CURATOR_AUTHORITY)
                // De rest van /api/**, dus alles wat schrijft, ook een nieuwe route: enkel de Curator-app role.
                .requestMatchers(apiMatcher).hasAuthority(CURATOR_AUTHORITY)
                .anyRequest().authenticated())
            // Spring Security 7's kant-en-klare SPA-preset: CookieCsrfTokenRepository
            // (XSRF-TOKEN-cookie, leesbaar door JS) + SpaCsrfTokenRequestHandler (accepteert het
            // ruwe cookie-token terug als X-XSRF-TOKEN-header, zoals onze vanilla-JS-frontend
            // stuurt).
            .csrf(CsrfConfigurer::spa)
            .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
            .addFilterAfter(new MaxSessionAgeFilter(), SecurityContextHolderFilter.class)
            // style-src-attr: de markers en enkele tabelrijen hebben een inline style-attribuut.
            .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                "default-src 'self'; script-src 'self' https://unpkg.com; style-src 'self' https://unpkg.com; "
                    + "style-src-attr 'unsafe-inline'; img-src 'self' data: https://unpkg.com https://*.tile.openstreetmap.org; "
                    + "object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'")))
            .oauth2Login(oauth2 -> oauth2
                // alwaysUse=true: zonder dit wint Spring Security's RequestCache na login van
                // deze default zodra er een "opgeslagen request" bestaat — en die wordt ook
                // opgeslagen voor een niet-geauthenticeerde /api/**-aanroep die zelf via
                // exceptionHandling hierboven al netjes met een JSON-401 wordt afgehandeld (bv.
                // de curator-only /api/review/unreviewed-map-toggle terwijl de sessie net
                // verlopen was). Resultaat: na inloggen kon je op zo'n API-endpoint (JSON, geen
                // kaart) belanden i.p.v. terug op de SPA. Dit is een single-page app zonder
                // deep-linkbare server-routes buiten "/" — er is dus nooit een geldige reden om
                // ergens anders dan "/" naartoe te gaan na login (kaartpositie/filters zitten al
                // in de URL-hash/localStorage, zie ontwerpbeslissing 5 in Claude/CLAUDE.md).
                .defaultSuccessUrl("/", true)
                .userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcUserService())))
            .logout(logout -> logout.logoutSuccessUrl("/"))
            // GET /api/stops/{id}/history en alle schrijvende /api/**-calls die niet aan de
            // vereiste hierboven voldoen, komen hier terecht: JSON 401 (niet ingelogd) of 403
            // (ingelogd maar geen Curator) i.p.v. een redirect naar de Entra ID-loginpagina
            // (§8: "geen data-lek, geen 500"). Access-denied (403) gebruikt Spring Security's
            // eigen default handler, die voor een geauthenticeerde request al gewoon een 403
            // zonder redirect teruggeeft.
            .exceptionHandling(ex -> ex
                .defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), apiMatcher));

        return http.build();
    }

    /**
     * Microsoft Entra ID zet toegewezen app roles in de {@code roles}-claim van het ID-token
     * (geen extra Graph-call nodig, werkt op de gratis laag). Deze wrapper vertaalt die claim
     * naar Spring Security {@link GrantedAuthority}'s met een {@code ROLE_}-prefix, zodat
     * {@code hasAuthority("ROLE_CURATOR")} hierboven werkt.
     */
    @Bean
    public OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService() {
        OidcUserService delegate = new OidcUserService();
        return userRequest -> {
            OidcUser oidcUser = delegate.loadUser(userRequest);
            Set<GrantedAuthority> authorities = new LinkedHashSet<>(oidcUser.getAuthorities());
            List<String> roles = oidcUser.getIdToken().getClaimAsStringList("roles");
            if (roles != null) {
                roles.forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase(Locale.ROOT))));
            }
            return new DefaultOidcUser(authorities, oidcUser.getIdToken(), oidcUser.getUserInfo());
        };
    }

    /**
     * Rollen worden alleen bij het inloggen gelezen en de sessie schuift bij elke request door, dus
     * zonder bovengrens blijft een ingetrokken Curator-rol werken zolang de sessie in gebruik is.
     * Meet vanaf de {@code iat} van het ID-token, niet vanaf de aanmaaktijd van de sessie: de
     * OAuth2-flow hergebruikt een bestaande anonieme sessie, die al veel ouder kan zijn dan de login.
     * Het request gaat daarna anoniem verder (publiek blijft werken, de rest geeft 401).
     */
    static final class MaxSessionAgeFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                throws ServletException, IOException {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof OidcUser user
                    && user.getIdToken().getIssuedAt().plus(MAX_LOGIN_AGE).isBefore(Instant.now())) {
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
                SecurityContextHolder.clearContext();
            }
            filterChain.doFilter(request, response);
        }
    }

    /**
     * Forceert het aanmaken van het CSRF-token (en dus het zetten van de XSRF-TOKEN-cookie) op
     * elke request. Nodig omdat {@link CsrfToken} sinds Spring Security 5.8 lazy is: zonder dit
     * filter verschijnt de cookie pas nadat er ergens al naar het token gevraagd is. Standaard
     * aanbevolen aanpak voor een SPA/vanilla-JS-frontend die het token uit de cookie leest en als
     * X-XSRF-TOKEN-header meestuurt.
     */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                throws ServletException, IOException {
            CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (csrfToken != null) {
                csrfToken.getToken();
            }
            filterChain.doFilter(request, response);
        }
    }
}
