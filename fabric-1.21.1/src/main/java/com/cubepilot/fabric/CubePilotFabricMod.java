package com.cubepilot.fabric;

import com.cubepilot.minecraft.CubePilotCommands;
import com.cubepilot.core.CubePilotAgent;
import com.cubepilot.core.ModInfo;
import java.io.IOException;
import java.util.List;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.loader.api.FabricLoader;

public final class CubePilotFabricMod implements ModInitializer {
    @Override
    public void onInitialize() {
        try {
            CubePilotAgent.ensureConfig();
        } catch (IOException | IllegalArgumentException exception) {
            System.err.println("[CubePilot] 无法创建自身配置文件：" + exception.getMessage());
        }
        List<ModInfo> mods = FabricLoader.getInstance().getAllMods().stream()
                .map(container -> new ModInfo(container.getMetadata().getId(), container.getMetadata().getName(),
                        container.getMetadata().getVersion().getFriendlyString()))
                .sorted(java.util.Comparator.comparing(ModInfo::id))
                .toList();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                CubePilotCommands.register(dispatcher, "Fabric", mods));
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, player, parameters) ->
                !CubePilotCommands.onChatMessage(player.getServer(), player, message.getContent(),
                        "Fabric", mods));
    }
}
