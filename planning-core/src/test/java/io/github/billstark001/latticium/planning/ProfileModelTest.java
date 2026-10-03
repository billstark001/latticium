package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.ResourceId;
import io.github.billstark001.latticium.dsl.Model.SetType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProfileModelTest {
  @Test
  void directProfilesRequireTheirCoreFieldsAndCopyImports() {
    var id = ResourceId.parse("test:profile");
    var uses = new ArrayList<>(List.of(ResourceId.parse("test:module")));
    var select = new Profile.Select("all()", Profile.Select.Choose.NEAREST);
    var policy = new Profile.Policy(Profile.Policy.BreakMode.DENY, 1, 1);
    var profile =
        new Profile(1, id, uses, null, "box(0,0,0,0,0,0)", select, new Profile.Clear(), policy);
    uses.clear();
    assertEquals(1, profile.uses().size());
    assertThrows(
        IllegalArgumentException.class,
        () -> new Profile(2, id, List.of(), null, "all()", select, new Profile.Clear(), policy));
    assertThrows(
        NullPointerException.class,
        () -> new Profile(1, id, List.of(), null, null, select, new Profile.Clear(), policy));
    assertThrows(NullPointerException.class, () -> new Profile.Items(null, null, List.of()));
    assertThrows(NullPointerException.class, () -> new Profile.Select("all()", null));
    var compiler = Compiler.symbolic();
    var positions = compiler.compile("all()", SetType.POS);
    assertDoesNotThrow(() -> new Profile.Bound(profile, null, positions, positions, null, null));
    var itemProfile =
        new Profile(
            1,
            id,
            List.of(),
            null,
            "box(0,0,0,0,0,0)",
            select,
            new Profile.Items("i{minecraft:stone}", null, List.of()),
            policy);
    assertThrows(
        IllegalArgumentException.class,
        () -> new Profile.Bound(itemProfile, null, positions, positions, null, null));
    var items = compiler.compile("i{minecraft:stone}", SetType.ITEM);
    assertThrows(
        IllegalArgumentException.class,
        () -> new Profile.Bound(itemProfile, null, items, positions, items, null));
  }
}
