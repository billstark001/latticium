package io.github.billstark001.latticium.mc;

import io.github.billstark001.latticium.planning.Host;
import net.minecraft.client.Minecraft;

/** Registry/tag bindings are invalid after a server tag update, including a datapack reload. */
public final class ClientRegistryState {
  private static volatile long registryRevision;

  private ClientRegistryState() {}

  /**
   * Only the registry revision is currently tracked; other fact epochs remain conservative zero.
   */
  public static Host.Epochs epochs() {
    return new Host.Epochs(registryRevision, 0, 0, 0, 0, 0);
  }

  /** Called on the client thread after vanilla has installed the updated tags. */
  public static void tagsUpdated() {
    var minecraft = Minecraft.getInstance();
    if (!minecraft.isSameThread())
      throw new IllegalStateException("Registry update on client thread");
    registryRevision++;
    LatticiumClient.get().registryReloaded(minecraft);
  }
}
