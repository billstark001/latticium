package io.github.billstark001.latticium.neoforge;

import io.github.billstark001.latticium.mc.LatticiumClient;
import io.github.billstark001.latticium.mc.ui.ClientConfig;
import io.github.billstark001.latticium.mc.ui.LatticiumHud;
import io.github.billstark001.latticium.mc.ui.UiControls;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;

/** NeoForge physical-client lifecycle. */
@Mod(value = "latticium", dist = Dist.CLIENT)
public final class LatticiumNeoForgeClient {
  public LatticiumNeoForgeClient(IEventBus modBus, ModContainer container) {
    ClientConfig.register();
    container.registerExtensionPoint(
        IConfigScreenFactory.class, (ignored, parent) -> ClientConfig.screen(parent));
    modBus.addListener(this::keys);
    modBus.addListener(this::hud);
    NeoForge.EVENT_BUS.addListener(this::tick);
    NeoForge.EVENT_BUS.addListener(NeoCommands::register);
  }

  private void tick(ClientTickEvent.Post event) {
    LatticiumClient.get().tick(Minecraft.getInstance());
    UiControls.tick(Minecraft.getInstance());
  }

  private void keys(RegisterKeyMappingsEvent event) {
    event.registerCategory(UiControls.CATEGORY);
    UiControls.mappings().forEach(event::register);
  }

  private void hud(RegisterGuiLayersEvent event) {
    event.registerBelow(
        VanillaGuiLayers.CHAT,
        Identifier.fromNamespaceAndPath("latticium", "status"),
        LatticiumHud::extract);
  }
}
