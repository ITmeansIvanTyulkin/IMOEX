package com.moex.trinity.shared;

/**
 * IP axis (not delivery Instance/Core, not price tier): whether the trading kernel
 * is on the classpath.
 * <p>
 * Bones (public clone) → {@link #present()} is false — evaluation shell only.
 * Operator (sibling {@code IMOEX-core}) → true — playbooks / EG / calendar-arb.
 * This is not DRM: a JVM can still decompile a core jar. Real protection is
 * not publishing the kernel sources on the public GitHub.
 */
public record TrinityCore(boolean present, String edition, String message) {

    public static final String EDITION_BONES = "BONES";
    public static final String EDITION_OPERATOR = "OPERATOR";

    public static TrinityCore bones() {
        return new TrinityCore(
                false,
                EDITION_BONES,
                "Публичные Кости: оценка продукта. Торговое ядро по подписке (IMOEX-core)."
        );
    }

    public static TrinityCore operator() {
        return new TrinityCore(
                true,
                EDITION_OPERATOR,
                "Торговое ядро на classpath (плейбуки #1/#2, пары, calendar-arb)."
        );
    }
}
