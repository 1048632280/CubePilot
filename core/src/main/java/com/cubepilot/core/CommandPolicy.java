package com.cubepilot.core;

import java.util.Locale;
import java.util.Set;

public final class CommandPolicy {
    private static final Set<String> ALWAYS_DENIED = Set.of(
            "stop", "op", "deop", "ban", "ban-ip", "pardon", "pardon-ip", "kick", "whitelist",
            "execute", "function", "schedule", "reload", "debug", "jfr", "perf", "publish",
            "transfer", "fill", "clone", "setblock", "kill", "summon", "data", "forceload");

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
        if (command.isEmpty() || command.length() > 512 || command.contains("\n")
                || command.contains("\r") || command.contains(";") || command.indexOf('\0') >= 0) {
            return null;
        }
        String[] words = command.split("\\s+", 2);
        String root = words[0].toLowerCase(Locale.ROOT);
        if (!root.matches("[a-z0-9_]+") || ALWAYS_DENIED.contains(root) || !allowedRoots.contains(root)) {
            return null;
        }
        return "/" + command;
    }
}
