package io.github.billstark001.latticium.mc;

import static io.github.billstark001.latticium.dsl.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.planning.Profile;
import io.github.billstark001.latticium.planning.ProfileReader;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CommandProfilesTest {
  @Test
  void commandProfilesHaveValidTypedTargetsAndPolicies() {
    var reader = new ProfileReader();
    var fill = reader.read(CommandProfiles.fill(ResourceId.parse("minecraft:stone")));
    var replace =
        reader.read(
            CommandProfiles.replace(
                ResourceId.parse("minecraft:dirt"), ResourceId.parse("minecraft:stone")));
    var clear = reader.read(CommandProfiles.clear());
    var query = reader.read(CommandProfiles.query("has_target() | current(s{minecraft:stone})"));

    assertEquals(
        "current(b{minecraft:air,minecraft:cave_air,minecraft:void_air})", fill.select().where());
    assertEquals("{minecraft:stone}", ((Profile.Items) fill.target()).expression());
    assertEquals(Profile.Policy.BreakMode.DENY, fill.policy().breakMode());
    assertEquals("current(s{minecraft:dirt})", replace.select().where());
    assertEquals(Profile.Policy.BreakMode.SELECTED, replace.policy().breakMode());
    assertEquals("all()", clear.select().where());
    assertEquals(Profile.Clear.class, clear.target().getClass());
    assertEquals("selection(\"build\")", query.scope());
    assertEquals("has_target() | current(s{minecraft:stone})", query.select().where());
    assertEquals(
        ResourceId.parse("latticium:query_unknown"), ((Profile.Source) query.target()).id());
  }

  @Test
  void fillSelectsEveryAirVariantAndPreservesNonAirBlocks() {
    var profile = new ProfileReader().read(CommandProfiles.fill(ResourceId.parse("stone")));
    assertEquals(Profile.Policy.BreakMode.DENY, profile.policy().breakMode());
    var predicate = Compiler.symbolic().compile(profile.select().where(), SetType.POS);
    var pos = new Position(ResourceId.parse("overworld"), 0, 0, 0);
    for (var air : vanillaAirBlocks()) assertEquals(Truth.TRUE, predicate.at(facts(air), pos));
    assertEquals(Truth.FALSE, predicate.at(facts(ResourceId.parse("stone")), pos));
    assertEquals(Truth.UNKNOWN, predicate.at(facts(null), pos));
  }

  private static Facts facts(ResourceId block) {
    return new Facts() {
      public Optional<WorldCell> world(Position pos) {
        return block == null
            ? Optional.empty()
            : Optional.of(new WorldCell(new BlockState(block, Map.of()), null, null, null, null));
      }

      public TargetCell target(Position pos) {
        return new TargetCell.Unknown("not captured");
      }

      public Optional<Position> player() {
        return Optional.empty();
      }

      public Optional<Set<ResourceId>> inventory() {
        return Optional.empty();
      }

      public Truth selection(String name, Position pos) {
        return Truth.UNKNOWN;
      }
    };
  }
}
