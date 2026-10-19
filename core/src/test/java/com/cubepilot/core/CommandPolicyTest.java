package com.cubepilot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class CommandPolicyTest {
    private static final List<String> ALLOWED = List.of("give", "effect", "tp", "time");

    @Test
    void addsSlashToAllowedCommand() {
        assertEquals("/give @s minecraft:diamond 1",
                CommandPolicy.validateSuggestion("give @s minecraft:diamond 1", ALLOWED));
    }

    @Test
    void rejectsChainedOrMultilineCommands() {
        assertNull(CommandPolicy.validateSuggestion("give @s diamond 1; op @s", ALLOWED));
        assertNull(CommandPolicy.validateSuggestion("give @s diamond 1\nstop", ALLOWED));
    }

    @Test
    void rejectsDangerousRootsEvenIfAllowlisted() {
        assertNull(CommandPolicy.validateSuggestion("op Player", List.of("op")));
        assertNull(CommandPolicy.validateSuggestion("execute as @a run give @s diamond 1", ALLOWED));
        assertNull(CommandPolicy.validateSuggestion("kill @e", List.of("kill")));
    }

    @Test
    void rejectsCommandsOutsideTheAllowlist() {
        assertNull(CommandPolicy.validateSuggestion("stop", ALLOWED));
        assertNull(CommandPolicy.validateSuggestion("say hello", ALLOWED));
    }
}
