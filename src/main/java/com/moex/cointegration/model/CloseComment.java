package com.moex.cointegration.model;

import java.util.Locale;

/**
 * Краткая категория закрытия paper-сделки для UI и анализа.
 */
public final class CloseComment {

    private CloseComment() {
    }

    public static String categorize(String closeReason) {
        if (closeReason == null || closeReason.isBlank()) {
            return "";
        }
        String r = closeReason.toLowerCase(Locale.ROOT);
        if (r.contains("flatten") || r.contains("pre-close") || r.contains("session end")) {
            return "flatten";
        }
        if (r.contains("time-stop")) {
            return "time-stop";
        }
        if (r.contains("mean-reversion") || r.contains("hold/no_signal")) {
            return "mean-reversion";
        }
        if (r.contains("stop") || r.contains("trailing") || r.contains("β-break")
                || r.contains("cointegration break") || r.contains("structural_break")
                || r.contains("long→short") || r.contains("short→long")) {
            return "stop";
        }
        if (r.contains("partial tp") || r.contains("poc")) {
            return "partial-tp";
        }
        return closeReason.length() > 40 ? closeReason.substring(0, 37) + "…" : closeReason;
    }
}
