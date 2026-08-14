package com.moex.cointegration.product;

import com.moex.cointegration.config.ProductProperties;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resolves active product edition: YAML default + optional in-memory override (settings demo).
 */
@Service
public class ProductEditionService {

    private final ProductProperties properties;
    private final AtomicReference<ProductEdition> override = new AtomicReference<>();

    public ProductEditionService(ProductProperties properties) {
        this.properties = properties != null ? properties : ProductProperties.defaults();
    }

    public ProductEdition configured() {
        return properties.configuredEdition();
    }

    public ProductEdition current() {
        ProductEdition o = override.get();
        return o != null ? o : configured();
    }

    public boolean hasOverride() {
        return override.get() != null;
    }

    public synchronized ProductEdition setOverride(ProductEdition edition) {
        if (edition == null) {
            override.set(null);
            return current();
        }
        override.set(edition);
        return edition;
    }

    public synchronized void clearOverride() {
        override.set(null);
    }

    public boolean hasPairs() {
        return current().hasPairs();
    }

    public boolean hasTrend() {
        return current().hasTrend();
    }

    public boolean hasArb() {
        return current().hasArb();
    }

    public Map<String, Object> dto() {
        ProductEdition cur = current();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("edition", cur.name());
        m.put("label", cur.labelRu());
        m.put("configured", configured().name());
        m.put("override", hasOverride());
        m.put("hasPairs", cur.hasPairs());
        m.put("hasTrend", cur.hasTrend());
        m.put("hasArb", cur.hasArb());
        return m;
    }

    public String lockTitle(String strategy) {
        String s = strategy == null ? "" : strategy.trim().toUpperCase();
        return switch (s) {
            case "TREND" -> "Стратегия «Тренд» недоступна";
            case "ARB", "CALENDAR_ARB", "CALENDAR-ARB" -> "Календарный арбитраж недоступен";
            default -> "Стратегия недоступна в вашей версии";
        };
    }

    public String lockBody(String strategy) {
        String s = strategy == null ? "" : strategy.trim().toUpperCase();
        ProductEdition cur = current();
        if ("TREND".equals(s)) {
            return "Сейчас активна версия «" + cur.labelRu()
                    + "». Трендовый desk (BR M5) доступен в тарифе «Коинтеграция + тренд» "
                    + "или Full Core (с календарным арбитражем).";
        }
        if ("ARB".equals(s) || "CALENDAR_ARB".equals(s) || "CALENDAR-ARB".equals(s)) {
            return "Календарный арбитраж фьючерсов — столп Full Core. "
                    + "Текущая версия: «" + cur.labelRu() + "».";
        }
        return "Обновите тариф, чтобы открыть эту стратегию.";
    }

    public String lockCtaHref(String strategy) {
        String s = strategy == null ? "" : strategy.trim().toUpperCase();
        if ("TREND".equals(s)) {
            return "/view/full-core?feature=trend";
        }
        return "/view/full-core?feature=calendar-arb";
    }

    public String lockCtaLabel(String strategy) {
        String s = strategy == null ? "" : strategy.trim().toUpperCase();
        if ("TREND".equals(s) && current() == ProductEdition.PAIRS) {
            return "Купить тренд или Full Core";
        }
        return "Открыть Full Core";
    }
}
