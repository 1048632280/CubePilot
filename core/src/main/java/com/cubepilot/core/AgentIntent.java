package com.cubepilot.core;

/** 统一入口的请求意图；这些值不是公开 Minecraft 二级指令。 */
public enum AgentIntent {
    ASK,
    CONFIG,
    ANALYZE,
    COMMAND,
    UNKNOWN;

    public static AgentIntent from(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        return switch (value.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "ASK" -> ASK;
            case "CONFIG" -> CONFIG;
            case "ANALYZE" -> ANALYZE;
            case "COMMAND" -> COMMAND;
            default -> UNKNOWN;
        };
    }
}
