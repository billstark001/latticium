package io.github.billstark001.latticium.mc.mixin;

import io.github.billstark001.latticium.mc.LatticiumClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Local predictions invalidate reads too; only packet hooks provide confirmation evidence. */
@Mixin(Level.class)
abstract class LevelRevisionMixin {
  @Inject(
      method =
          "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
      at = @At("RETURN"))
  private void latticium$worldRevision(
      BlockPos pos,
      BlockState state,
      int flags,
      int recursion,
      CallbackInfoReturnable<Boolean> callback) {
    var minecraft = Minecraft.getInstance();
    if (callback.getReturnValueZ() && minecraft.isSameThread() && (Object) this == minecraft.level)
      LatticiumClient.get().noteServerBlockUpdate(pos);
  }
}
