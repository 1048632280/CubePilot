package com.cubepilot.fabric;

import com.cubepilot.minecraft.CubePilotCommands;
import com.cubepilot.core.ModInfo;
import java.util.List;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;

public final class CubePilotFabricMod implements ModInitializer {
    @Override
    public void onInitialize() {
        List<ModInfo> mods = FabricLoader.getInstance().getAllMods().stream()
                .map(container -> new ModInfo(container.getMetadata().getId(), container.getMetadata().getName(),
                        container.getMetadata().getVersion().getFriendlyString()))
                .sorted(java.util.Comparator.comparing(ModInfo::id))
                .toList();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                CubePilotCommands.register(dispatcher, "Fabric", mods));
    }
}
