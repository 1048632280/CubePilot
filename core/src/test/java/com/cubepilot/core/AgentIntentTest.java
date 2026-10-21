package com.cubepilot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CompletionException;
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
    void parsesClassifierResponseAndRejectsMalformedIntent() {
        String valid = "{\"choices\":[{\"message\":{\"content\":\"{\\\"intent\\\":\\\"COMMAND\\\"}\"}}]}";
        assertEquals(AgentIntent.COMMAND, OpenAiClient.parseIntent(valid));

        String invalid = "{\"choices\":[{\"message\":{\"content\":\"{\\\"intent\\\":\\\"NOT_A_ROUTE\\\"}\"}}]}";
        assertThrows(CompletionException.class, () -> OpenAiClient.parseIntent(invalid));
    }
}
