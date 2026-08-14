package com.moex.cointegration.config;

import com.moex.cointegration.product.ProductEdition;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Product SKU simulation for operator UX locks. No billing.
 */
@ConfigurationProperties(prefix = "imoex.product")
public record ProductProperties(String edition) {

    public ProductProperties {
        if (edition == null || edition.isBlank()) {
            edition = "FULL";
        }
    }

    public ProductEdition configuredEdition() {
        return ProductEdition.parse(edition);
    }

    public static ProductProperties defaults() {
        return new ProductProperties("FULL");
    }
}
