package com.moex.trinity;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrinityUserDataPathsTest {

    @Test
    void userTokenResolvesToDurableHome() {
        Path p = TrinityUserDataPaths.resolveDataDir("user");
        assertTrue(p.isAbsolute());
        String s = p.toString();
        assertTrue(
                s.contains("TRINITY") || s.contains("trinity"),
                "expected TRINITY folder in " + s
        );
        assertEquals(p, TrinityUserDataPaths.resolveDataDir(""));
        assertTrue(TrinityUserDataPaths.isUserToken("user"));
        assertFalse(TrinityUserDataPaths.isUserToken("data"));
    }

    @Test
    void relativeDataLeftAsConfigured() {
        Path p = TrinityUserDataPaths.resolveDataDir("data");
        assertTrue(p.endsWith("data") || p.getFileName().toString().equals("data"));
    }
}
