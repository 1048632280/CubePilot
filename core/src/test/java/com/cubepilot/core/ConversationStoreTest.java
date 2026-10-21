package com.cubepilot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationStoreTest {
    @Test
    void isolatesPlayersAndKeepsRecentContext() {
        ConversationStore store = new ConversationStore();
        store.remember("a", "给我一只皮卡丘", new AssistantReply("已生成", List.of("summon")), "等待执行");
        assertTrue(store.prompt("a", "再生成一只").contains("给我一只皮卡丘"));
        assertEquals("再生成一只", store.prompt("b", "再生成一只"));
    }

    @Test
    void outcomeUpdatesWithoutCreatingAnotherTurn() {
        ConversationStore store = new ConversationStore();
        store.remember("a", "生成钻石剑", new AssistantReply("请手动执行", List.of("give")), "等待执行");
        store.updateLastOutcome("a", "已成功执行");
        assertEquals(1, store.size("a"));
        assertTrue(store.prompt("a", "下一步").contains("已成功执行"));
    }
}
