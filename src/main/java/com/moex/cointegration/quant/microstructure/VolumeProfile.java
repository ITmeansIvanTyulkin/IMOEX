package com.moex.cointegration.quant.microstructure;

/**
 * Volume profile snapshot (ATAS-style POC / value area).
 */
public record VolumeProfile(
        double pocPrice,
        double vahPrice,
        double valPrice,
        double totalVolume
) {
    public boolean containsPrice(double price) {
        return price >= valPrice && price <= vahPrice;
    }
}
