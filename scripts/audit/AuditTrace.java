import io.github.billstark001.latticium.dsl.Model.Position;
import io.github.billstark001.latticium.mc.ClientJob;
import io.github.billstark001.latticium.mc.LatticiumClient;
import io.github.billstark001.latticium.planning.ServerUpdates;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** Bounded transition history, retained only by development probes for failure diagnosis. */
final class AuditTrace {
  private final Path output;
  private final ArrayDeque<String> entries = new ArrayDeque<>();
  private String previous;

  AuditTrace(Path output) {
    this.output = output;
  }

  void observe(String label, ClientJob job, Position position) throws Exception {
    if (job == null || position == null) return;
    var minecraft = Minecraft.getInstance();
    var field = LatticiumClient.class.getDeclaredField("serverUpdates");
    field.setAccessible(true);
    var update = ((ServerUpdates) field.get(LatticiumClient.get())).get(position);
    var nativePos = new BlockPos(position.x(), position.y(), position.z());
    String state =
        label
            + " "
            + job.status()
            + " blocked="
            + job.blocked()
            + " visible="
            + minecraft.level.getBlockState(nativePos)
            + " server="
            + update;
    if (state.equals(previous)) return;
    previous = state;
    entries.addLast(minecraft.level.getGameTime() + " " + state);
    if (entries.size() > 128) entries.removeFirst();
    Files.writeString(output.resolve("action-trace.txt"), String.join("\n", entries) + "\n");
  }
}
