package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.planning.Revisions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Executes actual capture, planner, submission and receipt boundaries for every catalog event. */
class RevisionStagesTest {
  enum Stage {
    CAPTURE,
    PLANNING,
    SUBMISSION,
    OBSERVATION
  }

  private static final Position POS = new Position(ResourceId.parse("overworld"), 0, 64, 0);
  private static final BlockState STONE = new BlockState(ResourceId.parse("stone"), Map.of());
  private static final BlockState AIR = new BlockState(ResourceId.parse("air"), Map.of());
  private static final BlockState DIRT = new BlockState(ResourceId.parse("dirt"), Map.of());
  private static final Host.Epochs EPOCHS = new Host.Epochs(1, 1, 1, 1, 1, 1);
  private static final Profile.Policy POLICY =
      new Profile.Policy(Profile.Policy.BreakMode.SELECTED, 1, 2);

  @Test
  void eventCatalogIsExercisedThroughEveryPipelineBoundary() {
    for (var event : RevisionEvents.all())
      for (var stage : Stage.values()) {
        var session = new Host.SessionId();
        var ledger = new Ledger(64);
        var keys = event.domains().stream().map(d -> new Key(d, "input")).toList();
        for (var key : keys) ledger.sample(key, "before", Reliability.VERSIONED);
        var snapshot = new Host.Snapshot(session, EPOCHS, null, ledger.capture(session, 1, keys));
        String label = event.id() + "/" + stage;
        ledger.event(event.id(), "unrelated");
        assertTrue(ledger.valid(snapshot.inputs(), session, 1), label);
        if (stage == Stage.CAPTURE) {
          ledger.event(event.id(), "input");
          // The adapter's capture fence discards this scan before it may become a candidate.
          assertFalse(ledger.valid(snapshot.inputs(), session, 1), label);
          continue;
        }
        var breakStep =
            new Planner.Proposal(
                Planner.Action.BREAK, AIR, Set.of(POS), new Planner.Cost(1, 0, 1), "test:break");
        var placeStep =
            new Planner.Proposal(
                Planner.Action.PLACE, DIRT, Set.of(POS), new Planner.Cost(1, 1, 0), "test:place");
        var result =
            new Planner()
                .plan(
                    POS,
                    STONE,
                    new TargetCell.Exact(DIRT),
                    POLICY,
                    (p, current, goal) -> {
                      if (stage == Stage.PLANNING && current.equals(STONE))
                        ledger.event(event.id(), "input");
                      return new Planner.Prediction.Proposals(
                          List.of(current.equals(STONE) ? breakStep : placeStep));
                    },
                    8);
        var plan =
            assertInstanceOf(Planner.Result.Ready.class, result, label)
                .withInputs(snapshot.inputs());
        var controller = new JobController(session, POLICY, POS, new TargetCell.Exact(DIRT), plan);
        int[] sent = {0};
        controller.submit(
            new Host.Snapshot(session, EPOCHS, null, ledger.capture(session, 1, keys)),
            STONE,
            (step, before, policy) -> {
              if (stage == Stage.SUBMISSION) ledger.event(event.id(), "input");
              if (!ledger.valid(before.inputs(), session, 1))
                return new Host.Submission.Stale("Changed input");
              sent[0]++;
              return new Host.Submission.Accepted(new Host.Receipt(UUID.randomUUID(), session));
            });
        if (stage == Stage.OBSERVATION) {
          assertEquals(1, sent[0], label);
          ledger.event(event.id(), "input");
          controller.observe(
              (receipt, expected) -> {
                assertFalse(ledger.valid(plan.inputs(), session, 1), label);
                // The physical first operation may confirm; it cannot license the old second step.
                return new ActionConfirmation(STONE, AIR, 1, 1)
                    .observe(AIR, new ServerUpdates.Update(2, AIR), 2, 100);
              });
          assertEquals(JobController.Status.REPLAN, controller.status(), label);
          controller.submit(
              new Host.Snapshot(session, EPOCHS, null, ledger.capture(session, 1, keys)),
              AIR,
              (step, before, policy) -> {
                sent[0]++;
                return new Host.Submission.Accepted(new Host.Receipt(UUID.randomUUID(), session));
              });
          assertEquals(1, sent[0], label);
        } else {
          assertEquals(0, sent[0], label);
          assertEquals(JobController.Status.BLOCKED, controller.status(), label);
        }
      }
  }
}
