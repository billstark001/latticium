import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.mc.*;
import io.github.billstark001.latticium.planning.*;
import io.github.billstark001.minedriver.api.ProbeContext;
import java.time.Duration;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.Blocks;

/**
 * Actual source-container mutation, with unchanged metadata, followed by the source mod's rebuild.
 */
final class SourceEditsScenario {
  static void verifyWorld(ProbeContext context) throws Exception {
    var at = context.onClient(() -> Minecraft.getInstance().player.blockPosition().offset(2, 2, 0));
    var blueprint = context.onClient(() -> new MemoryBlueprint(at, 1, 1));
    if (blueprint.provider == null) return;
    var session = new Host.SessionId();
    var pos =
        context.onClient(
            () ->
                new Position(
                    MinecraftStateCodec.id(Minecraft.getInstance().level.dimension().identifier()),
                    at.getX(),
                    at.getY(),
                    at.getZ()));
    var source = context.onClient(() -> blueprint.source(session));
    var dirt = new TargetCell.Exact(MinecraftStateCodec.state(Blocks.DIRT.defaultBlockState()));
    var stone = new TargetCell.Exact(MinecraftStateCodec.state(Blocks.STONE.defaultBlockState()));
    try {
      context.await(
          "native source fixture residency",
          Duration.ofSeconds(15),
          () -> context.onClient(() -> source.target(pos, session).equals(dirt)));
      var metadata = context.onClient(blueprint::metadata);
      var before =
          context.onClient(
              () ->
                  source
                      .slice(
                          new SectionScanner.Bounds(
                              pos.dimension(),
                              pos.x(),
                              pos.y(),
                              pos.z(),
                              pos.x(),
                              pos.y(),
                              pos.z()),
                          session)
                      .orElseThrow());
      context.onClient(
          () -> {
            blueprint.editFirstCell(Blocks.STONE.defaultBlockState());
            return null;
          });
      context.await(
          "direct schematic-container edit visible through native source world",
          Duration.ofSeconds(15),
          () -> context.onClient(() -> source.target(pos, session).equals(stone)));
      context.require(
          metadata.equals(context.onClient(blueprint::metadata)),
          "Direct-edit fixture unexpectedly advanced metadata");
      context.require(before.target(pos).equals(dirt), "Old target slice was mutated in place");
      context.require(
          context.onClient(
              () ->
                  source.revision(session).orElseThrow().reliability()
                      == Revisions.Reliability.SHORT_LIVED),
          "Metadata-only source advertised a reusable revision");
      System.out.println(
          "LATTICIUM_AUDIT actual blueprint container edit: metadata unchanged, fresh target changed, immutable old slice retained, revision short-lived");
    } finally {
      context.onClient(
          () -> {
            blueprint.close();
            return null;
          });
    }
  }
}
