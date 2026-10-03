package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Model.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JobControllerTest {
  @Test
  void acceptedActionNeedsObservationAndCancellationSettlesReceipt() {
    var session = new Host.SessionId();
    var pos = new Position(ResourceId.parse("minecraft:overworld"), 0, 0, 0);
    var stone = new BlockState(ResourceId.parse("minecraft:stone"), Map.of());
    var air = new BlockState(ResourceId.parse("minecraft:air"), Map.of());
    var step =
        new Planner.Proposal(
            Planner.Action.PLACE, stone, Set.of(pos), new Planner.Cost(1, 1, 0), "test");
    var controller =
        new JobController(
            session,
            new Profile.Policy(Profile.Policy.BreakMode.DENY, 1, 8),
            pos,
            new TargetCell.Exact(stone),
            new Planner.Result.Ready(air, List.of(step), step.cost()));
    var snapshot = new Host.Snapshot(session, new Host.Epochs(1, 1, 1, 1, 1, 1), null);
    controller.submit(
        snapshot,
        air,
        (s, p, policy) ->
            new Host.Submission.Accepted(new Host.Receipt(UUID.randomUUID(), session)));
    assertEquals(JobController.Status.WAITING, controller.status());
    assertEquals(0, controller.confirmedSteps());
    controller.cancel();
    assertEquals(JobController.Status.WAITING, controller.status());
    controller.observe((receipt, expected) -> Host.Observation.CONFIRMED);
    assertEquals(1, controller.confirmedSteps());
    assertEquals(JobController.Status.CANCELLED, controller.status());
  }

  @Test
  void staleSessionNeverSubmits() {
    var session = new Host.SessionId();
    var pos = new Position(ResourceId.parse("minecraft:overworld"), 0, 0, 0);
    var stone = new BlockState(ResourceId.parse("minecraft:stone"), Map.of());
    var air = new BlockState(ResourceId.parse("minecraft:air"), Map.of());
    var step =
        new Planner.Proposal(
            Planner.Action.PLACE, stone, Set.of(pos), new Planner.Cost(1, 1, 0), "test");
    var controller =
        new JobController(
            session,
            new Profile.Policy(Profile.Policy.BreakMode.DENY, 1, 8),
            pos,
            new TargetCell.Exact(stone),
            new Planner.Result.Ready(air, List.of(step), step.cost()));
    controller.submit(
        new Host.Snapshot(new Host.SessionId(), new Host.Epochs(1, 1, 1, 1, 1, 1), null),
        stone,
        (s, p, policy) -> fail("Gateway must not run"));
    assertEquals(JobController.Status.BLOCKED, controller.status());
  }

  @Test
  void nextStepRequiresObservedResultOfPreviousStep() {
    var session = new Host.SessionId();
    var pos = new Position(ResourceId.parse("minecraft:overworld"), 0, 0, 0);
    var air = new BlockState(ResourceId.parse("minecraft:air"), Map.of());
    var stone = new BlockState(ResourceId.parse("minecraft:stone"), Map.of());
    var dirt = new BlockState(ResourceId.parse("minecraft:dirt"), Map.of());
    var first =
        new Planner.Proposal(
            Planner.Action.PLACE, stone, Set.of(pos), new Planner.Cost(1, 1, 0), "first");
    var second =
        new Planner.Proposal(
            Planner.Action.PLACE, dirt, Set.of(pos), new Planner.Cost(1, 1, 0), "second");
    var controller =
        new JobController(
            session,
            new Profile.Policy(Profile.Policy.BreakMode.DENY, 1, 8),
            pos,
            new TargetCell.Exact(dirt),
            new Planner.Result.Ready(air, List.of(first, second), new Planner.Cost(2, 2, 0)));
    var snapshot = new Host.Snapshot(session, new Host.Epochs(1, 1, 1, 1, 1, 1), null);
    controller.submit(
        snapshot,
        air,
        (step, preconditions, policy) ->
            new Host.Submission.Accepted(new Host.Receipt(UUID.randomUUID(), session)));
    controller.observe((receipt, expected) -> Host.Observation.CONFIRMED);
    controller.submit(
        snapshot, air, (step, preconditions, policy) -> fail("Stale step must not submit"));
    assertEquals(JobController.Status.BLOCKED, controller.status());
    assertEquals(1, controller.confirmedSteps());
  }

  @Test
  void firstStepRequiresTheStateObservedDuringPlanning() {
    var session = new Host.SessionId();
    var pos = new Position(ResourceId.parse("minecraft:overworld"), 0, 0, 0);
    var air = new BlockState(ResourceId.parse("minecraft:air"), Map.of());
    var stone = new BlockState(ResourceId.parse("minecraft:stone"), Map.of());
    var step =
        new Planner.Proposal(
            Planner.Action.PLACE, stone, Set.of(pos), new Planner.Cost(1, 1, 0), "test");
    var controller =
        new JobController(
            session,
            new Profile.Policy(Profile.Policy.BreakMode.DENY, 1, 8),
            pos,
            new TargetCell.Exact(stone),
            new Planner.Result.Ready(air, List.of(step), step.cost()));
    controller.submit(
        new Host.Snapshot(session, new Host.Epochs(1, 1, 1, 1, 1, 1), null),
        stone,
        (proposal, preconditions, policy) -> fail("Stale first step must not submit"));
    assertEquals(JobController.Status.BLOCKED, controller.status());
  }

  @Test
  void directPlanCannotSubmitUndeclaredEffects() {
    var session = new Host.SessionId();
    var pos = new Position(ResourceId.parse("minecraft:overworld"), 0, 0, 0);
    var air = new BlockState(ResourceId.parse("minecraft:air"), Map.of());
    var stone = new BlockState(ResourceId.parse("minecraft:stone"), Map.of());
    var step =
        new Planner.Proposal(
            Planner.Action.PLACE, stone, Set.of(), new Planner.Cost(1, 1, 0), "invalid");
    var controller =
        new JobController(
            session,
            new Profile.Policy(Profile.Policy.BreakMode.DENY, 1, 8),
            pos,
            new TargetCell.Exact(stone),
            new Planner.Result.Ready(air, List.of(step), step.cost()));
    controller.submit(
        new Host.Snapshot(session, new Host.Epochs(1, 1, 1, 1, 1, 1), null),
        air,
        (proposal, preconditions, policy) -> fail("Invalid step must not submit"));
    assertEquals(JobController.Status.BLOCKED, controller.status());
  }

  @Test
  void acceptedRetryClearsPreviousDeferralReason() {
    var session = new Host.SessionId();
    var pos = new Position(ResourceId.parse("minecraft:overworld"), 0, 0, 0);
    var air = new BlockState(ResourceId.parse("minecraft:air"), Map.of());
    var stone = new BlockState(ResourceId.parse("minecraft:stone"), Map.of());
    var step =
        new Planner.Proposal(
            Planner.Action.PLACE, stone, Set.of(pos), new Planner.Cost(1, 1, 0), "test");
    var controller =
        new JobController(
            session,
            new Profile.Policy(Profile.Policy.BreakMode.DENY, 1, 8),
            pos,
            new TargetCell.Exact(stone),
            new Planner.Result.Ready(air, List.of(step), step.cost()));
    var snapshot = new Host.Snapshot(session, new Host.Epochs(1, 1, 1, 1, 1, 1), null);
    controller.submit(
        snapshot,
        air,
        (proposal, preconditions, policy) -> new Host.Submission.Deferred("Inventory unavailable"));
    assertEquals("Inventory unavailable", controller.reason());
    controller.submit(
        snapshot,
        air,
        (proposal, preconditions, policy) ->
            new Host.Submission.Accepted(new Host.Receipt(UUID.randomUUID(), session)));
    assertEquals(JobController.Status.WAITING, controller.status());
    assertEquals("", controller.reason());
  }

  @Test
  void directPlanMustEndAtTheDeclaredTarget() {
    var session = new Host.SessionId();
    var pos = new Position(ResourceId.parse("minecraft:overworld"), 0, 0, 0);
    var air = new BlockState(ResourceId.parse("minecraft:air"), Map.of());
    var stone = new BlockState(ResourceId.parse("minecraft:stone"), Map.of());
    var wrong =
        new Planner.Proposal(
            Planner.Action.PLACE, stone, Set.of(pos), new Planner.Cost(1, 1, 0), "wrong");
    var plan = new Planner.Result.Ready(air, List.of(wrong), wrong.cost());
    var policy = new Profile.Policy(Profile.Policy.BreakMode.SELECTED, 1, 8);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new JobController(
                session,
                policy,
                pos,
                new TargetCell.Exact(stone),
                new Planner.Result.Ready(stone, List.of(wrong), wrong.cost())));
    assertThrows(
        IllegalArgumentException.class,
        () -> new JobController(session, policy, pos, new TargetCell.Clear(), plan));
    assertThrows(
        IllegalArgumentException.class,
        () -> new JobController(session, policy, pos, new TargetCell.DontCare(), plan));
    var costly =
        new Planner.Proposal(
            Planner.Action.BREAK, air, Set.of(pos), new Planner.Cost(9, 0, 0), "costly");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new JobController(
                session,
                policy,
                pos,
                new TargetCell.Clear(),
                new Planner.Result.Ready(stone, List.of(costly), costly.cost())));
    var noChange =
        new Planner.Proposal(
            Planner.Action.INTERACT, stone, Set.of(pos), new Planner.Cost(1, 0, 0), "no-change");
    var dirt = new BlockState(ResourceId.parse("minecraft:dirt"), Map.of());
    var finish =
        new Planner.Proposal(
            Planner.Action.PLACE, dirt, Set.of(pos), new Planner.Cost(1, 1, 0), "finish");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new JobController(
                session,
                policy,
                pos,
                new TargetCell.Exact(dirt),
                new Planner.Result.Ready(
                    air, List.of(wrong, noChange, finish), new Planner.Cost(3, 2, 0))));
    var undo =
        new Planner.Proposal(
            Planner.Action.BREAK, air, Set.of(pos), new Planner.Cost(1, 0, 0), "undo");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new JobController(
                session,
                policy,
                pos,
                new TargetCell.Exact(stone),
                new Planner.Result.Ready(
                    air, List.of(wrong, undo, wrong), new Planner.Cost(3, 2, 0))));
  }
}
