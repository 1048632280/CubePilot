package com.cubepilot.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PromptTemplatesTest {
    @Test
    void classifierDefinesEveryRouteAndDisambiguatesIntent() {
        assertTrue(PromptTemplates.CLASSIFIER.contains("ASK、CONFIG、ANALYZE、COMMAND"));
        assertTrue(PromptTemplates.CLASSIFIER.contains("只问“这个命令是什么意思/怎么用”归 ASK"));
        assertTrue(PromptTemplates.CLASSIFIER.contains("只输出一个标签"));
    }

    @Test
    void sharedOutputContractSeparatesCommandsAndExecutionStatus() {
        assertTrue(PromptTemplates.OUTPUT.contains("\"command_suggestions\":[]"));
        assertTrue(PromptTemplates.OUTPUT.contains("\"clarification_required\":false"));
        assertTrue(PromptTemplates.OUTPUT.contains("成功/失败状态由宿主根据实际执行结果生成"));
    }

    @Test
    void configurationPromptRequiresEvidenceAndAllowsPathHints() {
        assertTrue(PromptTemplates.CONFIG.contains("未提供 Mod ID/路径也应自行查找"));
        assertTrue(PromptTemplates.CONFIG.contains("提供的路径仅作线索"));
        assertTrue(PromptTemplates.CONFIG.contains("不得编造 Mod、路径、键、当前值或行号"));
    }

    @Test
    void commandPromptSeparatesClarificationCandidatesAndHostSafety() {
        assertTrue(PromptTemplates.COMMAND_1_20_1.contains("Minecraft Java 1.20.1"));
        assertTrue(PromptTemplates.COMMAND_1_21_1.contains("Minecraft Java 1.21.1"));
        assertTrue(PromptTemplates.commandFor("1.20.1").equals(PromptTemplates.COMMAND_1_20_1));
        assertTrue(PromptTemplates.commandFor("1.21.1").equals(PromptTemplates.COMMAND_1_21_1));
        assertTrue(PromptTemplates.commandFor("1.22").contains("不得猜测或生成命令"));
        assertTrue(PromptTemplates.commandVerifierFor("1.20.1").contains("目标版本：Minecraft Java 1.20.1"));
        assertTrue(PromptTemplates.commandVerifierFor("1.21.1").contains("目标版本：Minecraft Java 1.21.1"));
    }
}
