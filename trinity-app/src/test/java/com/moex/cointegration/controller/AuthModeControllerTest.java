package com.moex.cointegration.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import com.moex.cointegration.TestBootApplication;
import com.moex.cointegration.config.ImoexProperties;

import static org.mockito.Mockito.when;

@WebMvcTest(controllers = AuthModeController.class)
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = TestBootApplication.class)
@Import(AuthModeController.class)
class AuthModeControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    ImoexProperties properties;

    @Test
    void modeExposesSupabaseFlags() throws Exception {
        when(properties.auth()).thenReturn(ImoexProperties.AuthProperties.defaults());
        mockMvc.perform(get("/api/auth/mode"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supabase.enabled").value(false));
    }

    @Test
    void loginRejectedWhenSupabaseDisabled() throws Exception {
        when(properties.auth()).thenReturn(ImoexProperties.AuthProperties.defaults());
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.c\",\"password\":\"x\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("supabase_disabled"));
    }

    @Test
    void loginRequiresCredentials() throws Exception {
        var sb = new ImoexProperties.AuthProperties.SupabaseProperties(
                true, "https://example.supabase.co", "", "anon"
        );
        when(properties.auth()).thenReturn(new ImoexProperties.AuthProperties(
                true, "imoex", "secret", "", sb
        ));
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("missing_credentials"));
    }
}
