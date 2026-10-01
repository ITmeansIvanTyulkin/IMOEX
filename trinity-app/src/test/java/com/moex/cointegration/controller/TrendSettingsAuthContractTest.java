package com.moex.cointegration.controller;

import com.moex.cointegration.TestBootApplication;
import com.moex.cointegration.config.DeskSessionStore;
import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression: GET and POST /api/trend/* require auth; login/mode stay public.
 */
@WebMvcTest(controllers = {
        TrendSettingsAuthContractTest.StubTrendApi.class,
        TrendSettingsAuthContractTest.StubViews.class
})
@AutoConfigureMockMvc
@ContextConfiguration(classes = TestBootApplication.class)
@Import({SecurityConfig.class, DeskSessionStore.class, TrendSettingsAuthContractTest.AuthPropsConfig.class, TrendSettingsAuthContractTest.StubTrendApi.class, TrendSettingsAuthContractTest.StubViews.class})
@TestPropertySource(properties = {
        "imoex.auth.enabled=true",
        "imoex.auth.username=imoex",
        "imoex.auth.password=secret",
        "imoex.auth.supabase.enabled=false",
        "imoex.smoke.on-startup=false",
        "spring.main.allow-bean-definition-overriding=true"
})
class TrendSettingsAuthContractTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void getSettingsUnauthorizedWithoutCredentials() throws Exception {
        mockMvc.perform(get("/api/trend/settings").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    void getDeskUnauthorizedWithoutCredentials() throws Exception {
        mockMvc.perform(get("/api/trend/desk").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getSettingsOkWithBasic() throws Exception {
        mockMvc.perform(get("/api/trend/settings")
                        .with(httpBasic("imoex", "secret"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void postSettingsUnauthorizedWithoutCredentials() throws Exception {
        mockMvc.perform(post("/api/trend/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playbookId\":\"both\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    void postSettingsOkWithBasic() throws Exception {
        mockMvc.perform(post("/api/trend/settings")
                        .with(httpBasic("imoex", "secret"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playbookId\":\"both\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(true));
    }

    @Test
    void gateHtmlStaysPublic() throws Exception {
        mockMvc.perform(get("/view").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk());
    }

    @Test
    void deskHtmlUnauthorizedWithoutSession() throws Exception {
        mockMvc.perform(get("/view/trend-signal").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deskHtmlOkWithBasic() throws Exception {
        mockMvc.perform(get("/view/trend-signal")
                        .with(httpBasic("imoex", "secret"))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk());
    }

    @Test
    void deskHtmlRedirectsBrowserWithoutSession() throws Exception {
        mockMvc.perform(get("/view/trend-signal").accept(MediaType.TEXT_HTML))
                .andExpect(status().isFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Location", "/view"));
    }

    @RestController
    @RequestMapping("/api/trend")
    static class StubTrendApi {
        @GetMapping("/settings")
        Map<String, Object> settings() {
            return Map.of("ok", true);
        }

        @PostMapping("/settings")
        Map<String, Object> save() {
            return Map.of("saved", true);
        }

        @GetMapping("/desk")
        Map<String, Object> desk() {
            return Map.of("bars", java.util.List.of());
        }
    }

    @RestController
    static class StubViews {
        @GetMapping(value = "/view", produces = MediaType.TEXT_HTML_VALUE)
        String gate() {
            return "<html>gate</html>";
        }

        @GetMapping(value = "/view/trend-signal", produces = MediaType.TEXT_HTML_VALUE)
        String desk() {
            return "<html>desk</html>";
        }
    }

    @TestConfiguration
    static class AuthPropsConfig {
        @Bean
        ImoexProperties imoexProperties() {
            return new ImoexProperties(
                    "https://iss.moex.com/iss",
                    "TQBR",
                    "IMOEX",
                    5,
                    0.0005,
                    null,
                    null,
                    "data",
                    "data/charts",
                    null,
                    null,
                    null,
                    null,
                    null,
                    new ImoexProperties.AuthProperties(
                            true,
                            "imoex",
                            "secret",
                            "",
                            ImoexProperties.AuthProperties.SupabaseProperties.defaults()
                    )
            );
        }
    }
}
