package com.cubepilot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AgentIntentTest {
    @Test
    void acceptsKnownValuesAndKeepsInvalidValuesDistinctFromAsk() {
        assertEquals(AgentIntent.CONFIG, AgentIntent.from(" config "));
        assertEquals(AgentIntent.ANALYZE, AgentIntent.from("ANALYZE"));
        assertEquals(AgentIntent.COMMAND, AgentIntent.from("command"));
        assertEquals(AgentIntent.UNKNOWN, AgentIntent.from("unknown"));
        assertEquals(AgentIntent.UNKNOWN, AgentIntent.from(null));
    }

    @Test
    void parsesJsonAndPlainClassifierResponses() {
        String valid = "{\"choices\":[{\"message\":{\"content\":\"{\\\"intent\\\":\\\"COMMAND\\\"}\"}}]}";
        assertEquals(AgentIntent.COMMAND, OpenAiClient.parseIntent(valid));

        String plain = "{\"choices\":[{\"message\":{\"content\":\"ASK\\n\"}}]}";
        assertEquals(AgentIntent.ASK, OpenAiClient.parseIntent(plain));

        String fenced = "{\"choices\":[{\"message\":{\"content\":\"```json\\n{\\\"intent\\\":\\\"CONFIG\\\"}\\n```\"}}]}";
        assertEquals(AgentIntent.CONFIG, OpenAiClient.parseIntent(fenced));

        String segmented = "{\"choices\":[{\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"COMMAND\"}]}}]}";
        assertEquals(AgentIntent.COMMAND, OpenAiClient.parseIntent(segmented));
    }

    @Test
    void malformedClassifierPayloadIsNotSilentlyRoutedAsAsk() {
        assertEquals(AgentIntent.UNKNOWN, OpenAiClient.parseIntent("{}"));
    }
}
