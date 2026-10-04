package io.github.billstark001.latticium.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.billstark001.latticium.dsl.Model.ResourceId;
import io.github.billstark001.latticium.planning.Profile;
import io.github.billstark001.latticium.planning.ProfileReader;
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

    assertEquals("current(s{minecraft:air})", fill.select().where());
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
}
