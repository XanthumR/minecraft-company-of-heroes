package com.strategywar.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.strategywar.StrategyWar;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

@Mod(value = StrategyWar.MODID, dist = Dist.CLIENT)
public class StrategyWarClient {
    public static final KeyMapping TOGGLE = new KeyMapping(
            "key.strategywar.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, "key.categories.strategywar");

    public StrategyWarClient(IEventBus modBus) {
        modBus.addListener((RegisterKeyMappingsEvent e) -> e.register(TOGGLE));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> {
            Minecraft mc = Minecraft.getInstance();
            // While StrategyScreen is open it handles the key itself (screens swallow keymapping clicks).
            while (TOGGLE.consumeClick()) {
                if (mc.player != null && mc.screen == null) mc.setScreen(new StrategyScreen());
            }
        });
    }
}
