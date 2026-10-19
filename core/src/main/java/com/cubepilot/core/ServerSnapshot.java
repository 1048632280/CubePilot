package com.cubepilot.core;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record ServerSnapshot(
        Instant capturedAt,
        String minecraftVersion,
        String loader,
        String difficulty,
        int onlinePlayerCount,
        boolean whitelistEnabled,
        Map<String, String> serverSettings,
        List<String> configurationFiles,
        List<String> mods) {

    public ServerSnapshot {
        serverSettings = Collections.unmodifiableMap(new TreeMap<>(serverSettings));
        configurationFiles = List.copyOf(configurationFiles);
        mods = List.copyOf(mods);
    }

    public String toPromptText() {
        String visibleMods = String.join(", ", mods.stream().limit(100).toList());
        if (mods.size() > 100) {
            visibleMods += ", …（另有 " + (mods.size() - 100) + " 个）";
        }
        StringBuilder text = new StringBuilder("快照采集时间：").append(capturedAt)
                .append("\nMinecraft 版本：").append(minecraftVersion)
                .append("\n服务端加载器：").append(loader)
                .append("\n当前难度：").append(difficulty)
                .append("\n在线玩家数量：").append(onlinePlayerCount)
                .append("\n白名单：").append(whitelistEnabled ? "启用" : "未启用")
                .append("\n已加载 Mod（").append(mods.size()).append("）：").append(visibleMods);
        text.append("\nserver.properties 安全设置（仅白名单字段）：");
        if (serverSettings.isEmpty()) {
            text.append("\n（没有可读取的白名单设置）");
        } else {
            serverSettings.forEach((key, value) -> text.append("\n").append(key).append("=").append(value));
        }
        text.append("\n只读配置/日志/崩溃文件清单（仅路径，不含内容）：");
        if (configurationFiles.isEmpty()) {
            text.append("\n（没有发现白名单文本文件）");
        } else {
            configurationFiles.stream().limit(80).forEach(path -> text.append("\n").append(path));
            if (configurationFiles.size() > 80) {
                text.append("\n（其余文件未纳入快照）");
            }
        }
        return text.toString();
    }

    public String toStatusText() {
        return "Minecraft 版本：" + minecraftVersion
                + "\n服务端加载器：" + loader
                + "\n当前难度：" + difficulty
                + "\n在线玩家数量：" + onlinePlayerCount
                + "\n白名单：" + (whitelistEnabled ? "启用" : "未启用")
                + "\n已加载 Mod：" + mods.size() + " 个";
    }
}
