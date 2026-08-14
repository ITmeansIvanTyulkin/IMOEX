package com.moex.trinity.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrinityCoreTest {

    @Test
    void bonesIsEvaluationShell() {
        TrinityCore bones = TrinityCore.bones();
        assertFalse(bones.present());
        assertEquals(TrinityCore.EDITION_BONES, bones.edition());
        assertTrue(bones.message().contains("подписке"));
    }

    @Test
    void operatorHasKernel() {
        TrinityCore operator = TrinityCore.operator();
        assertTrue(operator.present());
        assertEquals(TrinityCore.EDITION_OPERATOR, operator.edition());
    }
}
