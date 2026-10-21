package com.cubepilot.core;

import java.util.List;

public record AssistantReply(String answer, List<String> commandSuggestions, boolean clarificationRequired) {
    public AssistantReply {
        commandSuggestions = List.copyOf(commandSuggestions);
        if (clarificationRequired && !commandSuggestions.isEmpty()) {
            throw new IllegalArgumentException("澄清回复不能包含命令候选");
        }
    }

    public AssistantReply(String answer, List<String> commandSuggestions) {
        this(answer, commandSuggestions, false);
    }
}
