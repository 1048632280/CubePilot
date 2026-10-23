package com.cubepilot.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ServerSnapshotTest {
    @Test
    void promptIncludesSafeSettingsAndPathsWhileStatusStaysConcise() {
        ServerSnapshot snapshot = new ServerSnapshot(Instant.parse("2026-10-20T00:00:00Z"),
                "1.21.1", "Fabric", "Hard", 3, true,
                Map.of("online-mode", "true"), List.of("config/example.toml"), List.of("fabric-api 0.1"));

        String prompt = snapshot.toPromptText();
        String status = snapshot.toStatusText();

        assertTrue(prompt.contains("online-mode=true"));
        assertTrue(prompt.contains("config/example.toml"));
        assertTrue(prompt.contains("只读配置/日志/崩溃文件清单"));
        assertTrue(status.contains("在线玩家数量：3"));
        assertFalse(status.contains("config/example.toml"));
        assertFalse(status.contains("online-mode=true"));
    }

    @Test
    void promptStatesWhenFileInventoryWasTruncated() {
        ServerSnapshot snapshot = new ServerSnapshot(Instant.parse("2026-10-20T00:00:00Z"),
                "1.21.1", "Fabric", "normal", 1, true, Map.of(),
                List.of("config/example.toml"), true, List.of());

        assertTrue(snapshot.toPromptText().contains("清单已达到扫描边界"));
    }
}
