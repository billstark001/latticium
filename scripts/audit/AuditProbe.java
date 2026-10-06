import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.mc.*;
import io.github.billstark001.latticium.planning.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Ephemeral test world; only normal Latticium interactions implement the tested construction. */
public final class AuditProbe implements Runnable {
  private static volatile boolean done;
  private final Minecraft minecraft = Minecraft.getInstance();
  private final Path output = Path.of(System.getProperty("latticium.audit.output"));
  private final StringBuilder results = new StringBuilder();
  private long end = System.currentTimeMillis() + 600_000;
  private int stage;
  private int scenario;
  private BlockPos target;
  private Position position;
  private ClientJob job;
  private long next;
  private int jobTicks;
  private RealBlueprintAudit real;
  private BlueprintAudit blueprint;
  private FillAudit fill;
  private ReloadReceiptAudit reloadReceipt;
  private ReplacementSafetyAudit safety;

  public static boolean isDone() {
    return done;
  }

  static Object call(Object receiver, String name, Object... args) throws Exception {
    Class<?> type = receiver instanceof Class<?> c ? c : receiver.getClass();
    for (var method : type.getMethods()) {
      if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
      boolean matches = true;
      for (int i = 0; i < args.length; i++)
        if (args[i] != null
            && !method.getParameterTypes()[i].isPrimitive()
            && !method.getParameterTypes()[i].isInstance(args[i])) matches = false;
      if (matches) return method.invoke(receiver instanceof Class<?> ? null : receiver, args);
    }
    throw new NoSuchMethodException(type.getName() + "." + name);
  }

  void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  void pass(String message) throws Exception {
    results.append("PASS: ").append(message).append('\n');
    System.out.println("LATTICIUM_AUDIT " + message);
    Files.createDirectories(output);
    Files.writeString(output.resolve("progress.txt"), results);
  }

