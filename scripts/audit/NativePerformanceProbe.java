package io.github.billstark001.latticium.mc;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.FactDependencies;
import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.planning.*;
import java.lang.management.ManagementFactory;
import java.util.*;
import net.minecraft.client.Minecraft;

/** Separate capture/allocation and pure scan measurements; no universal frame-time promise. */
public final class NativePerformanceProbe {
  private NativePerformanceProbe() {}

  public static List<String> measure() {
    var mc = Minecraft.getInstance();
    var feet = mc.player.blockPosition();
    var dimension = MinecraftStateCodec.id(mc.level.dimension().identifier());
    var key =
        new SectionScanner.SectionKey(
            dimension,
            feet.getX() >> 4,
            Math.max(
                mc.level.getMinSectionY() + 1,
                Math.min(mc.level.getMaxSectionY() - 1, (feet.getY() >> 4) + 1)),
            feet.getZ() >> 4);
    int x = key.x() << 4, y = key.y() << 4, z = key.z() << 4;
    String json =
        """
        {"schema":1,"id":"test:performance","scope":"box(%d,%d,%d,%d,%d,%d)",
         "select":{"where":"current(b{minecraft:air,minecraft:cave_air,minecraft:void_air})"},
         "target":{"clear":true},"policy":{"break":"deny"}}
        """
            .formatted(x, y, z, x + 15, y + 15, z + 15);
    var reader = new ProfileReader();
    var profile = reader.bind(reader.read(json), new Compiler(new MinecraftRegistry(mc.level), 16));
    var group =
        new SectionScanner.SectionGroup(
            key, List.of(new SectionScanner.Bounds(dimension, x, y, z, x + 15, y + 15, z + 15)));
    var scanner = new SectionScanner();
    var labels = new ArrayList<String>();
    labels.add(
        "runtime java="
            + System.getProperty("java.runtime.version")
            + " vm="
            + System.getProperty("java.vm.name")
            + " processors="
            + Runtime.getRuntime().availableProcessors()
            + " maxHeapBytes="
            + Runtime.getRuntime().maxMemory());
    var bean = ManagementFactory.getThreadMXBean();
    com.sun.management.ThreadMXBean allocations =
        bean instanceof com.sun.management.ThreadMXBean supported
                && supported.isThreadAllocatedMemorySupported()
            ? supported
            : null;
    if (allocations != null && !allocations.isThreadAllocatedMemoryEnabled())
      allocations.setThreadAllocatedMemoryEnabled(true);
    for (int halo : new int[] {0, 1, 8}) {
      var session = new Host.SessionId();
      var source =
          new MinecraftSectionSource(
              mc,
              session,
              ClientRegistryState.epochs(),
              null,
              Map.of(),
              FactDependencies.of(FactDependencies.Fact.STATE).asBroad());
      var request = new Host.SectionCaptureKey(session, key);
      var captureNanos = new long[30];
      var scanNanos = new long[30];
      var bytes = new long[30];
      for (int i = -10; i < 30; i++) {
        long before =
            allocations == null
                ? 0
                : allocations.getThreadAllocatedBytes(Thread.currentThread().threadId());
        long started = System.nanoTime();
        var captured = source.capture(request, halo);
        long elapsed = System.nanoTime() - started;
        if (!(captured instanceof Host.Capture.Ready ready))
          throw new AssertionError("Benchmark capture unavailable");
        long allocated =
            allocations == null
                ? -1
                : allocations.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before;
        if (i == -10)
          for (int dx = -halo; dx < 16 + halo; dx++)
            for (int dy = -halo; dy < 16 + halo; dy++)
              for (int dz = -halo; dz < 16 + halo; dz++)
                if (ready
                    .snapshot()
                    .facts()
                    .world(new Position(dimension, x + dx, y + dy, z + dz))
                    .map(WorldCell::state)
                    .isEmpty())
                  throw new AssertionError("Benchmark halo contains unavailable cells");
        started = System.nanoTime();
        var mask =
            scanner.scanGroup(group, profile.scope(), profile.select(), ready.snapshot().facts());
        long scanned = System.nanoTime() - started;
        if (mask.unknownCount() != 0)
          throw new AssertionError(
              "Benchmark unavailable cells="
                  + mask.unknownCount()
                  + " key="
                  + key
                  + " loaded="
                  + mc.level.getChunkSource().hasChunk(key.x(), key.z()));
        if (i >= 0) {
          captureNanos[i] = elapsed;
          scanNanos[i] = scanned;
          bytes[i] = allocated;
        }
      }
      Arrays.sort(captureNanos);
      Arrays.sort(scanNanos);
      Arrays.sort(bytes);
      // Reproducible smoke gates catch pathological regressions, not a third-party call deadline.
      if (captureNanos[28] > 500_000_000L
          || scanNanos[28] > 100_000_000L
          || bytes[28] > 64L * 1024 * 1024)
        throw new AssertionError("Native capture/scan smoke gate exceeded");
      labels.add(
          "capture halo="
              + halo
              + " cells="
              + (int) Math.pow(16 + 2 * halo, 3)
              + " warmup=10 samples=30 captureNanosP50="
              + captureNanos[15]
              + " captureNanosP95="
              + captureNanos[28]
              + " scanNanosP95="
              + scanNanos[28]
              + " allocatedBytesP95="
              + bytes[28]
              + " knownCells="
              + (int) Math.pow(16 + 2 * halo, 3));
    }
    return List.copyOf(labels);
  }
}
