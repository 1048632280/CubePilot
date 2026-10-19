package com.cubepilot.neoforge;

import com.cubepilot.minecraft.CubePilotCommands;
import java.util.List;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@Mod(CubePilotNeoForgeMod.MOD_ID)
public final class CubePilotNeoForgeMod {
    public static final String MOD_ID = "cubepilot";

    public CubePilotNeoForgeMod() {
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        List<String> mods = ModList.get().getMods().stream()
                .map(mod -> mod.getModId() + " " + mod.getVersion())
                .sorted()
                .toList();
        CubePilotCommands.register(event.getDispatcher(), "NeoForge", mods);
    }
}
