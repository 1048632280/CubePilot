package com.cubepilot.core;

import java.util.List;

public record ServerSnapshot(
        String minecraftVersion,
        String loader,
        String difficulty,
        int onlinePlayerCount,
        boolean whitelistEnabled,
        List<String> mods) {

    public ServerSnapshot {
        mods = List.copyOf(mods);
    }

    public String toPromptText() {
        String visibleMods = String.join(", ", mods.stream().limit(100).toList());
        if (mods.size() > 100) {
            visibleMods += ", …（另有 " + (mods.size() - 100) + " 个）";
        }
        return "Minecraft 版本：" + minecraftVersion
                + "\n服务端加载器：" + loader
                + "\n当前难度：" + difficulty
                + "\n在线玩家数量：" + onlinePlayerCount
                + "\n白名单：" + (whitelistEnabled ? "启用" : "未启用")
                + "\n已加载 Mod（" + mods.size() + "）：" + visibleMods;
    }
}
