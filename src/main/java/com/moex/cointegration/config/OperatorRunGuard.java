package com.moex.cointegration.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Без локального unlock / пароля API контекст не поднимается (до старта Tomcat).
 * Это не DRM: публичный код всё ещё можно пропатчить, но «из коробки» клон не работает.
 */
@Component
public class OperatorRunGuard {

    private static final Logger log = LoggerFactory.getLogger(OperatorRunGuard.class);

    private static final Set<String> FORBIDDEN_PASSWORDS = Set.of(
            "",
            "change-me",
            "changeme",
            "password",
            "pass",
            "admin",
            "imoex",
            "secret",
            "123456"
    );

    private final RunGuardProperties runGuard;
    private final ImoexProperties properties;
    private final Environment environment;

    public OperatorRunGuard(RunGuardProperties runGuard, ImoexProperties properties, Environment environment) {
        this.runGuard = runGuard;
        this.properties = properties;
        this.environment = environment;
    }

    @PostConstruct
    void verifyLocalSecrets() {
        if (isTestLikeProfile()) {
            log.debug("Run guard skipped (test/profile bypass).");
            return;
        }

        String unlock = runGuard.unlock() == null ? "" : runGuard.unlock().trim();
        if (unlock.isEmpty() || unlock.startsWith("paste-") || "REPLACE_ME".equalsIgnoreCase(unlock)) {
            throw new IllegalStateException("""
                    IMOEX не запустится без локального unlock.
                    Скопируйте application-local.yml.example → application-local.yml
                    (файл в .gitignore) и задайте imoex.run.unlock, либо export IMOEX_UNLOCK=...
                    Публичный клон репозитория намеренно не стартует «из коробки».
                    """.stripIndent().trim());
        }

        var auth = properties.auth();
        if (auth != null && auth.enabled()) {
            String password = auth.password() == null ? "" : auth.password().trim();
            if (FORBIDDEN_PASSWORDS.contains(password.toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException("""
                        imoex.auth.enabled=true, но пароль пустой или слишком слабый/публичный.
                        Задайте сильный пароль в application-local.yml (imoex.auth.password)
                        или через env IMOEX_AUTH_PASSWORD. Не коммитьте этот файл.
                        """.stripIndent().trim());
            }
        }

        log.info("Operator run guard OK (local unlock present).");
    }

    private boolean isTestLikeProfile() {
        for (String p : environment.getActiveProfiles()) {
            if ("test".equalsIgnoreCase(p) || "ci".equalsIgnoreCase(p)) {
                return true;
            }
        }
        String skip = environment.getProperty("imoex.run.skip-guard", "false");
        return "true".equalsIgnoreCase(skip);
    }
}
