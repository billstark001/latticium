package io.github.billstark001.latticium.mc;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.planning.ActionConfirmation;
import io.github.billstark001.latticium.planning.Host;
import io.github.billstark001.latticium.planning.Planner;
import io.github.billstark001.latticium.planning.Profile;
import io.github.billstark001.latticium.planning.Revisions;
import io.github.billstark001.latticium.planning.SectionScanner.Bounds;
import io.github.billstark001.latticium.planning.SelectionBounds;
import io.github.billstark001.latticium.planning.TargetSources;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The only normal-player action outlet; observations never follow from a successful method call.
 */
public final class MinecraftActionGateway implements Host.ActionGateway, Host.ObservationSource {
  private static final long TIMEOUT_TICKS = 100;

  private record Pending(
      Position position,
      Planner.Action action,
      Planner.Interaction interaction,
      TargetCell target,
      Revisions.Stamp inputs,
      ActionConfirmation confirmation) {}

  private final Minecraft minecraft;
  private final ClientLevel world;
  private final Host.SessionId session;
  private final Supplier<Host.Epochs> epochs;
  private final long registryBinding;
  private final Host.TargetSource targets;
  private final Compiler.Bound allowedItems;
  private final Map<String, List<Bounds>> selections;
  private final Map<UUID, Pending> pending = new HashMap<>();
  private boolean cancelRequested;
  private java.util.function.BooleanSupplier predictionValid = () -> true;
  private boolean transientConfirmation;

  void transientConfirmation(boolean enabled) {
    transientConfirmation = enabled;
  }

  public MinecraftActionGateway(
      Minecraft minecraft,
      Host.SessionId session,
      Supplier<Host.Epochs> epochs,
      Host.TargetSource targets,
      Compiler.Bound allowedItems,
      Map<String, List<Bounds>> selections) {
    this.minecraft = minecraft;
    world = minecraft.level;
    this.session = session;
    this.epochs = epochs;
    registryBinding = epochs.get().registry();
    this.targets = targets == null ? null : TargetSources.guarded(targets, session);
    this.allowedItems = allowedItems;
    this.selections = SelectionBounds.copy(selections);
  }

  void predictionValidity(java.util.function.BooleanSupplier check) {
    predictionValid = java.util.Objects.requireNonNull(check);
  }

