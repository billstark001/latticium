package io.github.billstark001.latticium.neoforge;

import io.github.billstark001.latticium.mc.LatticiumClient;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** NeoForge physical-client lifecycle. */
@Mod(value = "latticium", dist = Dist.CLIENT)
public final class LatticiumNeoForgeClient {
  public LatticiumNeoForgeClient() {
    NeoForge.EVENT_BUS.addListener(this::tick);
    NeoForge.EVENT_BUS.addListener(NeoCommands::register);
  }

  private void tick(ClientTickEvent.Post event) {
    LatticiumClient.get().tick(Minecraft.getInstance());
  }
}
