package io.github.billstark001.latticium.mc;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.FactDependencies;
import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.planning.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Native revision/intent probes. Temporary client-only state is restored in finally blocks. */
public final class NativeRevisionProbe {
  private NativeRevisionProbe() {}

  public static List<String> verify(BlockPos at) {
    var passed = new ArrayList<String>();
    var minecraft = Minecraft.getInstance();
    var dimension = MinecraftStateCodec.id(minecraft.level.dimension().identifier());
    var pos = new Position(dimension, at.getX(), at.getY(), at.getZ());
    var bounds =
        new SectionScanner.Bounds(dimension, pos.x(), pos.y(), pos.z(), pos.x(), pos.y(), pos.z());
    var session = new Host.SessionId();
    var profile = profile(at, "");
    var bound = bind(minecraft, profile);
    var fine = proof(minecraft, session, bound, pos, null);
    check(
        valid(fine) && !stamp(fine).reusable(), "native prediction inputs are short-lived", passed);
    var far =
        new BlockPos((at.getX() & ~15) + ((at.getX() & 15) < 8 ? 12 : 3), at.getY(), at.getZ());
    var oldFar = minecraft.level.getBlockState(far);
    try {
      minecraft.level.setBlock(far, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
      check(valid(fine), "unrelated native cell changes preserve a fine candidate", passed);
    } finally {
      minecraft.level.setBlock(far, oldFar, 3);
    }
    var coarse =
        proof(
            minecraft,
            new Host.SessionId(),
            bind(
                minecraft,
                profile(at, ",\"execution\":{\"consistency\":{\"granularity\":\"section\"}}")),
            pos,
            null);
    try {
      minecraft.level.setBlock(far, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
      check(
          !valid(coarse),
          "section tracking rejects another native change in the same section",
          passed);
    } finally {
      minecraft.level.setBlock(far, oldFar, 3);
    }
    var aba = proof(minecraft, new Host.SessionId(), bound, pos, null);
    var support = at.below();
    var oldSupport = minecraft.level.getBlockState(support);
    try {
      minecraft.level.setBlock(support, Blocks.GLASS.defaultBlockState(), 3);
      minecraft.level.setBlock(support, oldSupport, 3);
      check(
          !valid(aba), "native local A-to-B-to-A support changes do not resurrect a plan", passed);
    } finally {
      minecraft.level.setBlock(support, oldSupport, 3);
    }

    var source =
        new MinecraftSectionSource(
            minecraft,
            session,
            ClientRegistryState.epochs(),
            null,
            Map.of("revision", List.of(bounds)),
            FactDependencies.of(FactDependencies.Fact.STATE));
    var capture = source.captureAround(session, pos, 0);
    var ready = (Host.Capture.Ready) capture;
    check(
        ready.snapshot().inputs() != null && ready.snapshot().inputs().reusable(),
        "native immutable state captures carry version-qualified spatial evidence",
        passed);
    var old = minecraft.level.getBlockState(at);
    try {
      minecraft.level.setBlock(at, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
      check(
          !ledger().valid(ready.snapshot().inputs(), session, minecraft.level.getGameTime()),
          "native captures are rejected after a relevant local update",
          passed);
    } finally {
      minecraft.level.setBlock(at, old, 3);
    }

    var stopping =
        new ClientJob(
            minecraft, new Host.SessionId(), profile, null, Map.of("revision", List.of(bounds)));
    var frozen =
        new ClientJob(
            minecraft,
            new Host.SessionId(),
            profile(
                at,
                ",\"execution\":{\"consistency\":{\"selections\":\"freeze\",\"rules\":\"freeze\"}}"),
            null,
            Map.of("revision", List.of(bounds)));
    stopping.pause();
    frozen.pause();
    try {
      edited("selection_edit", "unreferenced");
      stopping.tick();
      check(!stopping.isSettled(), "unreferenced selection edits preserve a native job", passed);
      edited("selection_edit", "revision");
      stopping.tick();
      frozen.tick();
      check(
          stopping.isSettled() && !frozen.isSettled(),
          "native selection stop and freeze policies have distinct behavior",
          passed);
      var before = intent(new ProfileReader().read(profile));
      edited("rule_edit", "test:unreferenced");
      check(valid(before), "unreferenced profile edits preserve intent", passed);
      edited("rule_edit", "test:native_revision");
      check(!valid(before), "referenced profile edits invalidate live intent", passed);
    } finally {
      stopping.leaveWorld();
      frozen.leaveWorld();
    }

    var proof = proof(minecraft, new Host.SessionId(), bound, pos, null);
    var stack = minecraft.player.getInventory().getItem(0).copy();
    long inventory = ClientRegistryState.epochs().inventory();
    try {
      minecraft.player.getInventory().setItem(0, new ItemStack(Items.STICK, 3));
      check(
          ClientRegistryState.epochs().inventory() > inventory && !valid(proof),
          "native inventory epochs advance and stale predictions are rejected",
          passed);
    } finally {
      minecraft.player.getInventory().setItem(0, stack);
    }

    var dirt = new TargetCell.Exact(MinecraftStateCodec.state(Blocks.DIRT.defaultBlockState()));
    var stone = new TargetCell.Exact(MinecraftStateCodec.state(Blocks.STONE.defaultBlockState()));
    TargetCell[] value = {dirt};
    Host.TargetSource live = TargetSources.guarded((p, s) -> value[0], session);
    var liveProof = proof(minecraft, session, bound, pos, live);
    var pinned = new TargetSources.Pinned(live, session);
    pinned.pin(pos, dirt);
    value[0] = stone;
    check(
        !valid(liveProof)
            && pinned.target(pos, session).equals(dirt)
            && pinned.target(pos.offset(1, 0, 0), session).equals(stone),
        "unversioned source edits are rechecked while pinned intent is explicit per cell",
        passed);
    value[0] = dirt;
    var running = proof(minecraft, session, bound, pos, live);
    var previewSession = new Host.SessionId();
    proof(minecraft, previewSession, bound, pos, (p, s) -> stone);
    check(
        valid(running),
        "a preview's target revision cannot invalidate a running target namespace",
        passed);
    projectedReads(minecraft, at, bound, pos, passed);
    var activationIntent =
        intent(
            new ProfileReader()
                .read(
                    profile(
                        at,
                        ",\"activation\":{\"where\":\"selection(trigger)\",\"mode\":\"while\"}")));
    edited("selection_edit", "trigger");
    check(
        !valid(activationIntent),
        "automatic activation selections participate in native edit policy",
        passed);
    buildHeight(minecraft, at, dimension, passed);
    return List.copyOf(passed);
  }

  private static void buildHeight(
      Minecraft mc, BlockPos at, ResourceId dimension, List<String> passed) {
    var session = new Host.SessionId();
    var air = MinecraftStateCodec.state(Blocks.AIR.defaultBlockState());
    var dirt = MinecraftStateCodec.state(Blocks.DIRT.defaultBlockState());
    var target = new TargetCell.Exact(dirt);
    var policy = new ProfileReader().read(profile(at, "")).policy();
    var gateway =
        new MinecraftActionGateway(mc, session, ClientRegistryState::epochs, null, null, Map.of());
    try {
      for (int y : new int[] {mc.level.getMinY() - 1, mc.level.getMaxY() + 1}) {
        var pos = new Position(dimension, at.getX(), y, at.getZ());
        var step =
            new Planner.Proposal(
                Planner.Action.PLACE,
                dirt,
                java.util.Set.of(pos),
                new Planner.Cost(1, 1, 0),
                "test:build_height");
        var result =
            gateway.submit(
                step,
                new Host.Preconditions(session, ClientRegistryState.epochs(), pos, air, target),
                policy);
        check(
            result instanceof Host.Submission.Rejected rejected
                && rejected.reason().contains("build height")
                && new MinecraftPlacementOracle(mc).predict(pos, air, target)
                    instanceof Planner.Prediction.Unknown,
            "dimension-specific build-height boundary " + y + " is rejected before interaction",
            passed);
      }
    } finally {
      gateway.close();
    }
  }

  private static void projectedReads(
      Minecraft mc, BlockPos at, Profile.Bound bound, Position pos, List<String> passed) {
    var air = MinecraftStateCodec.state(Blocks.AIR.defaultBlockState());
    var step =
        new Planner.Proposal(
            Planner.Action.BREAK,
            air,
            java.util.Set.of(pos),
            new Planner.Cost(1, 0, 1),
            "test:projection");
    var plan = new Planner.Result.Ready(air, List.of(step), step.cost());
    var clear =
        project(proof(mc, new Host.SessionId(), bound, pos, null), bound, pos, plan, List.of());
    var below = mc.level.getBlockState(at.below());
    try {
      mc.level.setBlock(at.below(), Blocks.GLASS.defaultBlockState(), 3);
      check(valid(clear), "a clear proposal does not freeze an unused support block", passed);
    } finally {
      mc.level.setBlock(at.below(), below, 3);
    }
    var placement =
        new Planner.Proposal(
            Planner.Action.PLACE,
            MinecraftStateCodec.state(Blocks.DIRT.defaultBlockState()),
            java.util.Set.of(pos),
            new Planner.Cost(1, 1, 0),
            "test:future_place",
            new Planner.Interaction(
                0,
                Planner.Interaction.Face.UP,
                pos.x() + 0.5,
                pos.y(),
                pos.z() + 0.5,
                pos.offset(0, -1, 0)));
    var replacement =
        project(
            proof(mc, new Host.SessionId(), bound, pos, null),
            bound,
            pos,
            plan,
            List.of(placement));
    try {
      mc.level.setBlock(at.below(), Blocks.GLASS.defaultBlockState(), 3);
      check(
          !valid(replacement),
          "pre-break qualification retains the future placement's actual support",
          passed);
    } finally {
      mc.level.setBlock(at.below(), below, 3);
    }
    var relevant =
        project(proof(mc, new Host.SessionId(), bound, pos, null), bound, pos, plan, List.of());
    var fullBound =
        bind(mc, profile(at, ",\"execution\":{\"consistency\":{\"inventory\":\"full\"}}"));
    var full =
        project(
            proof(mc, new Host.SessionId(), fullBound, pos, null), fullBound, pos, plan, List.of());
    int unused = (mc.player.getInventory().getSelectedSlot() + 1) % 9;
    var previous = mc.player.getInventory().getItem(unused).copy();
    try {
      mc.player.getInventory().setItem(unused, new ItemStack(Items.APPLE, 7));
      check(
          valid(relevant) && !valid(full),
          "relevant inventory ignores an unused slot while full inventory freezes it",
          passed);
    } finally {
      mc.player.getInventory().setItem(unused, previous);
    }
  }

  private static Object project(
      Object proof,
      Profile.Bound bound,
      Position pos,
      Planner.Result.Ready plan,
      List<Planner.Proposal> preconditions) {
    return ((java.util.Optional<?>)
            NativeAccess.call(
                proof,
                "forPlan",
                new Class<?>[] {
                  Profile.Bound.class, Position.class, Planner.Result.Ready.class, List.class
                },
                bound,
                pos,
                plan,
                preconditions))
        .orElseThrow();
  }

  private static Profile.Bound bind(Minecraft minecraft, String json) {
    var reader = new ProfileReader();
    return reader.bind(reader.read(json), new Compiler(new MinecraftRegistry(minecraft.level), 16));
  }

  private static Object proof(
      Minecraft minecraft,
      Host.SessionId session,
      Profile.Bound profile,
      Position position,
      Host.TargetSource target) {
    return NativeAccess.callStatic(
        "ClientProof",
        "capture",
        new Class<?>[] {
          Minecraft.class,
          Host.SessionId.class,
          Profile.Bound.class,
          Position.class,
          Host.TargetSource.class
        },
        minecraft,
        session,
        profile,
        position,
        target);
  }

  private static Object intent(Profile profile) {
    return NativeAccess.construct("ClientIntent", new Class<?>[] {Profile.class}, profile);
  }

  private static boolean valid(Object input) {
    return (Boolean) NativeAccess.call(input, "valid");
  }

  private static Revisions.Stamp stamp(Object input) {
    return (Revisions.Stamp) NativeAccess.call(input, "stamp");
  }

  private static Revisions.Ledger ledger() {
    return (Revisions.Ledger) NativeAccess.field("ClientRevisions", "LEDGER");
  }

  private static void edited(String event, String resource) {
    NativeAccess.callStatic(
        "ClientRevisions", "edited", new Class<?>[] {String.class, String.class}, event, resource);
  }

  private static String profile(BlockPos at, String options) {
    return """
        {"schema":1,"id":"test:native_revision","scope":"selection(\\\"revision\\\")",
         "select":{"where":"all()"},"target":{"clear":true},
         "policy":{"break":"selected","refresh":"manual"}%s}
        """
        .formatted(options);
  }

  private static void check(boolean condition, String label, List<String> passed) {
    if (!condition) throw new AssertionError(label);
    passed.add(label);
  }
}
