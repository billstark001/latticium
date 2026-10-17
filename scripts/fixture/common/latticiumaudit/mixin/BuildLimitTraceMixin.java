package latticiumaudit.mixin;

import latticiumaudit.PacketAudit;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
abstract class BuildLimitTraceMixin {
  @Inject(method = "sendBuildLimitMessage", at = @At("HEAD"))
  private void audit(boolean above, int limit, CallbackInfo callback) {
    PacketAudit.buildLimit(above, limit);
  }
}
