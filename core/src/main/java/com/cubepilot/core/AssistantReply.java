package com.cubepilot.core;

import java.util.List;

public record AssistantReply(String answer, List<String> commandSuggestions) {
    public AssistantReply {
        commandSuggestions = List.copyOf(commandSuggestions);
    }
}
