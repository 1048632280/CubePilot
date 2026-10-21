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
        assertTrue(PromptTemplates.COMMAND.contains("answer 只写“请手动执行：”"));
        assertTrue(PromptTemplates.COMMAND.contains("命令数组为空"));
        assertTrue(PromptTemplates.COMMAND.contains("Brigadier 校验决定自动执行或显示可点击建议"));
        assertTrue(PromptTemplates.COMMAND.contains("没有找到这个物品，请重新输入"));
        assertTrue(PromptTemplates.COMMAND.contains("不得把原始命令或整段 NBT 展示给玩家"));
        assertTrue(PromptTemplates.COMMAND.contains("点击组件填充完整命令"));
    }
}
