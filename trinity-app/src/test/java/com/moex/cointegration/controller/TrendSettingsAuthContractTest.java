package com.moex.cointegration.controller;

import com.moex.cointegration.TestBootApplication;
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
 * Regression: GET trend settings/desk stay public; POST settings requires auth
 * (hard-refresh must not rely on anonymous POST).
 */
@WebMvcTest(controllers = TrendSettingsAuthContractTest.StubTrendApi.class)
@AutoConfigureMockMvc
@ContextConfiguration(classes = TestBootApplication.class)
@Import({SecurityConfig.class, TrendSettingsAuthContractTest.AuthPropsConfig.class, TrendSettingsAuthContractTest.StubTrendApi.class})
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
    void getSettingsIsPublic() throws Exception {
        mockMvc.perform(get("/api/trend/settings").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void getDeskIsPublic() throws Exception {
        mockMvc.perform(get("/api/trend/desk").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
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
