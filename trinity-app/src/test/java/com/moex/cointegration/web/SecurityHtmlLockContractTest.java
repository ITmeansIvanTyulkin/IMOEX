package com.moex.cointegration.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Strategy HTML is not public; the login gate page is. */
class SecurityHtmlLockContractTest {

    @Test
    void securityLocksViewDesksButKeepsGatePublic() throws Exception {
        Path p = Path.of("src/main/java/com/moex/cointegration/config/SecurityConfig.java");
        if (!Files.isRegularFile(p)) {
            p = Path.of("trinity-app/src/main/java/com/moex/cointegration/config/SecurityConfig.java");
        }
        String src = Files.readString(p, StandardCharsets.UTF_8);
        assertTrue(src.contains("\"/\", \"/view\", \"/view/\""), src);
        assertTrue(src.contains(".requestMatchers(\"/view/**\").authenticated()"), src);
        assertTrue(src.contains("POST, \"/api/auth/login\", \"/api/auth/logout\""), src);
        assertTrue(src.contains("DeskSessionAuthFilter"), src);
        assertTrue(!src.contains(".requestMatchers(\"/\", \"/view\", \"/view/**\").permitAll()"),
                "must not leave all /view HTML public");
    }
}
