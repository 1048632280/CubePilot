package com.cubepilot.fabric;

import com.cubepilot.minecraft.CubePilotCommands;
import java.util.List;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;

public final class CubePilotFabricMod implements ModInitializer {
    @Override
    public void onInitialize() {
        List<String> mods = FabricLoader.getInstance().getAllMods().stream()
                .map(container -> container.getMetadata().getId() + " "
                        + container.getMetadata().getVersion().getFriendlyString())
                .sorted()
                .toList();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                CubePilotCommands.register(dispatcher, "Fabric", mods));
    }
}
