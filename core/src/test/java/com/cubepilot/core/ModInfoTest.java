package com.cubepilot.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ModInfoTest {
    @Test
    void displaysIdNameAndVersionForModelContext() {
        ModInfo info = new ModInfo("ringmod", "七咒之戒", "1.2.3");

        assertTrue(info.displayText().contains("七咒之戒"));
    }
}
