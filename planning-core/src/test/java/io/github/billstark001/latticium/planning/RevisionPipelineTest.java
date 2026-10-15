package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.planning.Revisions.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RevisionPipelineTest {
  private static final Position POS = new Position(ResourceId.parse("overworld"), 0, 64, 0);
  private static final BlockState STONE = new BlockState(ResourceId.parse("stone"), Map.of());
  private static final BlockState AIR = new BlockState(ResourceId.parse("air"), Map.of());
  private static final Profile.Policy POLICY =
      new Profile.Policy(Profile.Policy.BreakMode.SELECTED, 1, 2);
  private static final Host.Epochs EPOCHS = new Host.Epochs(1, 1, 1, 1, 1, 1);

  @Test
  void freshSubmissionSnapshotCannotLaunderAnObsoleteQualifiedPlan() {
    for (var domain : Domain.values()) {
      var ledger = new Ledger(8);
      var session = new Host.SessionId();
      var key = new Key(domain, "read");
      ledger.sample(key, "before", Reliability.VERSIONED);
      var captured =
          new Host.Capture.Ready(
              new Host.Snapshot(
                  session, EPOCHS, facts(), ledger.capture(session, 1, List.of(key))));
      var result =
          new Planner()
              .plan(
                  POS,
                  STONE,
                  new TargetCell.Clear(),
                  POLICY,
                  (p, s, goal) ->
                      new Planner.Prediction.Proposals(
                          List.of(
                              new Planner.Proposal(
                                  Planner.Action.BREAK,
                                  AIR,
                                  Set.of(p),
                                  new Planner.Cost(1, 0, 1),
                                  "test:break"))),
                  4);
      var plan =
          assertInstanceOf(Planner.Result.Ready.class, result)
              .withInputs(captured.snapshot().inputs());
      ledger.sample(key, "after", Reliability.VERSIONED);
      var fresh =
          new Host.Snapshot(session, EPOCHS, facts(), ledger.capture(session, 1, List.of(key)));
      var controller = new JobController(session, POLICY, POS, new TargetCell.Clear(), plan);
      int[] sent = {0};
      controller.submit(
          fresh,
          STONE,
          (proposal, preconditions, policy) -> {
            assertEquals(plan.inputs(), preconditions.inputs(), domain.toString());
            if (!ledger.valid(preconditions.inputs(), session, 1))
              return new Host.Submission.Stale("Changed " + domain);
            sent[0]++;
            return new Host.Submission.Accepted(new Host.Receipt(UUID.randomUUID(), session));
          });
      assertEquals(0, sent[0], domain.toString());
      assertEquals(JobController.Status.BLOCKED, controller.status());
    }
  }

  @Test
  void ownWorldTransitionConfirmsTheActionButCannotReuseTheOldPlan() {
    var session = new Host.SessionId();
    var ledger = new Ledger(8);
    var cell = new Key(Domain.STATE, POS);
    var intent = new Key(Domain.RULES, "profile");
    ledger.sample(cell, STONE, Reliability.VERSIONED);
    ledger.sample(intent, "clear", Reliability.VERSIONED);
    var stamp = ledger.capture(session, 1, List.of(cell, intent));
    ledger.sample(cell, AIR, Reliability.VERSIONED);
    var confirmation = new ActionConfirmation(STONE, AIR, 1, 1);
    assertEquals(
        Host.Observation.CONFIRMED,
        confirmation.observe(AIR, new ServerUpdates.Update(2, AIR), 2, 100));
    assertFalse(ledger.valid(stamp, session, 2));
    assertTrue(ledger.valid(stamp.select(k -> k.domain() == Domain.RULES), session, 2));
    ledger.event("rule_edit", "profile");
    assertFalse(ledger.valid(stamp.select(k -> k.domain() == Domain.RULES), session, 2));
  }

  private static Facts facts() {
    return new Facts() {
      public java.util.Optional<WorldCell> world(Position p) {
        return java.util.Optional.of(new WorldCell(STONE, null, null, null, null));
      }

      public TargetCell target(Position p) {
        return new TargetCell.Clear();
      }

      public java.util.Optional<Position> player() {
        return java.util.Optional.of(POS);
      }

      public java.util.Optional<Set<ResourceId>> inventory() {
        return java.util.Optional.of(Set.of());
      }

      public Truth selection(String name, Position p) {
        return Truth.TRUE;
      }
    };
  }

  @Test
  void qualifiedMultiStepPlansRequireAFreshPlanAfterTheirOwnConfirmedTransition() {
    var dirt = new BlockState(ResourceId.parse("dirt"), Map.of());
    for (boolean paused : new boolean[] {false, true}) {
      var session = new Host.SessionId();
      var ledger = new Ledger(8);
      var key = new Key(Domain.STATE, POS);
      ledger.sample(key, STONE, Reliability.VERSIONED);
      var stamp = ledger.capture(session, 1, List.of(key));
      var breakStep =
          new Planner.Proposal(
              Planner.Action.BREAK, AIR, Set.of(POS), new Planner.Cost(1, 0, 1), "test:break");
      var placeStep =
          new Planner.Proposal(
              Planner.Action.PLACE, dirt, Set.of(POS), new Planner.Cost(1, 1, 0), "test:place");
      var plan =
          new Planner.Result.Ready(
              STONE, List.of(breakStep, placeStep), new Planner.Cost(2, 1, 1), stamp);
      var controller = new JobController(session, POLICY, POS, new TargetCell.Exact(dirt), plan);
      int[] sent = {0};
      Host.ActionGateway gateway =
          (step, before, policy) -> {
            sent[0]++;
            return new Host.Submission.Accepted(new Host.Receipt(UUID.randomUUID(), session));
          };
      controller.submit(new Host.Snapshot(session, EPOCHS, null, stamp), STONE, gateway);
      if (paused) controller.pause();
      ledger.sample(key, AIR, Reliability.VERSIONED);
      controller.observe((receipt, expected) -> Host.Observation.CONFIRMED);
      if (paused) {
        assertEquals(JobController.Status.PAUSED, controller.status());
        controller.resume();
      }
      assertEquals(JobController.Status.REPLAN, controller.status());
      assertEquals(AIR, controller.confirmedState());
      var fresh = ledger.capture(session, 2, List.of(key));
      controller.submit(new Host.Snapshot(session, EPOCHS, null, fresh), AIR, gateway);
      assertEquals(1, sent[0], "A fresh stamp cannot launder remaining steps from the old plan");
      var followup =
          new JobController(
              session,
              POLICY.afterActions(1),
              POS,
              new TargetCell.Exact(dirt),
              new Planner.Result.Ready(AIR, List.of(placeStep), placeStep.cost(), fresh));
      followup.submit(new Host.Snapshot(session, EPOCHS, null, fresh), AIR, gateway);
      followup.observe((receipt, expected) -> Host.Observation.CONFIRMED);
      assertEquals(2, sent[0]);
      assertEquals(JobController.Status.COMPLETE, followup.status());
    }
  }
}
