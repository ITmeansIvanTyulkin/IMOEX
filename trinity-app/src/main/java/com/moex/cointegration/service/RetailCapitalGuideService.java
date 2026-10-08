package com.moex.cointegration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moex.cointegration.config.CapitalProperties;
import com.moex.cointegration.config.ImoexProperties;
import com.moex.trinity.trend.RetailBrmSize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mini-book onboarding (50–150k): suggest BRM sleeve, park Exclusive/positional.
 */
@Service
public class RetailCapitalGuideService {

    private static final Logger log = LoggerFactory.getLogger(RetailCapitalGuideService.class);

    private final CapitalProperties capital;
    private final ObjectProvider<TrendSettingsService> trendSettings;
    private final Path stateFile;
    private final ObjectMapper mapper = new ObjectMapper();

    public RetailCapitalGuideService(
            CapitalProperties capital,
            ImoexProperties imoexProperties,
            ObjectProvider<TrendSettingsService> trendSettings
    ) {
        this.capital = capital;
        this.trendSettings = trendSettings;
        this.stateFile = Path.of(imoexProperties.dataDir(), "retail-capital-guide.json");
    }

    public Map<String, Object> prompt() {
        double equity = capital.equityRub() != null ? capital.equityRub() : 0;
        boolean mini = RetailBrmSize.isMiniBook(equity);
        State st = load();
        boolean justApplied = false;
        // Trial / new sub with 50–150k: arm BRM before the modal explains why.
        if (mini && !st.applied) {
            justApplied = ensureMiniDefaults(equity);
            st = load();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("equityRub", equity);
        m.put("miniBook", mini);
        m.put("miniBookMinRub", RetailBrmSize.PRODUCT_MIN_RUB);
        m.put("miniBookMaxRub", RetailBrmSize.MINI_BOOK_MAX_RUB);
        m.put("dismissed", st.dismissed);
        m.put("applied", st.applied);
        m.put("justApplied", justApplied);
        m.put("showModal", mini && !st.dismissed);
        m.put("title", "Капитал под мини-нефть");
        m.put("body", bodyText(equity));
        m.put("ctaPrimary", "Понятно, оставить BRM");
        m.put("ctaSecondary", "Закрыть");
        return m;
    }

    /**
     * Dismiss modal; {@code applyDefaults=true} re-asserts BRM-on / Exclusive+positional-off.
     */
    public Map<String, Object> acknowledge(boolean applyDefaults) {
        double equity = capital.equityRub() != null ? capital.equityRub() : 0;
        boolean applied = false;
        if (applyDefaults && RetailBrmSize.isMiniBook(equity)) {
            applied = ensureMiniDefaults(equity);
        }
        State prev = load();
        State st = new State(true, applied || prev.applied, LocalDateTime.now().toString());
        save(st);
        Map<String, Object> out = prompt();
        out.put("justApplied", applied);
        out.put("showModal", false);
        return out;
    }

    private boolean ensureMiniDefaults(double equity) {
        TrendSettingsService ts = trendSettings.getIfAvailable();
        boolean toggled = false;
        if (ts != null) {
            toggled = ts.applyMiniBookDefaults(equity);
        }
        State prev = load();
        if (!prev.applied || toggled) {
            save(new State(prev.dismissed, true, LocalDateTime.now().toString()));
        }
        return toggled || !prev.applied;
    }

    private static String bodyText(double equity) {
        long eq = Math.round(equity);
        return "На счёте около " + eq + " ₽. При таком капитале сложно нормально распределить деньги "
                + "между диапазонной торговлей, позиционной и календарным арбитражем — "
                + "на каждый рукав останется слишком мало, а результат будет копеечным.\n\n"
                + "Поэтому у вас автоматически включён робот BRM мини (нефть на пяти минутах, "
                + "маленький контракт). Диапазонная и позиционная по умолчанию выключены.\n\n"
                + "Их можно включить вручную в Настройках, но доля капитала под них будет символической — "
                + "в этом режиме заработок с этих рукавов будет копеечным.";
    }

    private State load() {
        if (!Files.isRegularFile(stateFile)) {
            return State.empty();
        }
        try {
            State s = mapper.readValue(stateFile.toFile(), State.class);
            return s == null ? State.empty() : s;
        } catch (Exception ex) {
            log.debug("retail-capital-guide load: {}", ex.toString());
            return State.empty();
        }
    }

    private void save(State st) {
        try {
            Files.createDirectories(stateFile.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(stateFile.toFile(), st);
        } catch (Exception ex) {
            log.warn("retail-capital-guide save: {}", ex.toString());
        }
    }

    public static final class State {
        public boolean dismissed;
        public boolean applied;
        public String updatedAt;

        public State() {
        }

        public State(boolean dismissed, boolean applied, String updatedAt) {
            this.dismissed = dismissed;
            this.applied = applied;
            this.updatedAt = updatedAt;
        }

        static State empty() {
            return new State(false, false, null);
        }
    }
}
