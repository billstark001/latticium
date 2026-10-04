package io.github.billstark001.latticium.mc.mixin;

import io.github.billstark001.latticium.mc.LatticiumClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records server packets after vanilla applies them, separating updates from client prediction. */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
  @Inject(method = "handleBlockUpdate", at = @At("TAIL"))
  private void latticium$blockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo callback) {
    LatticiumClient.get().noteServerBlockUpdate(packet.getPos());
  }

  @Inject(method = "handleChunkBlocksUpdate", at = @At("TAIL"))
  private void latticium$sectionUpdate(
      ClientboundSectionBlocksUpdatePacket packet, CallbackInfo callback) {
    packet.runUpdates((pos, state) -> LatticiumClient.get().noteServerBlockUpdate(pos));
  }
}
