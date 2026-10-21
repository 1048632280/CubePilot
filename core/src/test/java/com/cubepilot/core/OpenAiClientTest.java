package com.cubepilot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class OpenAiClientTest {
    @Test
    void parsesCommandsWithEscapedQuotesAndExtraMarkdown() {
        AssistantReply reply = OpenAiClient.parseStructuredContent(
                "结果如下：\n```json\n{\"answer\":\"已准备\","
                        + "\"command_suggestions\":[\"give @p diamond_sword[enchantments={levels:{\\\"minecraft:fortune\\\":5}}] 1\"]}\n```\n");

        assertEquals("已准备", reply.answer());
        assertEquals(1, reply.commandSuggestions().size());
        assertEquals("give @p diamond_sword[enchantments={levels:{\"minecraft:fortune\":5}}] 1",
                reply.commandSuggestions().get(0));
    }

    @Test
    void rejectsMalformedStructuredReplyInsteadOfDisplayingRawJson() {
        CompletionException exception = assertThrows(CompletionException.class,
                () -> OpenAiClient.parseStructuredContent(
                        "{\"answer\":\"缺少结尾\",\"command_suggestions\":[\"give"));

        assertEquals("AI 返回格式无效：未能解析 answer 或 command_suggestions，请重试",
                exception.getCause().getMessage());
    }

    @Test
    void streamingExtractorOnlyEmitsAnswerAndHidesJsonAndCommands() {
        List<String> visible = new ArrayList<>();
        OpenAiClient.AnswerExtractor extractor = new OpenAiClient.AnswerExtractor(visible::add);
        extractor.accept("{\"an");
        extractor.accept("swer\":\"第一段\\n第二");
        extractor.accept("段\\\"\",\"command_suggestions\":[\"kill @a\"]}");

        assertEquals("第一段\n第二段\"", String.join("", visible));
        assertEquals(0, visible.stream().filter(text -> text.contains("kill") || text.contains("command_suggestions"))
                .count());
    }
}
