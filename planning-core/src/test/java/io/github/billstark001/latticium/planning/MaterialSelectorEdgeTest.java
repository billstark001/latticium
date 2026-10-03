package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MaterialSelectorEdgeTest {
  @Test
  void materialChoiceUsesAvailabilityAndVerifiableStates() {
    var position = new Position(ResourceId.parse("minecraft:overworld"), 0, 0, 0);
    var current = new BlockState(ResourceId.parse("minecraft:air"), Map.of());
    var stone = ResourceId.parse("minecraft:stone");
    var dirt = ResourceId.parse("minecraft:dirt");
    var items = Compiler.symbolic().compile("i{minecraft:stone,minecraft:dirt}", SetType.ITEM);
    Facts facts =
        new Facts() {
          public Optional<WorldCell> world(Position pos) {
            return Optional.empty();
          }

          public TargetCell target(Position pos) {
            return new TargetCell.DontCare();
          }

          public Optional<Position> player() {
            return Optional.empty();
          }

          public Optional<Set<ResourceId>> inventory() {
            return Optional.of(Set.of(stone, dirt));
          }

          public Truth selection(String name, Position pos) {
            return Truth.FALSE;
          }
        };
    var choice =
        new MaterialSelector()
            .choose(
                position,
                current,
                items,
                null,
                facts,
                Map.of(stone, 4, dirt, 9),
                List.of(stone),
                (item, pos) ->
                    new MaterialSelector.Outcome.States(Set.of(new BlockState(item, Map.of()))));
    assertEquals(stone, assertInstanceOf(MaterialSelector.Choice.Frozen.class, choice).item());
  }

  @Test
  void unsupportedPlacementCapabilityIsNotReportedAsNoTarget() {
    var item = ResourceId.parse("minecraft:stone");
    var position = new Position(ResourceId.parse("minecraft:overworld"), 0, 0, 0);
    var current = new BlockState(ResourceId.parse("minecraft:air"), Map.of());
    var items = new Compiler.Bound(SetType.ITEM, (facts, pos, value) -> Truth.TRUE, 0);
    var selector = new MaterialSelector();
    var choice =
        selector.choose(
            position,
            current,
            items,
            null,
            null,
            Map.of(item, 1),
            List.of(),
            (id, pos) -> new MaterialSelector.Outcome.Unsupported("No placement adapter"));
    assertEquals(
        "No placement adapter",
        assertInstanceOf(MaterialSelector.Choice.Unsupported.class, choice).reason());
  }
}
