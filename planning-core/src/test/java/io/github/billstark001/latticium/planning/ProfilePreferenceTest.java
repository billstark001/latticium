package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.*;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProfilePreferenceTest {
  private static final ResourceId STONE = ResourceId.parse("minecraft:stone");
  private static final ResourceId DIRT = ResourceId.parse("minecraft:dirt");
  private static final Registry REGISTRY =
      new Registry() {
        public Resolution resolve(SetType kind, ResourceId id) {
          return Set.of(STONE, DIRT).contains(id) ? Resolution.FOUND : Resolution.MISSING;
        }

        public Resolution resolveTag(SetType kind, ResourceId id) {
          return Resolution.MISSING;
        }

        public Set<ResourceId> tag(SetType kind, ResourceId id) {
          return Set.of();
        }

        public Set<ResourceId> universe(SetType kind) {
          return Set.of(STONE, DIRT);
        }

        public Set<BlockState> states(ResourceId block) {
          return Set.of();
        }
      };

  @Test
  void rejectsPreferenceDefinitelyOutsideBoundItemSet() {
    var reader = new ProfileReader();
    var profile =
        reader.read(
            """
            {"schema":1,"id":"test:prefer","scope":"box(0,0,0,0,0,0)",
             "select":{"where":"all()"},
             "target":{"items":"i{minecraft:stone}",
               "choose":{"prefer":["minecraft:dirt"]}}}
            """);
    assertDoesNotThrow(() -> reader.bind(profile, Compiler.symbolic()));
    var error =
        assertThrows(ProfileReader.Error.class, () -> reader.bind(profile, new Compiler(REGISTRY)));
    assertTrue(error.getMessage().startsWith("/target/choose/prefer/0:"));
  }

  @Test
  void dynamicInventoryMembershipRemainsUndeterminedWithoutSnapshot() {
    var reader = new ProfileReader();
    var profile =
        reader.read(
            """
            {"schema":1,"id":"test:prefer","scope":"box(0,0,0,0,0,0)",
             "select":{"where":"all()"},
             "target":{"items":"inventory(i{minecraft:stone})",
               "choose":{"prefer":["minecraft:stone"]}}}
            """);
    assertDoesNotThrow(() -> reader.bind(profile, new Compiler(REGISTRY)));
  }
}
