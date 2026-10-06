import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.FactDependencies;
import io.github.billstark001.latticium.dsl.FactDependencies.Fact;
import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.mc.*;
import io.github.billstark001.latticium.planning.*;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;

/** Fact projection, dense-window capture and allocation measurements in a live client. */
final class CaptureAudit {
  static void run(AuditProbe probe, Minecraft minecraft, Position position) throws Exception {
    var session = new Host.SessionId();
    var epochs = new Host.Epochs(0, 0, 0, 0, 0, 0);
    var key =
        new Host.SectionCaptureKey(
            session,
            new SectionScanner.SectionKey(
                position.dimension(), position.x() >> 4, position.y() >> 4, position.z() >> 4));
    int[] targetCalls = {0};
    Host.TargetSource targets =
        (pos, s) -> {
          targetCalls[0]++;
          return new TargetCell.DontCare();
        };
    var none =
        new MinecraftSectionSource(
            minecraft, session, epochs, targets, Map.of(), FactDependencies.NONE);
    var state =
        new MinecraftSectionSource(
            minecraft, session, epochs, targets, Map.of(), FactDependencies.of(Fact.STATE));
    var all = new MinecraftSectionSource(minecraft, session, epochs, targets, Map.of());
    var noFacts = ((Host.Capture.Ready) none.capture(key, 0)).snapshot().facts();
    probe.check(
        noFacts.world(position).isEmpty()
            && noFacts.player().isEmpty()
            && noFacts.inventory().isEmpty(),
        "Unused columns captured");
    probe.check(targetCalls[0] == 0, "Target-free capture called provider");
    var stateFacts = ((Host.Capture.Ready) state.capture(key, 0)).snapshot().facts();
    var cell = stateFacts.world(position).orElseThrow();
    probe.check(
        cell.state() != null
            && cell.biome() == null
            && cell.fluid() == null
            && cell.light() == null
            && cell.solid() == null,
        "State-only capture requested other columns");
    probe.check(targetCalls[0] == 0, "State-only capture called provider");
    var allFacts = ((Host.Capture.Ready) all.capture(key, 0)).snapshot().facts();
    probe.check(targetCalls[0] == 4096, "Full capture target count incorrect");
    var compiler = new Compiler(new MinecraftRegistry(minecraft.level), 16);
    var select =
        compiler.compile("current(b{minecraft:air}) | current(b{minecraft:stone})", SetType.POS);
    var scope = compiler.compile("all()", SetType.POS);
    int x = key.section().x() << 4, y = key.section().y() << 4, z = key.section().z() << 4;
    var bounds = new SectionScanner.Bounds(position.dimension(), x, y, z, x + 15, y + 15, z + 15);
    var scanner = new SectionScanner();
    var fullMask = scanner.scanSection(bounds, key.section(), scope, select, allFacts);
    var requestedMask = scanner.scanSection(bounds, key.section(), scope, select, stateFacts);
    probe.check(fullMask.equals(requestedMask), "Requested columns changed scan masks");
    var unloaded = new Position(position.dimension(), 1_000_000, position.y(), 1_000_000);
    probe.check(
        !minecraft.level.getChunkSource().hasChunk(unloaded.x() >> 4, unloaded.z() >> 4),
        "Probe chunk unexpectedly loaded");
    var unavailable =
        ((Host.Capture.Ready) state.captureAround(session, unloaded, 0)).snapshot().facts();
    probe.check(
        compiler.compile("current(b{stone})", SetType.POS).at(unavailable, unloaded)
            == Truth.UNKNOWN,
        "Unloaded world became known");
    probe.check(
        compiler.compile("x=1000000 | current(b{stone})", SetType.POS).at(unavailable, unloaded)
            == Truth.TRUE,
        "Coordinate branch became Unknown in unloaded chunk");
    int[] batchCalls = {0}, pointCalls = {0};
    Host.TargetSource batched =
        new Host.TargetSource() {
          public TargetCell target(Position p, Host.SessionId s) {
            pointCalls[0]++;
            return new TargetCell.Clear();
          }

          public java.util.Optional<TargetSlice> slice(
              SectionScanner.Bounds bounds, Host.SessionId s) {
            batchCalls[0]++;
            return java.util.Optional.of(
                TargetSlice.capture(s, bounds, p -> new TargetCell.Clear()));
          }
        };
    var batchedFacts =
        ((Host.Capture.Ready)
                new MinecraftSectionSource(
                        minecraft,
                        session,
                        epochs,
                        batched,
                        Map.of(),
                        FactDependencies.of(Fact.TARGET))
                    .capture(key, 1))
            .snapshot()
            .facts();
    probe.check(
        batchCalls[0] == 1
            && pointCalls[0] == 0
            && batchedFacts.target(position) instanceof TargetCell.Clear,
        "Batch capture did not replace point reads");
    probe.pass("optional halo target slice captured once without point reads");
    probe.pass(
        "fact column projection, target-free zero provider calls, matching scan masks, and per-fact Unknown in unloaded chunks");
    compiler.targetAvailable(true);
    for (var expression :
        List.of(
            "sphere(player,16) & current(b{air})",
            "offset(16,0,0,current(b{air})) | offset(-16,0,0,biome(m{plains}))",
            "adjacent(current(b{air})) & surface()",
            "offset(1,0,0,adjacent(light(0..15))) | !fluid(f{water})",
            "offset(0,16,0,target(b{stone})) | current(b{air})",
            "offset(3,0,1,surface())")) {
      var bound = compiler.compile(expression, SetType.POS);
      var exact =
          new MinecraftSectionSource(
              minecraft, session, epochs, targets, Map.of(), bound.dependencies());
      var full = ((Host.Capture.Ready) all.capture(key, bound.radius())).snapshot().facts();
      var projected = ((Host.Capture.Ready) exact.capture(key, bound.radius())).snapshot().facts();
      probe.check(
          scanner
              .scanSection(bounds, key.section(), scope, bound, full)
              .equals(scanner.scanSection(bounds, key.section(), scope, bound, projected)),
          "Exact column offsets changed scan masks: " + expression);
    }
    var edge = new Position(position.dimension(), Integer.MAX_VALUE, position.y(), 0);
    var edgeFacts =
        ((Host.Capture.Ready) state.captureAround(session, edge, 16)).snapshot().facts();
    probe.check(
        compiler.compile("x=2147483647 | current(b{stone})", SetType.POS).at(edgeFacts, edge)
            == Truth.TRUE,
        "Clipped integer-edge capture lost coordinate branch");
    probe.pass(
        "exact per-column offset captures match full-halo masks for six spatial expressions; integer-edge halo clips without losing known coordinate facts");
    benchmark(probe, "all", all, key);
    benchmark(probe, "state", state, key);
    benchmark(probe, "coordinate", none, key);
    benchmark(probe, "state halo16", state, key, 16);
    benchmark(probe, "all halo16", all, key, 16);
  }

  static void benchmark(
      AuditProbe probe, String label, MinecraftSectionSource source, Host.SectionCaptureKey key)
      throws Exception {
    benchmark(probe, label, source, key, 0);
  }

  static void benchmark(
      AuditProbe probe,
      String label,
      MinecraftSectionSource source,
      Host.SectionCaptureKey key,
      int halo)
      throws Exception {
    for (int i = 0; i < 3; i++) source.capture(key, halo);
    var bean =
        (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
    long id = Thread.currentThread().threadId();
    long bytes = bean.getThreadAllocatedBytes(id), start = System.nanoTime();
    for (int i = 0; i < 10; i++) source.capture(key, halo);
    double millis = (System.nanoTime() - start) / 10_000_000.0;
    long allocation = (bean.getThreadAllocatedBytes(id) - bytes) / 10;
    probe.pass(
        "capture "
            + label
            + ": "
            + String.format(java.util.Locale.ROOT, "%.3f", millis)
            + " ms/section, "
            + allocation
            + " allocated bytes/section (10 samples, 3 warmups)");
  }
}
