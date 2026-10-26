package com.cubepilot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandPolicyTest {
    @TempDir
    Path serverRoot;

    @Test
    void matchesRootSubcommandAndExactAutomaticRules() throws IOException {
        CubePilotConfig root = configWith(defaults -> defaults.replace(
                "    # \"time\",", "    \"time\","));
        assertTrue(decide("time set night", root).automatic());

        CubePilotConfig subcommand = configWith(defaults -> defaults.replace(
                "    # \"weather clear\",", "    \"weather clear\","));
        assertTrue(decide("weather clear 30", subcommand).automatic());
        assertFalse(decide("weather thunder", subcommand).automatic());

        CubePilotConfig exact = configWith(defaults -> defaults.replace(
                "    # \"time set day\",", "    \"time set day\","));
        assertTrue(decide("/time   set day", exact).automatic());
        assertFalse(decide("time set noon", exact).automatic());
    }

    @Test
    void configurableAndPermanentDeniesOverrideAutomaticAllow() throws IOException {
        CubePilotConfig configurableDeny = configWith(defaults -> defaults
                .replace("    # \"time\",", "    \"time\",")
                .replace("    # \"weather thunder\",", "    \"time set\","));
        CommandPolicy.Decision denied = decide("time set day", configurableDeny);
        assertFalse(denied.automatic());
        assertTrue(denied.manualAllowed());

        CubePilotConfig rootAllow = configWith(defaults -> defaults.replace(
                "    # \"time\",", "    \"stop\","));
        CommandPolicy.Decision permanent = decide("stop", rootAllow);
        assertFalse(permanent.automatic());
        assertTrue(permanent.manualAllowed());
        assertFalse(permanent.offerAddToAutoAllow());
    }

    @Test
    void safetyModesSeparateAutomaticAndManualChannels() throws IOException {
        CubePilotConfig autoOnly = configWith(defaults -> defaults
                .replace("    # \"time\",", "    \"give\",")
                .replace("complex-nbt = 3", "complex-nbt = 1"));
        CommandPolicy.Decision one = decide("give @s stone{display:{Name:'x'}} 1", autoOnly);
        assertFalse(one.automatic());
        assertTrue(one.manualAllowed());

        CubePilotConfig manualOnly = configWith(defaults -> defaults
                .replace("    # \"time\",", "    \"give\",")
                .replace("complex-nbt = 3", "complex-nbt = 2"));
        CommandPolicy.Decision two = decide("give @s stone{display:{Name:'x'}} 1", manualOnly);
        assertTrue(two.automatic());
        assertFalse(two.manualAllowed());

        CubePilotConfig both = configWith(defaults -> defaults
                .replace("    # \"time\",", "    \"give\","));
        CommandPolicy.Decision three = decide("give @s stone{display:{Name:'x'}} 1", both);
        assertFalse(three.automatic());
        assertFalse(three.manualAllowed());

        CubePilotConfig off = configWith(defaults -> defaults
                .replace("    # \"time\",", "    \"give\",")
                .replace("complex-nbt = 3", "complex-nbt = 0"));
        CommandPolicy.Decision zero = decide("give @s stone{display:{Name:'x'}} 1", off);
        assertTrue(zero.automatic());
        assertTrue(zero.manualAllowed());
    }

    @Test
    void selectorsAndOtherOnlinePlayersAreIndependentRules() throws IOException {
        CubePilotConfig config = configWith(defaults -> defaults
                .replace("    # \"time\",", "    \"effect\",")
                .replace("all-players-selector = 3", "all-players-selector = 1")
                .replace("multiple-targets = 3", "multiple-targets = 0")
                .replace("other-players = 3", "other-players = 0"));
        CommandPolicy.Decision selector = decide("effect give @a speed", config);
        assertFalse(selector.automatic());
        assertTrue(selector.manualAllowed());

        CubePilotConfig otherPlayerBlocked = configWith(defaults -> defaults
                .replace("    # \"time\",", "    \"effect\",")
                .replace("other-players = 3", "other-players = 1"));
        CommandPolicy.Decision player = CommandPolicy.evaluate(
                "effect give Alex speed", otherPlayerBlocked, "Steve", List.of("Steve", "Alex"));
        assertFalse(player.automatic());
        assertTrue(player.manualAllowed());
    }

    @Test
    void buttonSwitchAndInvalidCommandsAreRespected() throws IOException {
        CubePilotConfig enabled = CubePilotConfig.load(serverRoot);
        CommandPolicy.Decision offered = decide("time set day", enabled);
        assertFalse(offered.automatic());
        assertTrue(offered.manualAllowed());
        assertTrue(offered.offerAddToAutoAllow());

        CubePilotConfig disabled = configWith(defaults -> defaults.replace(
                "offer-add-to-auto-allow = true", "offer-add-to-auto-allow = false"));
        assertFalse(decide("time set day", disabled).offerAddToAutoAllow());
        assertFalse(decide("time set day; stop", disabled).valid());
        assertEquals("give @s stone 1", CommandPolicy.normalizeForRule(" /give   @s stone 1 "));
    }

    @Test
    void quotedDangerousNbtKeysCannotBypassPermanentAutoDeny() throws IOException {
        CubePilotConfig allowGive = configWith(defaults -> defaults
                .replace("    # \"time\",", "    \"give\",")
                .replace("complex-nbt = 3", "complex-nbt = 0"));

        for (String payload : List.of(
                "{\"command\":\"say hacked\"}",
                "{'entity_data':{id:'minecraft:stone'}}",
                "{\"minecraft:entity_data\":{id:\"minecraft:stone\"}}")) {
            assertFalse(decide("give @s stone" + payload + " 1", allowGive).automatic(), payload);
        }
    }

    @Test
    void changedConfigIsUsedWhenReevaluatingAnInFlightCandidate() throws IOException {
        CubePilotConfig allowTime = configWith(defaults -> defaults.replace(
                "    # \"time\",", "    \"time\","));
        assertTrue(CommandPolicy.reevaluateForExecution("time set day", allowTime,
                "Steve", List.of("Steve")).automatic());

        CubePilotConfig denyTime = configWith(defaults -> defaults
                .replace("    # \"time\",", "    \"time\",")
                .replace("    # \"gamerule keepInventory true\",", "    \"time set day\","));
        assertFalse(CommandPolicy.reevaluateForExecution("time set day", denyTime,
                "Steve", List.of("Steve")).automatic());
    }

    @Test
    void zeroOrNegativeCommandResultsAreNotReportedAsSuccess() {
        assertFalse(CommandPolicy.successfulExecutionResult(0));
        assertFalse(CommandPolicy.successfulExecutionResult(-1));
        assertTrue(CommandPolicy.successfulExecutionResult(1));
    }

    private CommandPolicy.Decision decide(String command, CubePilotConfig config) {
        return CommandPolicy.evaluate(command, config, "Steve", List.of("Steve"));
    }

    private CubePilotConfig configWith(java.util.function.UnaryOperator<String> transform) throws IOException {
        Path existing = serverRoot.resolve("config/cubepilot/cubepilot.toml");
        Files.deleteIfExists(existing);
        Path configPath = CubePilotConfig.ensureExists(serverRoot);
        String contents = transform.apply(Files.readString(configPath));
        Files.writeString(configPath, contents);
        return CubePilotConfig.load(serverRoot);
    }
}
