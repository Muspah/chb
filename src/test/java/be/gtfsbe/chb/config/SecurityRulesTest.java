package be.gtfsbe.chb.config;

import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Rechten per soort route. Geen controllers geladen: 404 betekent dat de filterketen het request doorliet. */
@SpringJUnitWebConfig(SecurityRulesTest.Config.class)
class SecurityRulesTest {

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

    @ParameterizedTest(name = "{0} {1}: anoniem {2}, ingelogd {3}, curator {4}")
    @CsvSource({
        "GET,    /api/stops,                    404, 404, 404",
        "GET,    /api/stops/1,                  404, 404, 404",
        "GET,    /api/stops/1/history,          401, 404, 404",
        "GET,    /api/review/stats,             401, 403, 404",
        "POST,   /api/stops,                    401, 403, 404",
        "POST,   /api/stops/1/merge,            401, 403, 404",
        "PATCH,  /api/stops/1,                  401, 403, 404",
        "PUT,    /api/stops/1/attributes/x,     401, 403, 404",
        "DELETE, /api/stops/1,                  401, 403, 404",
        // Bestaat (nog) niet: een vergeten nieuwe schrijfroute moet toch dicht zijn.
        "POST,   /api/stops/1/something-new,    401, 403, 404",
        "POST,   /api/new-resource,             401, 403, 404",
    })
    void permissions(String method, String path, int anonymous, int loggedIn, int curator) throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        HttpMethod m = HttpMethod.valueOf(method);
        mvc.perform(request(m, path).with(csrf())).andExpect(status().is(anonymous));
        mvc.perform(request(m, path).with(csrf()).with(user("guest"))).andExpect(status().is(loggedIn));
        mvc.perform(request(m, path).with(csrf()).with(user("curator").authorities(new SimpleGrantedAuthority(SecurityConfig.CURATOR_AUTHORITY))))
            .andExpect(status().is(curator));
    }
}
