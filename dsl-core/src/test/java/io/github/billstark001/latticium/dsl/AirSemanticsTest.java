package io.github.billstark001.latticium.dsl;

import static io.github.billstark001.latticium.dsl.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AirSemanticsTest {
  @Test
  void caveAirCountsAsAirForSurfaceAndClearTarget() {
    var dimension = ResourceId.parse("minecraft:overworld");
    var stonePos = new Position(dimension, 0, 0, 0);
    var cavePos = stonePos.offset(1, 0, 0);
    var stone = new BlockState(ResourceId.parse("minecraft:stone"), Map.of());
    var caveAir = new BlockState(ResourceId.parse("minecraft:cave_air"), Map.of());
    Facts facts =
        new Facts() {
          public Optional<WorldCell> world(Position pos) {
            if (pos.equals(stonePos))
              return Optional.of(new WorldCell(stone, null, null, null, true));
            if (pos.equals(cavePos))
              return Optional.of(new WorldCell(caveAir, null, null, null, false));
            return Optional.empty();
          }

          public TargetCell target(Position pos) {
            return new TargetCell.Clear();
          }

          public Optional<Position> player() {
            return Optional.empty();
          }

          public Optional<Set<ResourceId>> inventory() {
            return Optional.empty();
          }

          public Truth selection(String name, Position pos) {
            return Truth.FALSE;
          }
        };
    var compiler = Compiler.symbolic().targetAvailable(true);
    assertEquals(Truth.TRUE, compiler.compile("surface()", SetType.POS).at(facts, stonePos));
    assertEquals(Truth.FALSE, compiler.compile("surface()", SetType.POS).at(facts, cavePos));
    assertEquals(Truth.TRUE, compiler.compile("matches_target()", SetType.POS).at(facts, cavePos));
    assertTrue(isVanillaAir(new BlockState(ResourceId.parse("minecraft:void_air"), Map.of())));
    assertFalse(
        isVanillaAir(new BlockState(ResourceId.parse("minecraft:air"), Map.of("bogus", "true"))));
  }
}
