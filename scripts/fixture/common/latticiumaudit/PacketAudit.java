package latticiumaudit;

import java.util.ArrayDeque;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;

/**
 * Test-only server ingress guard and bounded diagnostics for the actual received click coordinates.
 */
public final class PacketAudit {
  private static final ArrayDeque<String> RECENT = new ArrayDeque<>();
  private static volatile Region region;
  private static long packets, heightMessages;

  private record Region(BlockPos origin, int width, int depth) {}

  private PacketAudit() {}

  public static synchronized void begin(BlockPos origin, int width, int depth) {
    RECENT.clear();
    packets = 0;
    heightMessages = 0;
    region = new Region(origin, width, depth);
  }

  public static synchronized void received(ServerboundUseItemOnPacket packet) {
    var bounds = region;
    if (bounds == null) return;
    var hit = hitResult(packet);
    var pos = hit.getBlockPos();
    packets++;
    RECENT.addLast(pos + " face=" + hit.getDirection() + " hit=" + hit.getLocation());
    if (RECENT.size() > 32) RECENT.removeFirst();
    if (pos.getY() < bounds.origin.getY() - 1
        || pos.getY() > bounds.origin.getY()
        || pos.getX() < bounds.origin.getX() - 1
        || pos.getX() > bounds.origin.getX() + bounds.width
        || pos.getZ() < bounds.origin.getZ() - 1
        || pos.getZ() > bounds.origin.getZ() + bounds.depth)
      throw new AssertionError(
          "Server received a click outside the owned fixture: " + diagnostics());
  }

  private static net.minecraft.world.phys.BlockHitResult hitResult(
      ServerboundUseItemOnPacket packet) {
    try {
      String accessor = packet.getClass().isRecord() ? "hitResult" : "getHitResult";
      return (net.minecraft.world.phys.BlockHitResult)
          packet.getClass().getMethod(accessor).invoke(packet);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError("Cannot inspect actual server placement packet", failure);
    }
  }

  public static synchronized void buildLimit(boolean above, int limit) {
    if (region == null) return;
    heightMessages++;
    throw new AssertionError(
        "In-bounds fixture received a build-limit message above="
            + above
            + " limit="
            + limit
            + " "
            + diagnostics());
  }

  public static synchronized String diagnostics() {
    return "packets=" + packets + " heightMessages=" + heightMessages + " recent=" + RECENT;
  }

  public static synchronized long[] end() {
    region = null;
    return new long[] {packets, heightMessages};
  }
}
