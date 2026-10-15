import io.github.billstark001.latticium.dsl.FactDependencies;
import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.mc.*;
import io.github.billstark001.latticium.planning.*;
import io.github.billstark001.minedriver.api.ProbeContext;
import io.github.billstark001.minedriver.api.Scenario;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;

/**
 * Explicit server packets exercise native unload/reload and receipt boundaries in an owned world.
 */
public final class ResidencyScenario implements Scenario {
  @Override
  public void run(ProbeContext context) {
    context.call("world.create", Map.of("flat", true, "seed", "20261015", "timeoutMillis", 150000));
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
    int y = context.onClient(() -> Minecraft.getInstance().player.blockPosition().getY());
    context.call("world.fixture", Map.of("position", Map.of("x", 14.5, "y", y, "z", 8.5)));
    context.await(
        "chunk-border fixture",
        Duration.ofSeconds(10),
        () ->
            context.onClient(
                () -> {
                  var minecraft = Minecraft.getInstance();
                  return minecraft.player.blockPosition().getX() == 14
                      && minecraft.level.getChunkSource().hasChunk(1, 0);
                }));
    try {
      for (boolean timeout : new boolean[] {false, true}) verifyReceipt(context, y, timeout);
    } finally {
      context.call("world.fixture", Map.of("position", original));
    }
    System.out.println(
        "LATTICIUM_AUDIT native chunk unload keeps packet-confirmed air pending; reload confirms or preserves timeout, and deferred scans resume");
  }

  private static void verifyReceipt(ProbeContext context, int y, boolean timeout) {
    var target = new BlockPos(16, y, 8);
    context.onServer(
        () -> {
          var server = Minecraft.getInstance().getSingleplayerServer();
          var level = server.overworld();
          level.setBlock(target.below(), Blocks.STONE.defaultBlockState(), 3);
          level.setBlock(target, Blocks.STONE.defaultBlockState(), 3);
          return null;
        });
    context.await(
        "authoritative stone fixture",
        Duration.ofSeconds(5),
        () ->
            context.onClient(
                () -> Minecraft.getInstance().level.getBlockState(target).is(Blocks.STONE)));
    var holder = new Fixture[1];
    context.onClient(
        () -> {
          holder[0] = new Fixture(target);
          holder[0].submit();
          return null;
        });
    try {
      context.await(
          "normal break reaches the server",
          Duration.ofSeconds(5),
          () ->
              context.onServer(
                  () ->
                      Minecraft.getInstance()
                          .getSingleplayerServer()
                          .overworld()
                          .getBlockState(target)
                          .isAir()));
      context.onServer(
          () -> {
            var server = Minecraft.getInstance().getSingleplayerServer();
            var player = server.getPlayerList().getPlayers().getFirst();
            player.connection.send(
                new ClientboundForgetLevelChunkPacket(
                    new ChunkPos(target.getX() >> 4, target.getZ() >> 4)));
            player.connection.send(
                new ClientboundBlockUpdatePacket(target, server.overworld().getBlockState(target)));
            return null;
          });
      context.await(
          "native chunk unload",
          Duration.ofSeconds(5),
          () ->
              context.onClient(
                  () -> !Minecraft.getInstance().level.getChunkSource().hasChunk(1, 0)));
      context.require(
          context.onClient(() -> holder[0].observe() == Host.Observation.STILL_PENDING),
          "Unloaded default air must not confirm an accepted break");
      context.onClient(
          () -> {
            holder[0].startDeferredScan();
            return null;
          });
      if (timeout)
        context.await(
            "unloaded receipt timeout",
            Duration.ofSeconds(10),
            () -> context.onClient(() -> holder[0].observe() == Host.Observation.TIMED_OUT));
      context.onServer(
          () -> {
            var server = Minecraft.getInstance().getSingleplayerServer();
            var level = server.overworld();
            server
                .getPlayerList()
                .getPlayers()
                .getFirst()
                .connection
                .send(
                    new ClientboundLevelChunkWithLightPacket(
                        level.getChunkAt(target), level.getLightEngine(), null, null));
            return null;
          });
      context.await(
          "native chunk reload",
          Duration.ofSeconds(5),
          () ->
              context.onClient(
                  () -> {
                    var level = Minecraft.getInstance().level;
                    return level.getChunkSource().hasChunk(1, 0)
                        && level.getBlockState(target).isAir();
                  }));
      if (timeout)
        context.require(
            context.onClient(() -> holder[0].observe() == Host.Observation.CONTRADICTED),
            "Reload must not revive a consumed timeout receipt");
      else
        context.await(
            "reloaded authoritative receipt",
            Duration.ofSeconds(5),
            () -> context.onClient(() -> holder[0].observe() == Host.Observation.CONFIRMED));
      context.await(
          "deferred scan resumes",
          Duration.ofSeconds(5),
          () ->
              context.onClient(
                  () -> {
                    holder[0].scan.tick();
                    return holder[0].scan.isFinished();
                  }));
      context.require(
          context.onClient(
              () ->
                  holder[0].scan.snapshot().satisfiedCandidates() == 1
                      && holder[0].scan.snapshot().submittedActions() == 0),
          "Reloaded scan must observe air without submitting another action");
    } finally {
      context.onClient(
          () -> {
            holder[0].close();
            return null;
          });
    }
  }

