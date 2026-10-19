package com.cubepilot.forge;

import com.cubepilot.minecraft.CubePilotCommands;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

@Mod(CubePilotForgeMod.MOD_ID)
public final class CubePilotForgeMod {
    public static final String MOD_ID = "cubepilot";

    public CubePilotForgeMod() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        List<String> mods = ModList.get().getMods().stream()
                .map(mod -> mod.getModId() + " " + mod.getVersion())
                .sorted(Collectors.toList());
        CubePilotCommands.register(event.getDispatcher(), "Forge", mods);
    }
}
