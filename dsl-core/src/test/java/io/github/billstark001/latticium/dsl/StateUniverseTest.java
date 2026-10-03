package io.github.billstark001.latticium.dsl;

import static io.github.billstark001.latticium.dsl.Model.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StateUniverseTest {
  private static final ResourceId BLOCK = ResourceId.parse("minecraft:test_block");
  private static final BlockState LEGAL = new BlockState(BLOCK, Map.of("level", "1", "axis", "y"));
  private static final BlockState IMPOSSIBLE =
      new BlockState(BLOCK, Map.of("level", "2", "axis", "y"));

  @Test
  void statePredicatesStayWithinTheVersionRegistry() {
    Registry registry =
        new Registry() {
          public Resolution resolve(SetType kind, ResourceId id) {
            return id.equals(BLOCK) ? Resolution.FOUND : Resolution.MISSING;
          }

          public Resolution resolveTag(SetType kind, ResourceId id) {
            return Resolution.MISSING;
          }

          public Set<ResourceId> tag(SetType kind, ResourceId id) {
            return Set.of();
          }

          public Set<ResourceId> universe(SetType kind) {
            return Set.of(BLOCK);
          }

          public Set<BlockState> states(ResourceId block) {
            return block.equals(BLOCK) ? Set.of(LEGAL) : Set.of();
          }
        };
    var compiler = new Compiler(registry);
    for (String expression :
        new String[] {"states_of(b{test_block})", "property(level=2)", "property_range(level,2)"}) {
      var predicate = compiler.compile(expression, SetType.STATE);
      assertEquals(Truth.FALSE, predicate.contains(null, IMPOSSIBLE), expression);
    }
    assertEquals(
        Truth.TRUE,
        compiler.compile("states_of(b{test_block})", SetType.STATE).contains(null, LEGAL));
    assertEquals(
        Truth.TRUE,
        compiler.compile("property_range(level,1)", SetType.STATE).contains(null, LEGAL));
    assertThrows(
        Syntax.Failure.class, () -> compiler.compile("property_range(axis,1)", SetType.STATE));
    assertThrows(
        Syntax.Failure.class, () -> compiler.compile("property_range(missing,1)", SetType.STATE));
    assertThrows(
        Syntax.Failure.class,
        () -> compiler.targetAvailable(true).compile("compare(axis,lt)", SetType.POS));
  }
}
