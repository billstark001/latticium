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

  @Test
  void unknownHigherPriorityItemDefersTargetChoice() {
    var stone = ResourceId.parse("minecraft:stone");
    var dirt = ResourceId.parse("minecraft:dirt");
    var position = new Position(ResourceId.parse("minecraft:overworld"), 0, 0, 0);
    var current = new BlockState(ResourceId.parse("minecraft:air"), Map.of());
    var items =
        new Compiler.Bound(
            SetType.ITEM,
            (facts, pos, value) -> value.equals(stone) ? Truth.UNKNOWN : Truth.TRUE,
            0);
    var selector = new MaterialSelector();
    var oracle =
        (MaterialSelector.Oracle)
            (item, pos) ->
                new MaterialSelector.Outcome.States(Set.of(new BlockState(item, Map.of())));
    assertInstanceOf(
        MaterialSelector.Choice.Deferred.class,
        selector.choose(
            position, current, items, null, null, Map.of(stone, 10, dirt, 1), List.of(), oracle));
    var allItems = new Compiler.Bound(SetType.ITEM, (facts, pos, value) -> Truth.TRUE, 0);
    assertInstanceOf(
        MaterialSelector.Choice.Deferred.class,
        selector.choose(
            position,
            current,
            allItems,
            null,
            null,
            Map.of(stone, 10, dirt, 1),
            List.of(),
            (item, pos) ->
                item.equals(stone)
                    ? new MaterialSelector.Outcome.Unknown("Placement not captured")
                    : oracle.statesFor(item, pos)));
    assertEquals(
        dirt,
        assertInstanceOf(
                MaterialSelector.Choice.Frozen.class,
                selector.choose(
                    position,
                    current,
                    items,
                    null,
                    null,
                    Map.of(stone, 1, dirt, 10),
                    List.of(),
                    oracle))
            .item());
  }

  @Test
  void alreadyAcceptedCurrentStateWinsOverMaterialPreference() {
    var stone = ResourceId.parse("minecraft:stone");
    var dirt = ResourceId.parse("minecraft:dirt");
    var current = new BlockState(dirt, Map.of());
    var position = new Position(ResourceId.parse("minecraft:overworld"), 0, 0, 0);
    var allItems = new Compiler.Bound(SetType.ITEM, (facts, pos, value) -> Truth.TRUE, 0);
    var choice =
        new MaterialSelector()
            .choose(
                position,
                current,
                allItems,
                null,
                null,
                Map.of(stone, 10, dirt, 1),
                List.of(stone),
                (item, pos) ->
                    new MaterialSelector.Outcome.States(Set.of(new BlockState(item, Map.of()))));
    var frozen = assertInstanceOf(MaterialSelector.Choice.Frozen.class, choice);
    assertEquals(dirt, frozen.item());
    assertEquals(current, frozen.target().state());
    var withUnknownPreference =
        new MaterialSelector()
            .choose(
                position,
                current,
                allItems,
                null,
                null,
                Map.of(stone, 10, dirt, 1),
                List.of(stone),
                (item, pos) ->
                    item.equals(stone)
                        ? new MaterialSelector.Outcome.Unknown("Missing placement facts")
                        : new MaterialSelector.Outcome.States(Set.of(current)));
    assertEquals(
        dirt, assertInstanceOf(MaterialSelector.Choice.Frozen.class, withUnknownPreference).item());
  }

  @Test
  void itemPredicateUsesTheSameInventoryAsAvailabilityOrdering() {
    var stone = ResourceId.parse("minecraft:stone");
    var position = new Position(ResourceId.parse("minecraft:overworld"), 0, 0, 0);
    var current = new BlockState(ResourceId.parse("minecraft:air"), Map.of());
    var items = Compiler.symbolic().compile("inventory(i{minecraft:stone})", SetType.ITEM);
    var choice =
        new MaterialSelector()
            .choose(
                position,
                current,
                items,
                null,
                null,
                Map.of(stone, 1),
                List.of(),
                (item, pos) ->
                    new MaterialSelector.Outcome.States(Set.of(new BlockState(stone, Map.of()))));
    assertEquals(stone, assertInstanceOf(MaterialSelector.Choice.Frozen.class, choice).item());
  }
}
