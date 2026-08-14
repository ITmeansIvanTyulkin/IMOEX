package com.moex.trinity.shell;

import com.moex.trinity.shared.TrinityCore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class BonesControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    TrinityCore core;

    @Test
    void bonesDoesNotExposeTradingKernel() throws Exception {
        assertFalse(core.present());
        mvc.perform(get("/api/core"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.present").value(false))
                .andExpect(jsonPath("$.trading").value(false))
                .andExpect(jsonPath("$.paper").value(false))
                .andExpect(jsonPath("$.playbooks").value(false))
                .andExpect(jsonPath("$.edition").value("BONES"));
    }

    @Test
    void homeExplainsSubscriptionKernel() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String html = result.getResponse().getContentAsString();
                    if (!html.contains("по подписке") && !html.contains("Кости")) {
                        throw new AssertionError("bones page must say evaluation / subscription");
                    }
                });
    }
}