  public void run() {
    if (done || System.currentTimeMillis() < next) return;
    try {
      if (System.currentTimeMillis() > end)
        throw new AssertionError(
            "Timeout at stage="
                + stage
                + ", scenario="
                + scenario
                + (job == null ? "" : ", " + job.status()));
      Object screen = minecraft.gui.screen();
      if (stage == 0) {
        if (screen != null
            && (screen.getClass().getSimpleName().equals("NoticeScreen")
                || screen.getClass().getSimpleName().equals("WarningScreen")
                || screen.getClass().getSimpleName().equals("AccessibilityOnboardingScreen")
                || screen.getClass().getSimpleName().equals("LoadingErrorScreen"))) {
          for (Object child : (List<?>) call(screen, "children")) {
            if (!(child instanceof net.minecraft.client.gui.components.Button)) continue;
            String label = call(call(child, "getMessage"), "getString").toString();
            System.out.println("AUDIT startup button: " + label);
            if (label.startsWith("Proceed")
                || label.equals("Continue")
                || label.equals("Done")
                || label.equals("I understand")
                || label.contains("了解")
                || label.contains("继续")) {
              call(child, "onPress", new Object[] {null});
              break;
            }
          }
        }
        if (screen == null
            || !screen.getClass().getSimpleName().equals("TitleScreen")
            || minecraft.gui.overlay() != null) return;
        call(
            Class.forName("net.minecraft.client.gui.screens.worldselection.CreateWorldScreen"),
            "openFresh",
            minecraft,
            (Runnable) () -> {});
        stage = 1;
      } else if (stage == 1) {
        if (screen == null || !screen.getClass().getSimpleName().equals("CreateWorldScreen"))
          return;
        Object state = call(screen, "getUiState");
        call(state, "setName", "Latticium Audit " + System.currentTimeMillis());
        call(
            state,
            "setGameMode",
            Class.forName(
                    "net.minecraft.client.gui.screens.worldselection.WorldCreationUiState$SelectedGameMode")
                .getField("CREATIVE")
                .get(null));
        for (Object entry : (List<?>) call(state, "getNormalPresetList")) {
          String description = call(call(entry, "describePreset"), "getString").toString();
          if (description.contains("Flat") || description.contains("フラット"))
            call(state, "setWorldType", entry);
        }
        String label =
            net.minecraft.network.chat.Component.translatable("selectWorld.create").getString();
        for (Object child : (List<?>) call(screen, "children")) {
          if (child instanceof net.minecraft.client.gui.components.Button button
              && button.getMessage().getString().equals(label)) {
            call(button, "onPress", new Object[] {null});
            stage = 2;
            break;
          }
        }
      } else if (stage == 2) {
        if (minecraft.level == null || minecraft.player == null || screen != null) return;
        LatticiumClient.get().stopAll();
        var feet = minecraft.player.blockPosition();
        target = feet.offset(2, 0, 0);
        position =
            new Position(
                MinecraftStateCodec.id(minecraft.level.dimension().identifier()),
                target.getX(),
                target.getY(),
                target.getZ());
        CaptureAudit.run(this, minecraft, position);
        codecTests();
        LifecycleAudit.run(this, minecraft, position);
        blueprint = new BlueprintAudit(this, minecraft, position, target);
        if (blueprint.create()) {
          stage = 5;
          end = System.currentTimeMillis() + 30_000;
        } else {
          setup();
          stage = 3;
        }
      } else if (stage == 5) {
        if (!blueprint.checks()) return;
        String fixtures = System.getProperty("latticium.audit.schematics", "");
        if (!fixtures.isBlank()) {
          real =
              new RealBlueprintAudit(
                  blueprint.provider(), blueprint.manager(), Path.of(fixtures), output);
          stage = 6;
          end = System.currentTimeMillis() + 180_000;
        } else {
          setup();
          stage = 3;
        }
      } else if (stage == 6) {
        if (!real.tick()) return;
        pass(real.summary());
        setup();
        stage = 3;
      } else if (stage == 3) {
        if (!minecraft.level.getBlockState(target).is(Blocks.STONE)
            || !minecraft.player.getInventory().getItem(0).is(Items.DIRT)) return;
        String breaks = scenario == 2 ? "deny" : "selected";
        int budget = scenario == 1 ? 1 : 2;
        String json =
            """
            {"schema":1,"id":"test:audit","scope":"box(%d,%d,%d,%d,%d,%d)",
             "select":{"where":"current(b{minecraft:stone})"},
             "target":{"source":"test:audit"},
             "policy":{"break":"%s","refresh":"continuous","max_actions_per_activation":%d}}
            """
                .formatted(
                    target.getX(),
                    target.getY(),
                    target.getZ(),
                    target.getX(),
                    target.getY(),
                    target.getZ(),
                    breaks,
                    budget);
        job =
            new ClientJob(
                minecraft,
                new Host.SessionId(),
                json,
                (pos, session) ->
                    new TargetCell.Exact(
                        MinecraftStateCodec.state(Blocks.DIRT.defaultBlockState())),
                Map.of());
        jobTicks = 0;
        end = System.currentTimeMillis() + 30_000;
        stage = 4;
      } else if (stage == 4) {
        job.noteBlockUpdate(
            position); // Include packet-driven refresh around the partial transition.
        job.tick();
        jobTicks++;
        if (scenario == 0
            && minecraft.level.getBlockState(target).is(Blocks.DIRT)
            && !job.hasPendingAction()) {
          check(job.snapshot().submittedActions() == 2, "Replacement used wrong action count");
          check(
              job.snapshot().satisfiedCandidates() == 1, "Replacement lost its claimed candidate");
          pass(
              "break-then-place completed with source predicate false after break, packet refresh, and exactly two server-confirmed actions");
          advance();
        } else if ((scenario == 1 || scenario == 2) && jobTicks >= 25) {
          check(
              job.snapshot().submittedActions() == 0,
              "Forbidden or one-budget replacement broke the source");
          check(
              minecraft.level.getBlockState(target).is(Blocks.STONE),
              "Source changed under denied replacement");
          pass(
              scenario == 1
                  ? "one remaining action does not break a replacement"
                  : "break deny submits no destructive fallback");
          advance();
        }
      } else if (stage == 7 && fill.tick()) {
        reloadReceipt = new ReloadReceiptAudit(this, minecraft, position, target);
        stage = 8;
        end = System.currentTimeMillis() + 30_000;
      } else if (stage == 8 && reloadReceipt.tick()) {
        safety = new ReplacementSafetyAudit(this, minecraft, position, target);
        stage = 9;
        end = System.currentTimeMillis() + 45_000;
      } else if (stage == 9 && safety.tick()) {
        Files.writeString(output.resolve("result.txt"), results + "PASS: all audit checks\n");
        done = true;
        minecraft.stop();
      }
    } catch (Throwable failure) {
      failure.printStackTrace();
      try {
        Files.createDirectories(output);
        Files.writeString(output.resolve("result.txt"), results + "FAIL: " + failure + "\n");
      } catch (Exception ignored) {
      }
      if (job != null) job.leaveWorld();
      if (fill != null) fill.close();
      if (reloadReceipt != null) reloadReceipt.close();
      if (safety != null) safety.close();
      done = true;
      minecraft.stop();
    }
  }

