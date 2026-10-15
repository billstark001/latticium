package io.github.billstark001.latticium.mc.mixin;

import io.github.billstark001.latticium.mc.ClientRegistryState;
import io.github.billstark001.latticium.mc.ClientRevisions;
import io.github.billstark001.latticium.mc.LatticiumClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records server packets after vanilla applies them, separating updates from client prediction. */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
  @Inject(method = "handleForgetLevelChunk", at = @At("TAIL"))
  private void latticium$chunkUnloaded(
      ClientboundForgetLevelChunkPacket packet, CallbackInfo callback) {
    ClientRevisions.chunkResidencyChanged(packet.pos().x(), packet.pos().z());
  }

  @Inject(method = "handleLevelChunkWithLight", at = @At("TAIL"))
  private void latticium$chunkLoaded(
      ClientboundLevelChunkWithLightPacket packet, CallbackInfo callback) {
    ClientRevisions.chunkResidencyChanged(packet.x(), packet.z());
  }

  @Inject(method = "handleUpdateTags", at = @At("TAIL"))
  private void latticium$tagsUpdated(ClientboundUpdateTagsPacket packet, CallbackInfo callback) {
    ClientRegistryState.tagsUpdated();
  }

  @Inject(method = "handleBlockUpdate", at = @At("TAIL"))
  private void latticium$blockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo callback) {
    LatticiumClient.get().noteServerBlockUpdate(packet.getPos(), packet.getBlockState());
  }

  @Inject(method = "handleChunkBlocksUpdate", at = @At("TAIL"))
  private void latticium$sectionUpdate(
      ClientboundSectionBlocksUpdatePacket packet, CallbackInfo callback) {
    packet.runUpdates((pos, state) -> LatticiumClient.get().noteServerBlockUpdate(pos, state));
  }
}