  @Override
  public Host.Submission submit(
      Planner.Proposal step, Host.Preconditions before, Profile.Policy policy) {
    if (!minecraft.isSameThread()) return new Host.Submission.Rejected("Not on client thread");
    if (cancelRequested) return new Host.Submission.Rejected("Gateway cancelled or closed");
    var level = minecraft.level;
    var player = minecraft.player;
    var mode = minecraft.gameMode;
    if (level == null || player == null || mode == null)
      return new Host.Submission.Deferred("No world or player");
    if (level != world) return new Host.Submission.Stale("Client world changed");
    if (registryBinding != epochs.get().registry())
      return new Host.Submission.Stale("Registry binding changed");
    if (!session.equals(before.session()) || before.epochs().registry() != epochs.get().registry())
      return new Host.Submission.Stale("Session or facts changed");
    if (before.inputs() != null
        && !ClientRevisions.LEDGER.valid(before.inputs(), session, level.getGameTime()))
      return new Host.Submission.Deferred("Prediction revision changed; replan");
    var pos = before.position();
    if (!MinecraftStateCodec.id(level.dimension().identifier()).equals(pos.dimension()))
      return new Host.Submission.Stale("Dimension changed");
    if (!level.getChunkSource().hasChunk(pos.x() >> 4, pos.z() >> 4))
      return new Host.Submission.Deferred("Chunk unloaded");
    var blockPos = new BlockPos(pos.x(), pos.y(), pos.z());
    if (level.isOutsideBuildHeight(blockPos))
      return new Host.Submission.Rejected("Target outside dimension build height");
    if (!MinecraftStateCodec.state(level.getBlockState(blockPos)).equals(before.expectedCurrent()))
      return new Host.Submission.Stale("World state changed");
    if (targets != null) {
      var target = targets.target(pos, session);
      if (target instanceof TargetCell.Unknown)
        return new Host.Submission.Deferred("Target facts unavailable");
      if (!target.equals(before.expectedTarget()))
        return new Host.Submission.Stale("Target changed");
    }
    if (!step.affectsOnly(pos)) return new Host.Submission.Rejected("Undeclared effects");
    if (step.action() == Planner.Action.BREAK
        && policy.breakMode() == Profile.Policy.BreakMode.DENY)
      return new Host.Submission.Rejected("Breaking denied");
    if (!player.isWithinBlockInteractionRange(blockPos, 0))
      return new Host.Submission.Deferred("Outside interaction reach");
    var interaction = step.interaction();
    var update = LatticiumClient.get().serverUpdate(pos);
    long serverRevision = update == null ? 0 : update.revision();
    if (step.action() == Planner.Action.PLACE || step.action() == Planner.Action.INTERACT) {
      if (interaction == null)
        return new Host.Submission.Rejected("Interaction has no click context");
      int slot = interaction.inventorySlot();
      if (slot < 0 || slot >= 9)
        return new Host.Submission.Rejected("Interaction has no hotbar slot");
      var stack = player.getInventory().getItem(slot);
      if (step.action() == Planner.Action.PLACE && stack.isEmpty()
          || step.action() == Planner.Action.INTERACT && !stack.isEmpty())
        // Temporary slot availability must preserve a confirmed partial target for replanning.
        return new Host.Submission.Deferred("Interaction needs a different hotbar slot");
      if (step.action() == Planner.Action.PLACE && allowedItems != null) {
        var item = MinecraftStateCodec.id(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        var capture =
            new MinecraftSectionSource(
                    minecraft,
                    session,
                    epochs.get(),
                    targets,
                    selections,
                    allowedItems.dependencies())
                .captureAround(session, pos, allowedItems.radius());
        if (!(capture instanceof Host.Capture.Ready ready))
          return new Host.Submission.Deferred("Material facts unavailable");
        var membership = allowedItems.contains(ready.snapshot().facts(), item);
        if (membership == Truth.UNKNOWN)
          return new Host.Submission.Deferred("Material membership unknown");
        if (membership != Truth.TRUE)
          return new Host.Submission.Stale("Material no longer allowed");
      }
      Planner.Prediction prediction;
      try {
        prediction =
            new MinecraftPlacementOracle(minecraft)
                .predict(pos, before.expectedCurrent(), before.expectedTarget());
      } catch (RuntimeException error) {
        return new Host.Submission.Deferred(
            "Placement prediction unavailable: " + error.getClass().getSimpleName());
      }
      if (!(prediction instanceof Planner.Prediction.Proposals proposals)
          || proposals.steps().stream()
              .noneMatch(
                  p -> interaction.equals(p.interaction()) && step.result().equals(p.result())))
        return new Host.Submission.Deferred("Placement context needs replanning");
      var face = Direction.valueOf(interaction.face().name());
      var clicked = interaction.clicked();
      if (clicked != null && !clicked.dimension().equals(pos.dimension()))
        return new Host.Submission.Rejected("Clicked position has another dimension");
      var clickedPos =
          clicked == null
              ? step.action() == Planner.Action.INTERACT
                  ? blockPos
                  : blockPos.relative(face.getOpposite())
              : new BlockPos(clicked.x(), clicked.y(), clicked.z());
      if (level.isOutsideBuildHeight(clickedPos))
        return new Host.Submission.Rejected("Clicked position outside dimension build height");
      if (!player.isWithinBlockInteractionRange(clickedPos, 0))
        return new Host.Submission.Deferred("Clicked block outside interaction reach");
      player.getInventory().setSelectedSlot(slot);
      var hit =
          new BlockHitResult(
              new Vec3(interaction.hitX(), interaction.hitY(), interaction.hitZ()),
              face,
              clickedPos,
              false);
      if (!predictionValid.getAsBoolean())
        return new Host.Submission.Deferred("Prediction inputs changed; replan");
      mode.useItemOn(player, InteractionHand.MAIN_HAND, hit);

    } else if (step.action() == Planner.Action.BREAK) {
      var unsafe = new MinecraftPlacementOracle(minecraft).breakDeferral(pos);
      if (unsafe != null) return new Host.Submission.Deferred(unsafe);
      var afterBreak = MinecraftStateCodec.state(level.getFluidState(blockPos).createLegacyBlock());
      if (!step.result().equals(afterBreak))
        return new Host.Submission.Stale("Break result changed");
      var face = interaction == null ? Direction.UP : Direction.valueOf(interaction.face().name());
      if (!predictionValid.getAsBoolean())
        return new Host.Submission.Deferred("Prediction inputs changed; replan");
      if (!mode.startDestroyBlock(blockPos, face))
        return new Host.Submission.Deferred("Could not start breaking");
    } else return new Host.Submission.Rejected("No native executor for " + step.action());
    var receipt = new Host.Receipt(UUID.randomUUID(), session);
    pending.put(
        receipt.id(),
        new Pending(
            pos,
            step.action(),
            interaction,
            before.expectedTarget(),
            before.inputs(),
            new ActionConfirmation(
                before.expectedCurrent(), step.result(), level.getGameTime(), serverRevision)));
    return new Host.Submission.Accepted(receipt);
  }

  @Override
  public Host.Observation observe(Host.Receipt receipt, BlockState expected) {
    if (!minecraft.isSameThread()) throw new IllegalStateException("Observe on client thread");
    if (!session.equals(receipt.session())) return Host.Observation.CONTRADICTED;
    var action = pending.get(receipt.id());
    var level = minecraft.level;
    if (action == null) return Host.Observation.CONTRADICTED;
    if (level == null || minecraft.gameMode == null)
      return complete(receipt, Host.Observation.CONTRADICTED);
    if (level != world) return complete(receipt, Host.Observation.CONTRADICTED);
    if (action.inputs() != null) {
      var intent =
          action
              .inputs()
              .select(
                  key ->
                      switch (key.domain()) {
                        case WORLD, REGISTRY, SELECTION, RULES -> true;
                        default -> false;
                      });
      if (!ClientRevisions.LEDGER.valid(intent, session, level.getGameTime()))
        return complete(receipt, Host.Observation.CONTRADICTED);
    }
    var pos = action.position();
    if (!action.confirmation().expected().equals(expected)
        || targets != null && !targets.target(pos, session).equals(action.target()))
      return complete(receipt, Host.Observation.CONTRADICTED);
    if (!MinecraftStateCodec.id(level.dimension().identifier()).equals(pos.dimension()))
      return complete(receipt, Host.Observation.CONTRADICTED);
    var blockPos = new BlockPos(pos.x(), pos.y(), pos.z());
    boolean loaded = level.getChunkSource().hasChunk(pos.x() >> 4, pos.z() >> 4);
    var observation =
        action
            .confirmation()
            .observe(
                loaded ? MinecraftStateCodec.state(level.getBlockState(blockPos)) : null,
                LatticiumClient.get().serverUpdate(pos),
                level.getGameTime(),
                TIMEOUT_TICKS);
    if (transientConfirmation)
      observation =
          action
              .confirmation()
              .observeTransition(
                  loaded ? MinecraftStateCodec.state(level.getBlockState(blockPos)) : null,
                  LatticiumClient.get().serverUpdate(pos),
                  LatticiumClient.get()
                      .expectedUpdate(pos, expected, action.confirmation().serverRevision()),
                  level.getGameTime(),
                  TIMEOUT_TICKS);
    if (observation != Host.Observation.STILL_PENDING) return complete(receipt, observation);
    if (!loaded) return Host.Observation.STILL_PENDING;
    if (action.action() == Planner.Action.BREAK && !cancelRequested) {
      var face =
          action.interaction() == null
              ? Direction.UP
              : Direction.valueOf(action.interaction().face().name());
      minecraft.gameMode.continueDestroyBlock(blockPos, face);
    }
    return Host.Observation.STILL_PENDING;
  }

  private Host.Observation complete(Host.Receipt receipt, Host.Observation result) {
    var action = pending.remove(receipt.id());
    if (action != null && action.action() == Planner.Action.BREAK && minecraft.gameMode != null)
      minecraft.gameMode.stopDestroyBlock();
    return result;
  }

  public void cancelFurtherWork() {
    cancelRequested = true;
    if (minecraft.gameMode != null) minecraft.gameMode.stopDestroyBlock();
  }

  public void close() {
    cancelRequested = true;
    pending.clear();
    if (minecraft.gameMode != null) minecraft.gameMode.stopDestroyBlock();
  }
}
