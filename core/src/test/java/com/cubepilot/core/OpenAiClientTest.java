package com.cubepilot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class OpenAiClientTest {

    @Test
    void parsesGenericCommandVerificationAndRejectsMissingConstraints() {
        String complete = "{\"choices\":[{\"message\":{\"content\":"
                + "\"{\\\"results\\\":[{\\\"command\\\":\\\"give @s diamond\\\","
                + "\\\"complete\\\":true,\\\"missing_requirements\\\":[],\\\"reason\\\":\\\"\\\"}]}\"}}]}";
        assertTrue(OpenAiClient.parseCommandVerification(complete).get(0).complete());

        String incomplete = "{\"choices\":[{\"message\":{\"content\":"
                + "\"{\\\"results\\\":[{\\\"command\\\":\\\"give @s diamond\\\","
                + "\\\"complete\\\":false,\\\"missing_requirements\\\":[\\\"请求条件未表达\\\"],"
                + "\\\"reason\\\":\\\"缺少请求条件\\\"}]}\"}}]}";
        assertFalse(OpenAiClient.parseCommandVerification(incomplete).get(0).complete());
        assertEquals("请求条件未表达", OpenAiClient.parseCommandVerification(incomplete)
                .get(0).missingRequirements().get(0));
    }

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

        assertEquals("模型回复不是完整 JSON，请重试",
                exception.getCause().getMessage());
    }

    @Test
    void clarificationReplyMustNotContainCommandCandidates() {
        AssistantReply reply = OpenAiClient.parseStructuredContent(
                "{\"answer\":\"你要哪一种剑？\",\"command_suggestions\":[],"
                        + "\"clarification_required\":true}");

        assertEquals(true, reply.clarificationRequired());
        assertEquals(0, reply.commandSuggestions().size());
    }

    @Test
    void rejectsCommandsAttachedToClarification() {
        assertThrows(CompletionException.class, () -> OpenAiClient.parseStructuredContent(
                "{\"answer\":\"确认吗？\",\"command_suggestions\":[\"give @s diamond\"],"
                        + "\"clarification_required\":true}"));
    }

    @Test
    void acceptsPlainAnswersAndCommandCodeBlocksFromCompatibleModels() {
        AssistantReply plain = OpenAiClient.parseStructuredContent("这是简短的普通回答。");
        assertEquals("这是简短的普通回答。", plain.answer());
        assertEquals(0, plain.commandSuggestions().size());

        AssistantReply command = OpenAiClient.parseStructuredContent(
                "已准备命令。\n```mcfunction\ngive @s diamond_sword 1\n```");
        assertEquals("已准备命令。", command.answer());
        assertEquals(List.of("give @s diamond_sword 1"), command.commandSuggestions());

        AssistantReply labelled = OpenAiClient.parseStructuredContent("命令：/give @s diamond 1");
        assertEquals(List.of("give @s diamond 1"), labelled.commandSuggestions());
    }

    @Test
    void acceptsAlternativeStructuredFieldNames() {
        AssistantReply reply = OpenAiClient.parseStructuredContent(
                "{\"response\":\"已准备\",\"commands\":\"give @s diamond 2\"}");

        assertEquals("已准备", reply.answer());
        assertEquals(List.of("give @s diamond 2"), reply.commandSuggestions());
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
