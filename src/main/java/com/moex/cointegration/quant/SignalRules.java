package com.moex.cointegration.quant;

/**
 * Правила входа/выхода по Z-score.
 * <p>
 * Режим {@code requireReversal=true}: вход не на первом касании ±entry, а когда Z уже
 * за порогом и разворачивается к нулю (fade the extreme). Это снижает ложные входы,
 * когда спред продолжает расширяться после пробоя.
 */
public final class SignalRules {

    private SignalRules() {
    }

    /** Short spread: Z был ≥ +entry и начал снижаться, ещё не перешёл через 0. */
    public static boolean confirmShortEntry(double zPrev, double zCur, double zEntry, boolean requireReversal) {
        if (Double.isNaN(zPrev) || Double.isNaN(zCur)) {
            return false;
        }
        if (!requireReversal) {
            return zPrev < zEntry && zCur >= zEntry;
        }
        // fade: ещё на short-стороне (Z>0), иначе mean-reversion уже отыграна
        return zPrev >= zEntry && zCur < zPrev && zCur > 0;
    }

    /** Long spread: Z был ≤ −entry и начал расти, ещё не перешёл через 0. */
    public static boolean confirmLongEntry(double zPrev, double zCur, double zEntry, boolean requireReversal) {
        if (Double.isNaN(zPrev) || Double.isNaN(zCur)) {
            return false;
        }
        if (!requireReversal) {
            return zPrev > -zEntry && zCur <= -zEntry;
        }
        // fade: ещё на long-стороне (Z<0), иначе вход после факта
        return zPrev <= -zEntry && zCur > zPrev && zCur < 0;
    }

    public static boolean exitLong(double zPrev, double zCur, double zExit) {
        if (Double.isNaN(zPrev) || Double.isNaN(zCur)) {
            return false;
        }
        return zPrev < zExit && zCur >= zExit;
    }

    public static boolean exitShort(double zPrev, double zCur, double zExit) {
        if (Double.isNaN(zPrev) || Double.isNaN(zCur)) {
            return false;
        }
        return zPrev > zExit && zCur <= zExit;
    }

    /**
     * Симулирует позицию по ряду Z: 0 flat, +1 long spread, −1 short spread.
     * Возвращает позицию после последнего бара.
     */
    public static int finalPosition(
            double[] z,
            double zEntry,
            double zExit,
            boolean requireReversal
    ) {
        int position = 0;
        for (int i = 1; i < z.length; i++) {
            double prev = z[i - 1];
            double cur = z[i];
            if (position == 0) {
                if (confirmLongEntry(prev, cur, zEntry, requireReversal)) {
                    position = 1;
                } else if (confirmShortEntry(prev, cur, zEntry, requireReversal)) {
                    position = -1;
                }
            } else if (position == 1 && exitLong(prev, cur, zExit)) {
                position = 0;
            } else if (position == -1 && exitShort(prev, cur, zExit)) {
                position = 0;
            }
        }
        return position;
    }
}
