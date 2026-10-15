import io.github.billstark001.latticium.mc.*;
import io.github.billstark001.latticium.planning.Host;
import io.github.billstark001.latticium.planning.SectionScanner.Bounds;
import io.github.billstark001.minedriver.api.*;
import java.time.Duration;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * 1,024 survival placements, bounded to sixteen retained candidates, with a real bridge if loaded.
 */
public final class StreamingScenario implements Scenario {
  @Override
  public void run(ProbeContext context) throws Exception {
    context.call("world.create", Map.of("flat", true, "seed", "20261016", "timeoutMillis", 150000));
    verifyWorld(context);
    context.call("world.disconnect");
  }

  static void verifyWorld(ProbeContext context) throws Exception {
    NativeWorld.quiet(context);
    context.call(
        "world.command", Map.of("command", "execute as @e[type=minecraft:slime] run kill @s"));
    var origin =
        context.onClient(() -> Minecraft.getInstance().player.blockPosition().offset(8, 2, 8));
    var uuid = context.onClient(() -> Minecraft.getInstance().player.getUUID().toString());
    int[] supplied = {0};
    context.onServer(
        () -> {
          var mc = Minecraft.getInstance();
          var server = mc.getSingleplayerServer();
          var world = server.overworld();
          var player = server.getPlayerList().getPlayers().getFirst();
          player.setGameMode(GameType.SURVIVAL);
          for (int slot = 0; slot < 36; slot++)
            player.getInventory().setItem(slot, ItemStack.EMPTY);
          for (int x = -1; x <= 32; x++)
            for (int z = -1; z <= 48; z++) {
              var p = origin.offset(x, 0, z);
              for (int dy = -2; dy <= 3; dy++)
                world.setBlock(p.above(dy), Blocks.AIR.defaultBlockState(), 3);
              world.setBlock(p.below(), Blocks.STONE.defaultBlockState(), 3);
              if (x == -1 || x == 32 || z == -1 || z == 48)
                world.setBlock(p, Blocks.STONE.defaultBlockState(), 3);
            }
          return null;
        });
    var blueprint = context.onClient(() -> new MemoryBlueprint(origin, 32, 48));
    var dimension =
        context.onClient(
            () -> MinecraftStateCodec.id(Minecraft.getInstance().level.dimension().identifier()));
    var bands = new ArrayList<Bounds>();
    // Two target rows followed by an empty walkway; all tile supports are comfortably in reach.
    for (int band = 0; band < 16; band++)
      bands.add(
          new Bounds(
              dimension,
              origin.getX(),
              origin.getY(),
              origin.getZ() + band * 3,
              origin.getX() + 31,
              origin.getY(),
              origin.getZ() + band * 3 + 1));
    var session = new Host.SessionId();
    var samples = new ArrayList<Long>();
    var tileTimes = new ArrayList<Long>();
    int[] peak = {0};
    context.onServer(
        () -> {
          FixtureNetwork.begin(origin, 32, 48);
          return null;
        });
    var json =
        """
        {"schema":1,"id":"test:streaming_survival","scope":"selection(streaming)",
         "select":{"where":"all()"},"target":%s,
         "policy":{"break":"deny","refresh":"manual","max_actions_per_activation":1024},
         "execution":{"planning":{"max_candidates":16,"retry_ticks":1}}}
        """
            .formatted(
                blueprint.provider == null
                    ? "{\"source\":\"test:large_blueprint\"}"
                    : "{\"source\":\"latticium:active_blueprint\"}");
    var job =
        context.onClient(
            () ->
                new ClientJob(
                    Minecraft.getInstance(),
                    session,
                    json,
                    blueprint.provider == null
                        ? (pos, requested) ->
                            new io.github.billstark001.latticium.dsl.Model.TargetCell.Exact(
                                MinecraftStateCodec.state(Blocks.DIRT.defaultBlockState()))
                        : blueprint.source(session),
                    Map.of("streaming", bands)));
    context.call("profile.start");
    try {
      for (int tz = 0; tz < 16; tz++)
        for (int tx = 0; tx < 8; tx++) {
          final int x = tx * 4, z = tz * 3;
          context.onClient(
              () -> {
                job.pause();
                return null;
              });
          context.await(
              "settle before fixture travel",
              Duration.ofSeconds(10),
              () ->
                  context.onClient(
                      () -> {
                        job.tick();
                        return !job.hasPendingAction();
                      }));
          context.onServer(
              () -> {
                var player =
                    Minecraft.getInstance()
                        .getSingleplayerServer()
                        .getPlayerList()
                        .getPlayers()
                        .getFirst();
                int count = player.getInventory().getItem(0).getCount();
                supplied[0] += 64 - count;
                player.getInventory().setItem(0, new ItemStack(Items.DIRT, 64));
                player.inventoryMenu.broadcastChanges();
                return null;
              });
          context.call(
              "world.command",
              Map.of(
                  "command",
                  "tp "
                      + uuid
                      + " "
                      + (origin.getX() + x + 2.0)
                      + " "
                      + origin.getY()
                      + " "
                      + (origin.getZ() + z + 2.5)));
          context.await(
              "teleport/inventory synchronization",
              Duration.ofSeconds(10),
              () ->
                  context.onClient(
                          () -> {
                            var player = Minecraft.getInstance().player;
                            return Math.abs(player.getX() - (origin.getX() + x + 2.0)) < 0.1
                                && Math.abs(player.getZ() - (origin.getZ() + z + 2.5)) < 0.1
                                && !player.getAbilities().instabuild
                                && player.getInventory().getItem(0).is(Items.DIRT)
                                && player.getInventory().getItem(0).getCount() == 64;
                          })
                      && context.onServer(
                          () -> {
                            var connection =
                                Minecraft.getInstance()
                                    .getSingleplayerServer()
                                    .getPlayerList()
                                    .getPlayers()
                                    .getFirst()
                                    .connection;
                            var pending =
                                connection
                                    .getClass()
                                    .getDeclaredField("awaitingPositionFromClient");
                            pending.setAccessible(true);
                            return pending.get(connection) == null;
                          }));
          context.onClient(
              () -> {
                job.resume();
                return null;
              });
          long tileStarted = System.nanoTime();
          context.await(
              "survival streamed tile " + tx + "," + tz,
              Duration.ofSeconds(30),
              () -> {
                context.onClient(
                    () -> {
                      long started = System.nanoTime();
                      job.tick();
                      samples.add(System.nanoTime() - started);
                      peak[0] = Math.max(peak[0], job.snapshot().pendingCandidates());
                      if (peak[0] > 16) throw new AssertionError("Candidate capacity exceeded");
                      return null;
                    });
                return context.onServer(
                    () -> {
                      var world = Minecraft.getInstance().getSingleplayerServer().overworld();
                      for (int dx = 0; dx < 4; dx++)
                        for (int dz = 0; dz < 2; dz++)
                          if (!world
                              .getBlockState(origin.offset(x + dx, 0, z + dz))
                              .is(Blocks.DIRT)) return false;
                      return true;
                    });
              });
          tileTimes.add(System.nanoTime() - tileStarted);
        }
      context.onClient(
          () -> {
            job.pause();
            return null;
          });
      context.await(
          "last streamed receipt",
          Duration.ofSeconds(10),
          () ->
              context.onClient(
                  () -> {
                    job.tick();
                    return !job.hasPendingAction();
                  }));
      context.require(
          context.onClient(
              () ->
                  job.snapshot().submittedActions() == 1024
                      && job.snapshot().satisfiedCandidates() == 1024),
          "Streamed work was lost or duplicated: " + context.onClient(job::status));
      int remaining =
          context.onServer(
              () ->
                  Minecraft.getInstance()
                      .getSingleplayerServer()
                      .getPlayerList()
                      .getPlayers()
                      .getFirst()
                      .getInventory()
                      .getItem(0)
                      .getCount());
      context.require(supplied[0] - remaining == 1024, "Survival material consumption mismatch");
      var network = context.onServer(FixtureNetwork::end);
      context.require(
          network[0] == 1024 && network[1] == 0,
          "Actual server clicks/build-limit messages mismatch: "
              + java.util.Arrays.toString(network));
      samples.sort(Long::compare);
      tileTimes.sort(Long::compare);
      System.out.println(
          "LATTICIUM_AUDIT streaming size=1024 cap=16 peak="
              + peak[0]
              + " bridge="
              + (blueprint.provider != null)
              + " samples="
              + samples.size()
              + " jobNanosP50="
              + samples.get(samples.size() / 2)
              + " jobNanosP95="
              + samples.get(samples.size() * 95 / 100)
              + " max="
              + samples.getLast());
      System.out.println(
          "LATTICIUM_AUDIT streamed tile wait samples="
              + tileTimes.size()
              + " nanosP50="
              + tileTimes.get(tileTimes.size() / 2)
              + " nanosP95="
              + tileTimes.get(tileTimes.size() * 95 / 100)
              + " max="
              + tileTimes.getLast());
    } catch (RuntimeException error) {
      System.out.println(
          "LATTICIUM_AUDIT streaming failure "
              + context.onClient(
                  () ->
                      job.status()
                          + " blocked="
                          + job.blocked()
                          + " player="
                          + Minecraft.getInstance().player.position()
                          + " slot0="
                          + Minecraft.getInstance().player.getInventory().getItem(0)
                          + " paused="
                          + Minecraft.getInstance().isPaused()
                          + " screen="
                          + Minecraft.getInstance().gui.screen()));
      System.out.println(
          "LATTICIUM_AUDIT actual received clicks "
              + context.onServer(FixtureNetwork::diagnostics));
      throw error;
    } finally {
      context.onServer(FixtureNetwork::end);
      context.call("profile.stop", Map.of("name", "streaming-survival"));
      context.onClient(
          () -> {
            job.leaveWorld();
            blueprint.close();
            return null;
          });
      context.onServer(
          () -> {
            Minecraft.getInstance()
                .getSingleplayerServer()
                .getPlayerList()
                .getPlayers()
                .getFirst()
                .setGameMode(GameType.CREATIVE);
            return null;
          });
    }
  }
}
