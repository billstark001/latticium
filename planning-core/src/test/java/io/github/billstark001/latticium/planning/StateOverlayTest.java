package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.*;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StateOverlayTest {
  private static final ResourceId DIM = ResourceId.parse("minecraft:overworld");
  private static final Position POS = new Position(DIM, 0, 64, 0);
  private static final BlockState STONE =
      new BlockState(ResourceId.parse("minecraft:stone"), Map.of());
  private static final BlockState AIR = new BlockState(ResourceId.parse("minecraft:air"), Map.of());
  private static final WorldCell ORIGINAL =
      new WorldCell(
          STONE,
          ResourceId.parse("minecraft:plains"),
          ResourceId.parse("minecraft:empty"),
          12,
          true);

  private static Facts facts() {
    return new Facts() {
      public Optional<WorldCell> world(Position p) {
        return Optional.of(ORIGINAL);
      }

      public TargetCell target(Position p) {
        return new TargetCell.DontCare();
      }

      public Optional<Position> player() {
        return Optional.of(POS);
      }

      public Optional<Set<ResourceId>> inventory() {
        return Optional.of(Set.of(STONE.block()));
      }

      public Truth selection(String name, Position p) {
        return Truth.TRUE;
      }
    };
  }

  @Test
  void changedStateDoesNotReuseDerivedFactsBeyondImmediateNeighbors() {
    var original = facts();
    var changed = StateOverlay.at(original, POS, AIR, new TargetCell.Clear());
    var current = changed.world(POS).orElseThrow();
    assertEquals(AIR, current.state());
    assertEquals(ORIGINAL.biome(), current.biome());
    for (int distance : new int[] {0, 1, 2, 16, 100}) {
      var cell = changed.world(POS.offset(distance, 0, 0)).orElseThrow();
      assertNull(cell.light());
      assertNull(cell.fluid());
      assertNull(cell.solid());
      if (distance > 0) assertEquals(STONE, cell.state());
    }
    var other = new Position(ResourceId.parse("minecraft:the_nether"), 0, 64, 0);
    assertEquals(ORIGINAL, changed.world(other).orElseThrow());
    assertEquals(ORIGINAL, original.world(POS).orElseThrow());
    assertInstanceOf(TargetCell.Clear.class, changed.target(POS));
    assertInstanceOf(TargetCell.DontCare.class, changed.target(POS.offset(1, 0, 0)));
    assertEquals(original.player(), changed.player());
    assertEquals(original.inventory(), changed.inventory());
    assertEquals(Truth.TRUE, changed.selection("build", POS));
  }

  @Test
  void identicalStateRetainsCapturedFacts() {
    var unchanged = StateOverlay.at(facts(), POS, STONE, new TargetCell.Exact(STONE));
    assertEquals(ORIGINAL, unchanged.world(POS).orElseThrow());
    assertEquals(ORIGINAL, unchanged.world(POS.offset(2, 0, 0)).orElseThrow());
  }

  @Test
  void longRangePostconditionCannotProveSuccessUsingOldLight() {
    var rules =
        RuleBook.parse(
            """
      {"schema":1,"rules":[{"id":"test:light","before":{"block":"minecraft:stone"},
       "after":{"block":"minecraft:air"},"action":"break",
       "requires":"offset(2,0,0,light(0..15))","verify":"offset(2,0,0,light(0..15))"}]}
      """,
            Compiler.symbolic());
    assertInstanceOf(
        Planner.Prediction.Unknown.class,
        rules.oracle(facts()).predict(POS, STONE, new TargetCell.Clear()));
  }
}
