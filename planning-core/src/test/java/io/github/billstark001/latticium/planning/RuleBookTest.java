package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.dsl.Syntax;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RuleBookTest {
  @Test
  void declaredTransitionUsesSharedGuardAndPlanner() {
    String json =
        """
          {"schema":1,"rules":[{"id":"latticium:clear_stone","before":{"block":"minecraft:stone"},
          "after":{"block":"minecraft:air"},"action":"break","when":"current(b{minecraft:stone})",
          "verify":"current(b{minecraft:air}) & matches_target()"}]}
          """;
    var rules = RuleBook.parse(json, Compiler.symbolic());
    var dim = ResourceId.parse("minecraft:overworld");
    var pos = new Position(dim, 0, 0, 0);
    var stone = new BlockState(ResourceId.parse("minecraft:stone"), Map.of());
    var facts =
        new Facts() {
          public Optional<WorldCell> world(Position p) {
            return Optional.empty();
          }

          public TargetCell target(Position p) {
            return new TargetCell.Clear();
          }

          public Optional<Position> player() {
            return Optional.empty();
          }

          public Optional<Set<ResourceId>> inventory() {
            return Optional.empty();
          }

          public Truth selection(String name, Position p) {
            return Truth.FALSE;
          }
        };
    var planner = new Planner();
    assertInstanceOf(
        Planner.Result.NoPlan.class,
        planner.plan(
            pos,
            stone,
            new TargetCell.Clear(),
            new Profile.Policy(Profile.Policy.BreakMode.DENY, 1, 4),
            rules.oracle(facts),
            8));
    assertInstanceOf(
        Planner.Result.Ready.class,
        planner.plan(
            pos,
            stone,
            new TargetCell.Clear(),
            new Profile.Policy(Profile.Policy.BreakMode.SELECTED, 1, 4),
            rules.oracle(facts),
            8));
  }

  @Test
  void duplicateRuleIdRejected() {
    String rule =
        "{\"id\":\"latticium:x\",\"before\":{\"block\":\"minecraft:stone\"},\"after\":{\"block\":\"minecraft:air\"},\"action\":\"break\"}";
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RuleBook.parse(
                "{\"schema\":1,\"rules\":[" + rule + "," + rule + "]}", Compiler.symbolic()));
  }

  @Test
  void unchangedStateCannotProduceAUsefulTransition() {
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                RuleBook.parse(
                    """
                    {"schema":1,"rules":[{"id":"latticium:no_op",
                     "before":{"block":"minecraft:stone"},
                     "after":{"block":"minecraft:stone"},"action":"interact"}]}
                    """,
                    Compiler.symbolic()));
    assertTrue(error.getMessage().startsWith("/rules/0/after:"));
  }

  @Test
  void falseGuardDominatesUnknownRequirementAndBindingRestoresTargetPhase() {
    var compiler = Compiler.symbolic();
    var rules =
        RuleBook.parse(
            """
            {"schema":1,"rules":[{"id":"latticium:guarded","before":{"block":"minecraft:stone"},
             "after":{"block":"minecraft:air"},"action":"break","when":"none()",
             "requires":"adjacent(current(b{minecraft:stone}))"}]}
            """,
            compiler);
    assertThrows(Syntax.Failure.class, () -> compiler.compile("matches_target()", SetType.POS));
    var pos = new Position(ResourceId.parse("minecraft:overworld"), 0, 0, 0);
    var stone = new BlockState(ResourceId.parse("minecraft:stone"), Map.of());
    int[] worldReads = {0};
    Facts facts =
        new Facts() {
          public Optional<WorldCell> world(Position p) {
            worldReads[0]++;
            return Optional.empty();
          }

          public TargetCell target(Position p) {
            return new TargetCell.Clear();
          }

          public Optional<Position> player() {
            return Optional.empty();
          }

          public Optional<Set<ResourceId>> inventory() {
            return Optional.empty();
          }

          public Truth selection(String name, Position p) {
            return Truth.FALSE;
          }
        };
    assertInstanceOf(
        Planner.Prediction.NoLegalPlacement.class,
        rules.oracle(facts).predict(pos, stone, new TargetCell.Clear()));
    assertEquals(0, worldReads[0]);
  }

  @Test
  void ruleCostsAndSchemaRequireIntegralJsonNumbers() {
    assertThrows(
        IllegalArgumentException.class,
        () -> RuleBook.parse("{\"schema\":1.0,\"rules\":[]}", Compiler.symbolic()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RuleBook.parse(
                """
                {"schema":1,"rules":[{"id":"latticium:cost","before":{"block":"minecraft:stone"},
                 "after":{"block":"minecraft:air"},"action":"break","cost":{"risk":1.5}}]}
                """,
                Compiler.symbolic()));
  }

  @Test
  void speculativeAfterStateDoesNotReuseOldWorldDerivedFacts() {
    var rules =
        RuleBook.parse(
            """
            {"schema":1,"rules":[{"id":"latticium:change","before":{"block":"minecraft:stone"},
             "after":{"block":"minecraft:air"},"action":"break","verify":"solid()"}]}
            """,
            Compiler.symbolic());
    var pos = new Position(ResourceId.parse("minecraft:overworld"), 0, 0, 0);
    var stone = new BlockState(ResourceId.parse("minecraft:stone"), Map.of());
    Facts facts =
        new Facts() {
          public Optional<WorldCell> world(Position p) {
            return Optional.of(new WorldCell(stone, null, null, 0, true));
          }

          public TargetCell target(Position p) {
            return new TargetCell.Clear();
          }

          public Optional<Position> player() {
            return Optional.empty();
          }

          public Optional<Set<ResourceId>> inventory() {
            return Optional.empty();
          }

          public Truth selection(String name, Position p) {
            return Truth.FALSE;
          }
        };
    assertInstanceOf(
        Planner.Prediction.Unknown.class,
        rules.oracle(facts).predict(pos, stone, new TargetCell.Clear()));
  }

  @Test
  void invalidResourceIdsIdentifyTheirRuleField() {
    String json =
        """
        {"schema":1,"rules":[{"id":"latticium:example",
         "before":{"block":"minecraft:stone"},"after":{"block":"minecraft:air"},
         "action":"break"}]}
        """;
    var badRule =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                RuleBook.parse(
                    json.replace("latticium:example", "Bad:example"), Compiler.symbolic()));
    assertTrue(badRule.getMessage().startsWith("/rules/0/id:"));
    var badBlock =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                RuleBook.parse(json.replace("minecraft:stone", "Bad:stone"), Compiler.symbolic()));
    assertTrue(badBlock.getMessage().startsWith("/rules/0/before/block:"));
  }

  @Test
  void missingActionReportsItsRequiredField() {
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                RuleBook.parse(
                    """
                    {"schema":1,"rules":[{"id":"test:missing_action",
                     "before":{"block":"minecraft:stone"},
                     "after":{"block":"minecraft:air"}}]}
                    """,
                    Compiler.symbolic()));
    assertTrue(error.getMessage().contains("/rules/0/action: expected string"));
  }

  @Test
  void speculativeTransitionInvalidatesNeighborDerivedFacts() {
    var rules =
        RuleBook.parse(
            """
            {"schema":1,"rules":[{"id":"latticium:neighbor","before":{"block":"minecraft:stone"},
             "after":{"block":"minecraft:air"},"action":"break",
             "requires":"adjacent(light(0..15))","verify":"adjacent(light(0..15))"}]}
            """,
            Compiler.symbolic());
    var pos = new Position(ResourceId.parse("minecraft:overworld"), 0, 0, 0);
    var stone = new BlockState(ResourceId.parse("minecraft:stone"), Map.of());
    var air = new BlockState(ResourceId.parse("minecraft:air"), Map.of());
    Facts facts =
        new Facts() {
          public Optional<WorldCell> world(Position p) {
            if (p.equals(pos)) return Optional.of(new WorldCell(stone, null, null, 0, true));
            if (p.equals(pos.offset(1, 0, 0)))
              return Optional.of(new WorldCell(stone, null, null, 0, true));
            return Optional.empty();
          }

          public TargetCell target(Position p) {
            return new TargetCell.Clear();
          }

          public Optional<Position> player() {
            return Optional.empty();
          }

          public Optional<Set<ResourceId>> inventory() {
            return Optional.empty();
          }

          public Truth selection(String name, Position p) {
            return Truth.FALSE;
          }
        };
    assertInstanceOf(
        Planner.Prediction.Unknown.class,
        rules.oracle(facts).predict(pos, stone, new TargetCell.Exact(air)));
  }
}
