package be.gtfsbe.chb.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Door de echte filterketen: een curator-pad geeft 404 (geen controller geladen) of 401 (uitgelogd). */
@SpringJUnitWebConfig(MaxSessionAgeFilterTest.Config.class)
class MaxSessionAgeFilterTest {

    @Configuration
    @EnableWebMvc
    @Import(SecurityConfig.class)
    static class Config {
        @Bean
        ClientRegistrationRepository clientRegistrationRepository() {
            return mock(ClientRegistrationRepository.class);
        }
    }

    @Autowired
    WebApplicationContext context;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private static MockHttpSession curatorSessionLoggedInAgo(Duration age) {
        Instant issuedAt = Instant.now().minus(age);
        OidcIdToken token = new OidcIdToken("t", issuedAt, issuedAt.plusSeconds(3600), Map.of("sub", "curator"));
        var authorities = List.of(new SimpleGrantedAuthority(SecurityConfig.CURATOR_AUTHORITY));
        var authentication = new OAuth2AuthenticationToken(new DefaultOidcUser(authorities, token), authorities, "azure");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(authentication));
        return session;
    }

    @Test
    void loginYoungerThanSevenDaysStaysCurator() throws Exception {
        MockHttpSession session = curatorSessionLoggedInAgo(Duration.ofDays(6));
        mvc().perform(get("/api/review/stats").session(session)).andExpect(status().isNotFound());
        assertFalse(session.isInvalid());
    }

    @Test
    void loginOlderThanSevenDaysIsLoggedOut() throws Exception {
        MockHttpSession session = curatorSessionLoggedInAgo(Duration.ofDays(8));
        mvc().perform(get("/api/review/stats").session(session)).andExpect(status().isUnauthorized());
        assertTrue(session.isInvalid());
    }

    @Test
    void publicPathStillWorksWithExpiredLogin() throws Exception {
        MockHttpSession session = curatorSessionLoggedInAgo(Duration.ofDays(8));
        mvc().perform(get("/api/stops").session(session)).andExpect(status().isNotFound());
        assertTrue(session.isInvalid());
    }
}
