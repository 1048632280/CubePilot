package com.cubepilot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CubePilotConfigTest {
    @TempDir
    Path serverRoot;

    @Test
    void ensureExistsCreatesDocumentedTomlWithoutLoadingApi() throws IOException {
        Path configPath = CubePilotConfig.ensureExists(serverRoot);
        String contents = Files.readString(configPath);

        assertEquals(serverRoot.resolve("config/cubepilot/cubepilot.toml"), configPath);
        assertTrue(Files.isRegularFile(configPath));
        assertTrue(contents.contains("endpoint = \"https://api.openai.com/v1/chat/completions\""));
        assertTrue(contents.contains("[command.auto-allow]"));
        assertTrue(contents.contains("[command.auto-deny]"));
        assertTrue(contents.contains("permanent-auto-deny"));
        assertTrue(contents.contains("0 = 关闭此项限制"));
        assertTrue(contents.contains("offer-add-to-auto-allow = true"));
        assertTrue(contents.contains("max-scan-files = 48"));
        assertTrue(contents.contains("max-scan-bytes = 524288"));

        Files.writeString(configPath, "[api]\nendpoint = \"http://remote.example.invalid/v1\"\n");
        assertEquals(configPath, CubePilotConfig.ensureExists(serverRoot));
        assertTrue(Files.readString(configPath).contains("remote.example.invalid"));
    }

    @Test
    void loadsArraysSafetyModesAndButtonSwitch() throws IOException {
        Path configPath = CubePilotConfig.ensureExists(serverRoot);
        String contents = Files.readString(configPath)
                .replace("chat-trigger-enabled = false", "chat-trigger-enabled = true")
                .replace("chat-permission-level = 2", "chat-permission-level = 1")
                .replace("offer-add-to-auto-allow = true", "offer-add-to-auto-allow = false")
                .replace("    # \"time\",", "    \"time\",")
                .replace("    # \"weather clear\",", "    \"weather clear\",")
                .replace("    # \"time set day\",", "    \"time set day\",")
                .replace("all-players-selector = 3", "all-players-selector = 1")
                .replace("complex-nbt = 3", "complex-nbt = 2")
                .replace("multiple-targets = 3", "multiple-targets = 0");
        Files.writeString(configPath, contents);

        CubePilotConfig config = CubePilotConfig.load(serverRoot);

        assertFalse(config.offerAddToAutoAllow());
        assertTrue(config.autoAllow().roots().contains("time"));
        assertTrue(config.autoAllow().subcommands().contains("weather clear"));
        assertTrue(config.autoAllow().exact().contains("time set day"));
        assertEquals(1, config.safetyMode(CubePilotConfig.SafetyRule.ALL_PLAYERS_SELECTOR));
        assertEquals(2, config.safetyMode(CubePilotConfig.SafetyRule.COMPLEX_NBT));
        assertEquals(0, config.safetyMode(CubePilotConfig.SafetyRule.MULTIPLE_TARGETS));
        assertEquals(48, config.maxScanFiles());
        assertEquals(524288, config.maxScanBytes());
        assertTrue(config.chatTriggerEnabled());
        assertEquals(1, config.chatPermissionLevel());
        assertEquals(java.util.List.of("!ai"), config.chatPrefixes());
    }

    @Test
    void buttonAddsOnlyExactCommandAndPreservesComments() throws IOException {
        Path configPath = CubePilotConfig.ensureExists(serverRoot);

        assertTrue(CubePilotConfig.addExactAutoAllowedCommand(serverRoot, "/time   set day"));
        assertFalse(CubePilotConfig.addExactAutoAllowedCommand(serverRoot, "time set day"));

        String contents = Files.readString(configPath);
        CubePilotConfig config = CubePilotConfig.load(serverRoot);
        assertTrue(contents.contains("# 只有命中下列任意数组"));
        assertTrue(contents.contains("    \"time set day\","));
        assertTrue(config.autoAllow().exact().contains("time set day"));
        assertFalse(config.autoAllow().roots().contains("time"));
    }

    @Test
    void buttonCanAppendToInlineExactArrayWithoutTrailingComma() throws IOException {
        Path configPath = CubePilotConfig.ensureExists(serverRoot);
        String contents = Files.readString(configPath).replace(
                "exact = [\n    # \"time set day\",\n]", "exact = [\"time set day\"]");
        Files.writeString(configPath, contents);

        assertTrue(CubePilotConfig.addExactAutoAllowedCommand(serverRoot, "time set night"));

        CubePilotConfig config = CubePilotConfig.load(serverRoot);
        assertTrue(config.autoAllow().exact().contains("time set day"));
        assertTrue(config.autoAllow().exact().contains("time set night"));
    }

    @Test
    void rejectsInvalidEndpointSafetyModeAndRuleShape() throws IOException {
        Path configPath = CubePilotConfig.ensureExists(serverRoot);
        String defaults = Files.readString(configPath);

        Files.writeString(configPath, defaults.replace(
                "https://api.openai.com/v1/chat/completions",
                "https://user:password@example.com/v1/chat/completions"));
        assertThrows(IllegalArgumentException.class, () -> CubePilotConfig.load(serverRoot));

        Files.writeString(configPath, defaults.replace("all-players-selector = 3", "all-players-selector = 4"));
        assertThrows(IllegalArgumentException.class, () -> CubePilotConfig.load(serverRoot));

        Files.writeString(configPath, defaults.replace("    # \"time\",", "    \"time set\","));
        assertThrows(IllegalArgumentException.class, () -> CubePilotConfig.load(serverRoot));
    }
}
