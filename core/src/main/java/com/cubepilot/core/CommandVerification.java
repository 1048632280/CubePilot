package com.cubepilot.core;

import java.util.List;

/** 独立语义核验结果；只有完整且无待确认要求时才可继续交给宿主安全策略。 */
public record CommandVerification(String command, boolean complete, List<String> missingRequirements, String reason) {
    public CommandVerification {
        command = command == null ? "" : command;
        missingRequirements = List.copyOf(missingRequirements);
        reason = reason == null ? "" : reason;
        if (complete && !missingRequirements.isEmpty()) {
            throw new IllegalArgumentException("完整核验不能包含缺失要求");
        }
    }
}