  /** All access to this fixture and native objects occurs through onClient. */
  private static final class Fixture {
    private final Minecraft minecraft = Minecraft.getInstance();
    private final BlockPos target;
    private final Host.SessionId session = new Host.SessionId();
    private final Position position;
    private final MinecraftActionGateway gateway;
    private final BlockState air = MinecraftStateCodec.state(Blocks.AIR.defaultBlockState());
    private Host.Receipt receipt;
    private ClientJob scan;

    Fixture(BlockPos target) {
      this.target = target;
      position =
          new Position(
              MinecraftStateCodec.id(minecraft.level.dimension().identifier()),
              target.getX(),
              target.getY(),
              target.getZ());
      gateway =
          new MinecraftActionGateway(
              minecraft, session, ClientRegistryState::epochs, null, null, Map.of());
    }

    void submit() {
      var step =
          new Planner.Proposal(
              Planner.Action.BREAK,
              air,
              Set.of(position),
              new Planner.Cost(1, 0, 1),
              "test:residency_break");
      var before =
          new Host.Preconditions(
              session,
              ClientRegistryState.epochs(),
              position,
              MinecraftStateCodec.state(minecraft.level.getBlockState(target)),
              new TargetCell.Clear());
      var result =
          gateway.submit(step, before, new Profile.Policy(Profile.Policy.BreakMode.SELECTED, 1, 1));
      if (!(result instanceof Host.Submission.Accepted accepted))
        throw new AssertionError("Break was not accepted: " + result);
      receipt = accepted.receipt();
    }

    Host.Observation observe() {
      return gateway.observe(receipt, air);
    }

    void startDeferredScan() {
      var source =
          new MinecraftSectionSource(
              minecraft,
              session,
              ClientRegistryState.epochs(),
              null,
              Map.of(),
              FactDependencies.of(FactDependencies.Fact.STATE));
      var capture = source.captureAround(session, position, 0);
      if (!(capture instanceof Host.Capture.Ready ready)
          || ready.snapshot().facts().world(position).isPresent())
        throw new AssertionError("Unloaded chunk invented captured world facts");
      String profile =
          """
          {"schema":1,"id":"test:residency_scan","scope":"box(16,%d,8,16,%d,8)",
           "select":{"where":"current(s{air})"},"target":{"clear":true},
           "policy":{"break":"deny","refresh":"manual"}}
          """
              .formatted(target.getY(), target.getY());
      scan = new ClientJob(minecraft, new Host.SessionId(), profile, null, Map.of());
      scan.tick();
      if (scan.snapshot().deferredReads() == 0 || scan.snapshot().satisfiedCandidates() != 0)
        throw new AssertionError("Unloaded scan did not defer");
    }

    void close() {
      gateway.close();
      if (scan != null) scan.leaveWorld();
    }
  }
}