  private void codecTests() throws Exception {
    int count = 0;
    for (var block : net.minecraft.core.registries.BuiltInRegistries.BLOCK)
      for (var nativeState : block.getStateDefinition().getPossibleStates()) {
        check(
            MinecraftStateCodec.state(MinecraftStateCodec.state(nativeState)).orElseThrow()
                == nativeState,
            "State round-trip mismatch: " + nativeState);
        count++;
      }
    var repeater = MinecraftStateCodec.state(Blocks.REPEATER.defaultBlockState());
    check(
        MinecraftStateCodec.state(new BlockState(repeater.block(), Map.of())).isEmpty(),
        "Missing properties accepted");
    var invalid = new java.util.HashMap<>(repeater.properties());
    invalid.put("delay", "5");
    check(
        MinecraftStateCodec.state(new BlockState(repeater.block(), invalid)).isEmpty(),
        "Invalid property accepted");
    for (var spelling : List.of("01", "+1", " 1", "1 ")) {
      var properties = new java.util.HashMap<>(repeater.properties());
      properties.put("delay", spelling);
      check(
          MinecraftStateCodec.state(new BlockState(repeater.block(), properties)).isEmpty(),
          "Noncanonical exact property accepted: " + spelling);
    }
    var renamed = new java.util.HashMap<>(repeater.properties());
    renamed.remove("delay");
    renamed.put("unknown", "1");
    check(
        MinecraftStateCodec.state(new BlockState(repeater.block(), renamed)).isEmpty(),
        "Unknown property accepted");
    invalid = new java.util.HashMap<>(repeater.properties());
    invalid.put("extra", "value");
    check(
        MinecraftStateCodec.state(new BlockState(repeater.block(), invalid)).isEmpty(),
        "Extra property accepted");
    check(
        MinecraftStateCodec.state(new BlockState(ResourceId.parse("test:missing"), Map.of()))
            .isEmpty(),
        "Missing block accepted");
    pass(
        "native exact-state round trips: "
            + count
            + " registered states, invalid/missing/extra properties rejected");
  }

  private void setup() {
    var server = minecraft.getSingleplayerServer();
    var dimension = minecraft.level.dimension();
    var uuid = minecraft.player.getUUID();
    server.execute(
        () -> {
          var level = server.getLevel(dimension);
          level.setBlock(target.below(), Blocks.STONE.defaultBlockState(), 3);
          level.setBlock(target, Blocks.STONE.defaultBlockState(), 3);
          var player = server.getPlayerList().getPlayer(uuid);
          player.getInventory().setItem(0, new ItemStack(Items.DIRT, 64));
          player.inventoryMenu.broadcastChanges();
        });
    next = System.currentTimeMillis() + 1000;
  }

  private void advance() throws Exception {
    job.leaveWorld();
    job = null;
    if (++scenario < 3) {
      setup();
      stage = 3;
      end = System.currentTimeMillis() + 30_000;
    } else {
      fill = new FillAudit(this, minecraft, position, target);
      stage = 7;
      end = System.currentTimeMillis() + 30_000;
    }
  }
}
