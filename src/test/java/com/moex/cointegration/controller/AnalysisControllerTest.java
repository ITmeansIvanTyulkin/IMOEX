package com.moex.cointegration.controller;

import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.config.SecurityConfig;
import com.moex.cointegration.model.AnalysisReport;
import com.moex.cointegration.model.PaperJournal;
import com.moex.cointegration.service.ChartDataService;
import com.moex.cointegration.service.ChartService;
import com.moex.cointegration.service.CointegrationAnalysisService;
import com.moex.cointegration.service.FinalRecommendationService;
import com.moex.cointegration.service.MarketDataService;
import com.moex.cointegration.service.PaperTradingService;
import com.moex.cointegration.service.RiskPolicyService;
import com.moex.cointegration.service.TradingRecommendationService;
import com.moex.cointegration.service.WalkForwardService;
import com.moex.cointegration.storage.MarketDataStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AnalysisController.class)
@Import({SecurityConfig.class, ApiExceptionHandler.class})
class AnalysisControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    CointegrationAnalysisService analysisService;
    @MockBean
    MarketDataService marketDataService;
    @MockBean
    ChartService chartService;
    @MockBean
    ChartDataService chartDataService;
    @MockBean
    MarketDataStorage storage;
    @MockBean
    TradingRecommendationService recommendationService;
    @MockBean
    FinalRecommendationService finalRecommendationService;
    @MockBean
    WalkForwardService walkForwardService;
    @MockBean
    PaperTradingService paperTradingService;
    @MockBean
    RiskPolicyService riskPolicyService;
    @MockBean
    ImoexProperties imoexProperties;

    @Test
    void runAnalysisReturnsSummary() throws Exception {
        AnalysisReport report = new AnalysisReport(LocalDate.of(2026, 7, 25), 10, 45, 3, List.of());
        when(analysisService.runFullAnalysis(anyBoolean())).thenReturn(report);
        when(recommendationService.getLastRecommendations()).thenReturn(List.of());

        mockMvc.perform(post("/api/analysis/run").param("refresh", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tickersAnalyzed").value(10))
                .andExpect(jsonPath("$.pairsTested").value(45))
                .andExpect(jsonPath("$.cointegratedPairs").value(3));
    }

    @Test
    void latestReportNotFoundWhenMissing() throws Exception {
        when(storage.loadReport()).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/analysis/report"))
                .andExpect(status().isNotFound());
    }

    @Test
    void paperJournalEndpoint() throws Exception {
        when(paperTradingService.summary())
                .thenReturn(new PaperJournal(LocalDateTime.now(), List.of()));
        mockMvc.perform(get("/api/paper/journal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries").isArray());
    }

    @Test
    void riskPolicyEndpoint() throws Exception {
        when(riskPolicyService.policy()).thenReturn(ImoexProperties.RiskProperties.defaults());
        mockMvc.perform(get("/api/risk/policy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stopZ").value(3.5));
    }
}
