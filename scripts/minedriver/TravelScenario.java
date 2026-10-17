import io.github.billstark001.latticium.dsl.FactDependencies;
import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.mc.*;
import io.github.billstark001.latticium.planning.*;
import io.github.billstark001.minedriver.api.*;
import java.time.Duration;
import java.util.Map;
import net.minecraft.client.Minecraft;

/** Actual dimension travel must discard old job/capture lifetimes even after returning. */
public final class TravelScenario implements Scenario {
  @Override
  public void run(ProbeContext context) {
    context.call("world.create", Map.of("flat", true, "seed", "20261016", "timeoutMillis", 150000));
    verifyWorld(context);
    context.call("world.disconnect");
  }

  static void verifyWorld(ProbeContext context) {
    var original =
        context.onClient(
            () -> {
              var player = Minecraft.getInstance().player;
              return Map.of("x", player.getX(), "y", player.getY(), "z", player.getZ());
            });
    var uuid = context.onClient(() -> Minecraft.getInstance().player.getUUID().toString());
    var position =
        context.onClient(
            () -> {
              var mc = Minecraft.getInstance();
              var at = mc.player.blockPosition();
              return new Position(
                  MinecraftStateCodec.id(mc.level.dimension().identifier()),
                  at.getX(),
                  at.getY(),
                  at.getZ());
            });
    var session = new Host.SessionId();
    var source =
        context.onClient(
            () ->
                new MinecraftSectionSource(
                    Minecraft.getInstance(),
                    session,
                    ClientRegistryState.epochs(),
                    null,
                    Map.of(),
                    FactDependencies.of(FactDependencies.Fact.STATE)));
    var job =
        context.onClient(
            () -> {
              var current =
                  new ClientJob(
                      Minecraft.getInstance(),
                      new Host.SessionId(),
                      """
          {"schema":1,"id":"test:dimension_lifetime","scope":"box(%d,%d,%d,%d,%d,%d)",
           "select":{"where":"all()"},"target":{"clear":true},"policy":{"break":"selected","refresh":"manual"}}
          """
                          .formatted(
                              position.x(),
                              position.y(),
                              position.z(),
                              position.x(),
                              position.y(),
                              position.z()),
                      null,
                      Map.of());
              current.pause();
              return current;
            });
    context.require(
        context.onClient(
            () -> source.captureAround(session, position, 0) instanceof Host.Capture.Ready),
        "Pre-travel capture unavailable");
    try {
      context.call(
          "world.command",
          Map.of("command", "execute in minecraft:the_nether run tp " + uuid + " 0 100 0"));
      context.await(
          "real Nether dimension transfer",
          Duration.ofSeconds(45),
          () ->
              context.onClient(
                  () ->
                      Minecraft.getInstance()
                          .level
                          .dimension()
                          .identifier()
                          .getPath()
                          .equals("the_nether")));
      context.onClient(
          () -> {
            job.tick();
            return null;
          });
      context.require(
          context.onClient(() -> job.isSettled() && job.snapshot().submittedActions() == 0),
          "Old dimension job survived travel");
      context.require(
          context.onClient(
              () -> source.captureAround(session, position, 0) instanceof Host.Capture.Deferred),
          "Old dimension source survived transfer");
    } finally {
      context.call(
          "world.command",
          Map.of(
              "command",
              "execute in minecraft:overworld run tp "
                  + uuid
                  + " "
                  + original.get("x")
                  + " "
                  + original.get("y")
                  + " "
                  + original.get("z")));
      context.await(
          "Overworld return",
          Duration.ofSeconds(45),
          () ->
              context.onClient(
                  () ->
                      Minecraft.getInstance()
                          .level
                          .dimension()
                          .identifier()
                          .getPath()
                          .equals("overworld")));
      context.onClient(
          () -> {
            job.leaveWorld();
            return null;
          });
    }
    context.require(
        context.onClient(
            () -> source.captureAround(session, position, 0) instanceof Host.Capture.Deferred),
        "Returning to an old dimension resurrected its captured source");
    context.await(
        "returned player position and chunk",
        Duration.ofSeconds(30),
        () ->
            context.onClient(
                () -> {
                  var mc = Minecraft.getInstance();
                  return Math.abs(mc.player.getX() - original.get("x")) < 0.1
                      && Math.abs(mc.player.getZ() - original.get("z")) < 0.1
                      && mc.level.getChunkSource().hasChunk(position.x() >> 4, position.z() >> 4);
                }));
    System.out.println(
        "LATTICIUM_AUDIT actual Overworld/Nether/Overworld travel preserves world-generation isolation");
  }
}
