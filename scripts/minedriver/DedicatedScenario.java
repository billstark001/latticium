import io.github.billstark001.latticium.mc.*;
import io.github.billstark001.latticium.planning.Host;
import io.github.billstark001.minedriver.api.*;
import java.time.Duration;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Real remote connection; every final world assertion goes through the dedicated server's RCON. */
public final class DedicatedScenario implements Scenario {
  @Override
  public void run(ProbeContext context) throws Exception {
    try (var server = new Rcon()) {
      server.command("whitelist off");
      context.call("world.connect", Map.of("address", server.address, "timeoutMillis", 150000));
      context.require(
          context.onClient(() -> Minecraft.getInstance().getSingleplayerServer() == null),
          "Dedicated acceptance must not use an integrated server");
      server.command("scoreboard objectives add latticium_audit dummy");
      // New players may spawn outside the fixture's server view distance. Loading the
      // destination must precede fill; an unloaded fill command otherwise changes nothing.
      server.command("tp @a 0.5 100 0.5");
      context.await(
          "remote fixture chunk residency",
          Duration.ofSeconds(30),
          () -> context.onClient(() -> {
            var mc = Minecraft.getInstance();
            if (Math.abs(mc.player.getX() - 0.5) > 0.1
                || Math.abs(mc.player.getZ() - 0.5) > 0.1) return false;
            for (int x = -1; x <= 0; x++)
              for (int z = -1; z <= 0; z++)
                if (!mc.level.getChunkSource().hasChunk(x, z)) return false;
            return true;
          }));
      var filled = server.command("fill -8 80 -8 8 80 8 minecraft:stone");
      server.command("fill -8 81 -8 8 85 8 minecraft:air");
      context.require(server.block(2, 80, 0, "minecraft:stone"),
          "Dedicated fixture support fill failed: " + filled);
      server.command("gamemode survival @a");
      server.command("tp @a 0.5 81 0.5");
      server.command("clear @a");
      server.command("give @a minecraft:dirt 16");
      context.await(
          "dedicated fixture and inventory",
          Duration.ofSeconds(15),
          () ->
              context.onClient(
                  () -> {
                    var mc = Minecraft.getInstance();
                    return mc.player.blockPosition().equals(new BlockPos(0, 81, 0))
                        && !mc.player.getAbilities().instabuild
                        && mc.player.getInventory().getItem(0).is(Items.DIRT)
                        && mc.level.getBlockState(new BlockPos(2, 80, 0)).is(Blocks.STONE);
                  }));
      runJob(
          context, server, "place", "{\"items\":\"i{minecraft:dirt}\"}", "deny", "minecraft:dirt");
      runJob(context, server, "clear", "{\"clear\":true}", "selected", "minecraft:air");
      // Out-of-reach work must remain available until normal server-authoritative travel returns.
      server.command("tp @a 512.5 81 0.5");
      context.await(
          "natural remote chunk unload",
          Duration.ofSeconds(30),
          () ->
              context.onClient(
                  () -> {
                    var mc = Minecraft.getInstance();
                    return mc.player.getX() > 500 && !mc.level.getChunkSource().hasChunk(0, 0);
                  }));
      var held =
          context.onClient(
              () ->
                  new ClientJob(
                      Minecraft.getInstance(),
                      new Host.SessionId(),
                      profile("travel", "{\"items\":\"i{minecraft:dirt}\"}", "deny"),
                      null,
                      Map.of()));
      try {
        context.onClient(
            () -> {
              for (int i = 0; i < 25; i++) held.tick();
              return null;
            });
        context.require(
            context.onClient(() -> held.snapshot().submittedActions() == 0),
            "Unloaded distant target submitted an action");
        server.command("tp @a 0.5 81 0.5");
        context.await(
            "remote chunk reload and deferred work",
            Duration.ofSeconds(30),
            () ->
                context.onClient(
                    () -> {
                      held.tick();
                      return held.isFinished();
                    }));
        context.require(
            server.block(2, 81, 0, "minecraft:dirt"),
            "Dedicated server did not confirm resumed placement");
      } finally {
        context.onClient(
            () -> {
              held.leaveWorld();
              return null;
            });
      }
      verifyRefillAndBridge(context, server);
      System.out.println(
          "LATTICIUM_AUDIT dedicated survival place/clear, refill, optional real blueprint, real travel unload/reload and server-authoritative final states passed");
      context.call("world.disconnect");
    }
  }

