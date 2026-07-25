package com.moex.cointegration.quant;

/**
 * Правила умного выхода: partial take-profit, trailing, слом коинтеграции (β / p-value).
 */
public final class ExitRules {

    private ExitRules() {
    }

    /**
     * Z прошёл ≥ fraction пути от entry к нулю (например 0.5 = половина пути).
     */
    public static boolean halfwayToZero(double entryZ, double currentZ, double fraction) {
        if (Double.isNaN(entryZ) || Double.isNaN(currentZ) || fraction <= 0 || fraction >= 1) {
            return false;
        }
        double target = entryZ * (1.0 - fraction);
        if (entryZ < 0) {
            // long: entry −2.4 → halfway −1.2; current должен быть ≥ −1.2 (ближе к 0)
            return currentZ >= target;
        }
        // short: entry +2.4 → halfway +1.2; current ≤ +1.2
        return currentZ <= target;
    }

    /**
     * Лучший Z в пользу mean-reversion (ближе к 0 / дальше за 0 в нужную сторону).
     * LONG: более высокий Z лучше; SHORT: более низкий Z лучше.
     */
    public static double updateBestZ(boolean longSpread, double bestSoFar, double currentZ) {
        if (Double.isNaN(currentZ)) {
            return bestSoFar;
        }
        if (Double.isNaN(bestSoFar)) {
            return currentZ;
        }
        if (longSpread) {
            return Math.max(bestSoFar, currentZ);
        }
        return Math.min(bestSoFar, currentZ);
    }

    /**
     * Trailing: от лучшего Z откат против позиции ≥ trailZ.
     */
    public static boolean trailStopHit(boolean longSpread, double bestZ, double currentZ, double trailZ) {
        if (Double.isNaN(bestZ) || Double.isNaN(currentZ) || trailZ <= 0) {
            return false;
        }
        if (longSpread) {
            return currentZ <= bestZ - trailZ;
        }
        return currentZ >= bestZ + trailZ;
    }

    /** Относительный скачок |β|: |β_now − β_entry| / max(|β_entry|, eps). */
    public static boolean betaBreak(double entryBeta, double currentBeta, double maxRelJump) {
        if (Double.isNaN(entryBeta) || Double.isNaN(currentBeta) || maxRelJump <= 0) {
            return false;
        }
        double base = Math.max(Math.abs(entryBeta), 1e-6);
        return Math.abs(currentBeta - entryBeta) / base >= maxRelJump;
    }

    /** Слом коинтеграции по p-value (rolling / last EG). */
    public static boolean cointegrationBroken(double pValue, double pBreak) {
        if (Double.isNaN(pValue) || pBreak <= 0) {
            return false;
        }
        return pValue >= pBreak;
    }
}
