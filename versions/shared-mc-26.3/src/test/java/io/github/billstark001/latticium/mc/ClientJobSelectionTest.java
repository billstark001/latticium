package io.github.billstark001.latticium.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.planning.Profile;
import io.github.billstark001.latticium.planning.ProfileReader;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ClientJobSelectionTest {
  @Test
  void replacementNeedsBudgetForBreakAndFollowUp() {
    var policy = new Profile.Policy(Profile.Policy.BreakMode.SELECTED, 1, 2);
    var exact =
        new TargetCell.Exact(
            new BlockState(ResourceId.parse("minecraft:stone"), java.util.Map.of()));
    assertEquals(true, ClientTargetResolver.hasReplacementBudget(exact, policy));
    assertEquals(false, ClientTargetResolver.hasReplacementBudget(exact, policy.afterActions(1)));
    assertEquals(
        true,
        ClientTargetResolver.hasReplacementBudget(new TargetCell.Clear(), policy.afterActions(1)));
  }

  @Test
  void freshEligibilityFollowsChangedAndUnknownFacts() {
    var profile =
        new ProfileReader()
            .read(
                """
                {"schema":1,"id":"test:selection","scope":"box(0,0,0,0,0,0)",
                 "select":{"where":"all()"},"target":{"clear":true}}
                """);
    var inScope = new AtomicReference<>(Truth.TRUE);
    var selected = new AtomicReference<>(Truth.TRUE);
    int[] selectionCalls = {0};
    var bound =
        new Profile.Bound(
            profile,
            null,
            new Compiler.Bound(SetType.POS, (facts, pos, value) -> inScope.get(), 0),
            new Compiler.Bound(
                SetType.POS,
                (facts, pos, value) -> {
                  selectionCalls[0]++;
                  return selected.get();
                },
                0),
            null,
            null);
    var position = new Position(ResourceId.parse("minecraft:overworld"), 0, 0, 0);
    assertEquals(Truth.TRUE, ClientJob.selectionAt(bound, null, position));
    selected.set(Truth.FALSE);
    assertEquals(Truth.FALSE, ClientJob.selectionAt(bound, null, position));
    selected.set(Truth.UNKNOWN);
    assertEquals(Truth.UNKNOWN, ClientJob.selectionAt(bound, null, position));
    inScope.set(Truth.FALSE);
    assertEquals(Truth.FALSE, ClientJob.selectionAt(bound, null, position));
    assertEquals(3, selectionCalls[0]);
  }
}
