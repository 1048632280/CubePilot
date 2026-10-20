package com.cubepilot.core;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class CommandPolicy {
    private static final int MAX_COMMAND_LENGTH = 2048;
    private static final Set<String> ALWAYS_DENIED = Set.of(
            "stop", "op", "deop", "ban", "ban-ip", "pardon", "pardon-ip", "kick", "whitelist",
            "execute", "function", "schedule", "reload", "debug", "jfr", "perf", "publish",
            "transfer", "fill", "clone", "setblock", "kill", "summon", "data", "forceload");
    private static final Set<String> DANGEROUS_ITEM_IDS = Set.of(
            "minecraft:command_block", "minecraft:chain_command_block", "minecraft:repeating_command_block",
            "minecraft:structure_block", "minecraft:jigsaw", "command_block", "chain_command_block",
            "repeating_command_block", "structure_block", "jigsaw");
    private static final Set<String> DANGEROUS_NBT_KEYS = Set.of(
            "command", "commandblockoutput", "command_block_output", "entitytag", "passengers",
            "entity_data", "block_entity_data", "minecraft:entity_data", "minecraft:block_entity_data");

    private CommandPolicy() {
    }

    public static String validateSuggestion(String rawCommand, java.util.List<String> allowedRoots) {
        if (rawCommand == null) {
            return null;
        }
        String command = rawCommand.trim();
        while (command.startsWith("/")) {
            command = command.substring(1).trim();
        }
        if (command.isEmpty() || command.length() > MAX_COMMAND_LENGTH || command.contains("\n")
                || command.contains("\r") || command.contains(";") || command.indexOf('\0') >= 0) {
            return null;
        }
        String[] words = command.split("\\s+", 2);
        String root = words[0].toLowerCase(Locale.ROOT);
        if (!root.matches("[a-z0-9_]+") || ALWAYS_DENIED.contains(root) || !allowedRoots.contains(root)) {
            return null;
        }
        if (!validatePayload(command, root)) {
            return null;
        }
        return "/" + command;
    }

    private static boolean validatePayload(String command, String root) {
        if (!hasBalancedPayload(command)) {
            return false;
        }
        if (!root.equals("give")) {
            return true;
        }
        String lower = command.toLowerCase(Locale.ROOT);
        for (String itemId : DANGEROUS_ITEM_IDS) {
            if (lower.contains(itemId)) {
                return false;
            }
        }
        for (String key : DANGEROUS_NBT_KEYS) {
            if (lower.matches(".*(?:[\\{\\[,\\s])" + Pattern.quote(key) + "\\s*[:=].*")) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasBalancedPayload(String command) {
        int braces = 0;
        int brackets = 0;
        int depth = 0;
        boolean quoted = false;
        char quote = 0;
        for (int index = 0; index < command.length(); index++) {
            char current = command.charAt(index);
            if ((current == '\'' || current == '"') && (index == 0 || command.charAt(index - 1) != '\\')) {
                if (!quoted) {
                    quoted = true;
                    quote = current;
                } else if (quote == current) {
                    quoted = false;
                }
                continue;
            }
            if (quoted) {
                continue;
            }
            if (current == '{') {
                braces++;
                depth++;
            } else if (current == '}') {
                if (--braces < 0) {
                    return false;
                }
                depth--;
            } else if (current == '[') {
                brackets++;
                depth++;
            } else if (current == ']') {
                if (--brackets < 0) {
                    return false;
                }
                depth--;
            }
            if (depth > 8) {
                return false;
            }
        }
        return !quoted && braces == 0 && brackets == 0;
    }
}
