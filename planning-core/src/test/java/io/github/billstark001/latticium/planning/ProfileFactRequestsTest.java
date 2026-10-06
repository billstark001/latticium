package io.github.billstark001.latticium.planning;

import static io.github.billstark001.latticium.dsl.FactDependencies.Fact.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Compiler;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProfileFactRequestsTest {
  @Test
  void targetFreeScanSkipsStateAndInventoryButPlanningRequestsBothWhenNeeded() {
    var reader = new ProfileReader();
    var bound =
        reader.bind(
            reader.read(
                """
        {"schema":1,"id":"test:columns","scope":"box(0,0,0,10,10,10)",
         "select":{"where":"sphere(player,2) & light(0..15)"},
         "target":{"items":"inventory(i{stone})"}}
        """),
            Compiler.symbolic());
    assertEquals(Set.of(PLAYER, LIGHT), bound.scanDependencies().facts());
    assertEquals(Set.of(STATE, PLAYER, LIGHT, INVENTORY), bound.planningDependencies().facts());
    assertEquals(0, bound.planningRadius());
  }

  @Test
  void selectionAndTargetReadsKeepTheirNeighborHalo() {
    var reader = new ProfileReader();
    var bound =
        reader.bind(
            reader.read(
                """
        {"schema":1,"id":"test:target_columns","scope":"selection(\\\"build\\\")",
         "select":{"where":"offset(3,0,0,adjacent(has_target()))"},
         "target":{"source":"test:blueprint"}}
        """),
            Compiler.symbolic());
    assertEquals(Set.of(SELECTION, TARGET), bound.scanDependencies().facts());
    assertEquals(Set.of(STATE, SELECTION, TARGET), bound.planningDependencies().facts());
    assertEquals(4, bound.planningRadius());
  }
}
