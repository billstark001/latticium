package io.github.billstark001.latticium.mc;

import io.github.billstark001.latticium.planning.Host;
import net.minecraft.client.Minecraft;

/** Registry/tag bindings are invalid after a server tag update, including a datapack reload. */
public final class ClientRegistryState {
  private static volatile long registryRevision;

  private ClientRegistryState() {}

  /** Coarse source revisions for diagnostics. Dependency-qualified proofs govern native reuse. */
  public static Host.Epochs epochs() {
    return ClientRevisions.epochs(registryRevision);
  }

  /** Called on the client thread after vanilla has installed the updated tags. */
  public static void tagsUpdated() {
    var minecraft = Minecraft.getInstance();
    if (!minecraft.isSameThread())
      throw new IllegalStateException("Registry update on client thread");
    registryRevision++;
    ClientRevisions.LEDGER.sample(
        new io.github.billstark001.latticium.planning.Revisions.Key(
            io.github.billstark001.latticium.planning.Revisions.Domain.REGISTRY, "registry"),
        registryRevision,
        io.github.billstark001.latticium.planning.Revisions.Reliability.VERSIONED);
    LatticiumClient.get().registryReloaded(minecraft);
  }
}
