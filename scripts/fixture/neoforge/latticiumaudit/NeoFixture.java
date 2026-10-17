package latticiumaudit;

import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.RegisterEvent;

@Mod("latticiumaudit")
public final class NeoFixture {
  public NeoFixture(IEventBus bus) {
    bus.addListener(NeoFixture::register);
  }

  private static void register(RegisterEvent event) {
    event.register(Registries.BLOCK, AuditBlocks.STATEFUL, () -> AuditBlocks.STATEFUL_BLOCK);
    event.register(Registries.BLOCK, AuditBlocks.FAILING, () -> AuditBlocks.FAILING_BLOCK);
    event.register(Registries.ITEM, AuditBlocks.STATEFUL, () -> AuditBlocks.STATEFUL_ITEM);
    event.register(Registries.ITEM, AuditBlocks.FAILING, () -> AuditBlocks.FAILING_ITEM);
  }
}
