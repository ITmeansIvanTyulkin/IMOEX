package com.moex.trinity.shell;

import com.moex.trinity.shared.TrinityCore;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class BonesController {

    private final TrinityCore core;

    public BonesController(TrinityCore core) {
        this.core = core;
    }

    @GetMapping(value = {"/", "/view", "/view/"}, produces = MediaType.TEXT_HTML_VALUE)
    public String home() {
        return PAGE.formatted(escape(core.message()));
    }

    @GetMapping("/api/core")
    public Map<String, Object> core() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("present", core.present());
        m.put("edition", core.edition());
        m.put("message", core.message());
        m.put("trading", false);
        m.put("paper", false);
        m.put("playbooks", false);
        return m;
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "edition", core.edition());
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static final String PAGE = """
            <!DOCTYPE html>
            <html lang="ru">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>TRINITY — оценка продукта</title>
              <style>
                :root { color-scheme: dark; }
                body { margin: 0; font-family: "IBM Plex Sans", "Segoe UI", sans-serif;
                       background: #0e1116; color: #e8edf4; line-height: 1.5; }
                main { max-width: 42rem; margin: 12vh auto; padding: 0 1.5rem; }
                h1 { font-size: 1.75rem; letter-spacing: .12em; margin: 0 0 .25rem; }
                .sub { color: #8b98a8; margin: 0 0 1.5rem; }
                .card { border: 1px solid #2a3340; border-radius: 12px; padding: 1.25rem 1.4rem;
                        background: #161b22; }
                .badge { display: inline-block; font-size: .75rem; letter-spacing: .08em;
                         text-transform: uppercase; color: #f0c14b; border: 1px solid #f0c14b55;
                         padding: .15rem .5rem; border-radius: 999px; margin-bottom: .75rem; }
                a { color: #7eb6ff; }
                ul { padding-left: 1.2rem; color: #c5d0dc; }
                .foot { margin-top: 1.5rem; color: #6b7684; font-size: .85rem; }
              </style>
            </head>
            <body>
              <main>
                <p class="badge">Кости · BONES</p>
                <h1>TRINITY</h1>
                <p class="sub">Three Strategies. One Mission.</p>
                <div class="card">
                  <p><strong>%s</strong></p>
                  <p>Этот публичный клон — оболочка, чтобы <em>оценить</em> продукт: лицензия,
                  документация, UI-каркас. Без закрытого ядра приложение <strong>не</strong>
                  открывает сделки, не крутит плейбуки Exclusive / positional и не считает
                  боевые рекомендации пар.</p>
                  <ul>
                    <li>Плейбуки #1/#2, EG/Z, calendar-arb — модуль <code>IMOEX-core</code> (по подписке).</li>
                    <li><code>IMOEX_UNLOCK</code> — локальный секрет оператора, не защита исходников.</li>
                    <li>Research / decision-support, не auto-execution.</li>
                  </ul>
                  <p>Лендинг: репозиторий <code>trinity-landing</code>. Ядро оператора кладётся рядом:
                  <code>../IMOEX-core</code> — тогда <code>mvn -pl trinity-app -am spring-boot:run</code>.</p>
                </div>
                <p class="foot">Проприетарное ПО · см. LICENSE · Роспатент. Не индивидуальная инвестиционная рекомендация.</p>
              </main>
            </body>
            </html>
            """;
}
