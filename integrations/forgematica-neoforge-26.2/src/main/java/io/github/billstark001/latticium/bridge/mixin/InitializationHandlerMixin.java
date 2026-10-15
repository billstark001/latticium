package io.github.billstark001.latticium.bridge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import fi.dy.masa.malilib.event.InitializationHandler;
import fi.dy.masa.malilib.interfaces.IInitializationHandler;
import org.spongepowered.asm.mixin.Mixin;

/** NeoForge constructs mods in parallel; MaFgLib 0.5.3 uses an unsynchronized contains/add pair. */
@Mixin(value = InitializationHandler.class, remap = false)
abstract class InitializationHandlerMixin {
  @WrapMethod(method = "registerInitializationHandler")
  private void latticium$serializeRegistration(
      IInitializationHandler handler, Operation<Void> original) {
    synchronized (this) {
      original.call(handler);
    }
  }
}