  private static void verifyRefillAndBridge(ProbeContext context, Rcon server) throws Exception {
    server.command("setblock 2 81 0 minecraft:air");
    server.command("clear @a");
    context.await(
        "remote inventory exhaustion",
        Duration.ofSeconds(10),
        () ->
            context.onClient(
                () -> {
                  var mc = Minecraft.getInstance();
                  return mc.player.getInventory().getItem(0).isEmpty()
                      && mc.level.getBlockState(new BlockPos(2, 81, 0)).isAir();
                }));
    var blueprint = context.onClient(() -> new MemoryBlueprint(new BlockPos(2, 81, 0), 1, 1));
    context.require(
        (blueprint.provider != null) == Boolean.getBoolean("latticium.audit.expectBridge"),
        "Remote bridge acceptance must exercise the expected registered provider");
    var session = new Host.SessionId();
    var job =
        context.onClient(
            () ->
                new ClientJob(
                    Minecraft.getInstance(),
                    session,
                    profile(
                        "refill",
                        blueprint.provider == null
                            ? "{\"items\":\"i{minecraft:dirt}\"}"
                            : "{\"source\":\"latticium:active_blueprint\"}",
                        "deny"),
                    blueprint.source(session),
                    Map.of()));
    try {
      int[] polls = {0};
      context.await(
          "no materials means no remote action",
          Duration.ofSeconds(10),
          () ->
              context.onClient(
                  () -> {
                    job.tick();
                    context.require(
                        job.snapshot().submittedActions() == 0,
                        "Exhausted inventory submitted an action");
                    return ++polls[0] >= 20;
                  }));
      server.command("give @a minecraft:dirt 4");
      context.await(
          "remote refill resumes original task",
          Duration.ofSeconds(30),
          () ->
              context.onClient(
                  () -> {
                    job.tick();
                    return job.isFinished();
                  }));
      context.require(
          context.onClient(
              () ->
                  job.snapshot().submittedActions() == 1
                      && job.snapshot().satisfiedCandidates() == 1),
          "Refilled remote task did not confirm exactly one action");
      context.require(
          server.block(2, 81, 0, "minecraft:dirt"), "Remote refill/source final state mismatch");
      System.out.println("LATTICIUM_AUDIT dedicated refill bridge=" + (blueprint.provider != null));
    } finally {
      context.onClient(
          () -> {
            job.leaveWorld();
            blueprint.close();
            return null;
          });
    }
  }

  private static void runJob(
      ProbeContext context, Rcon server, String name, String target, String breaking, String result)
      throws Exception {
    var job =
        context.onClient(
            () ->
                new ClientJob(
                    Minecraft.getInstance(),
                    new Host.SessionId(),
                    profile(name, target, breaking),
                    null,
                    Map.of()));
    try {
      context.await(
          "dedicated " + name,
          Duration.ofSeconds(30),
          () ->
              context.onClient(
                  () -> {
                    job.tick();
                    return job.isFinished();
                  }));
      context.require(
          context.onClient(
              () ->
                  job.snapshot().submittedActions() == 1
                      && job.snapshot().satisfiedCandidates() == 1),
          "Remote operation lacked a confirmed receipt: " + context.onClient(job::status));
      context.require(
          server.block(2, 81, 0, result),
          "Dedicated authoritative final state mismatch for " + name);
    } finally {
      context.onClient(
          () -> {
            job.leaveWorld();
            return null;
          });
    }
  }

  private static String profile(String name, String target, String breaking) {
    return """
        {"schema":1,"id":"test:dedicated_%s","scope":"box(2,81,0,2,81,0)",
         "select":{"where":"all()"},"target":%s,
         "policy":{"break":"%s","refresh":"manual","max_actions_per_activation":4}}
        """
        .formatted(name, target, breaking);
  }
}
