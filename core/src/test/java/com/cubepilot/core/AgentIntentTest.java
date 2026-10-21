package com.cubepilot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AgentIntentTest {
    @Test
    void acceptsKnownValuesCaseInsensitivelyAndDefaultsToAsk() {
        assertEquals(AgentIntent.CONFIG, AgentIntent.from(" config "));
        assertEquals(AgentIntent.ANALYZE, AgentIntent.from("ANALYZE"));
        assertEquals(AgentIntent.COMMAND, AgentIntent.from("command"));
        assertEquals(AgentIntent.ASK, AgentIntent.from("unknown"));
        assertEquals(AgentIntent.ASK, AgentIntent.from(null));
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
    void malformedClassifierPayloadDoesNotBlockRequestRouting() {
        assertEquals(AgentIntent.ASK, OpenAiClient.parseIntent("{}"));
    }
}
