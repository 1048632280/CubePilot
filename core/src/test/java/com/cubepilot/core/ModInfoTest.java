package com.cubepilot.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ModInfoTest {
    @Test
    void matchesDisplayNameInNaturalLanguage() {
        ModInfo info = new ModInfo("ringmod", "七咒之戒", "1.2.3");

        assertTrue(info.matches("我想知道七咒之戒的配置文件，如何关闭死亡掉血"));
        assertTrue(info.displayText().contains("七咒之戒"));
    }
}
