package io.github.billstark001.latticium.mc;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.planning.Host;
import io.github.billstark001.latticium.planning.SectionScanner.Bounds;
import java.util.List;
import org.junit.jupiter.api.Test;

class BlueprintProvidersTest {
  @Test
  void failedBlueprintRegistrationCannotReplaceOrPartiallyRegisterATarget() {
    var providers = new BlueprintProviders();
    var id = ResourceId.parse("test:source");
    Host.TargetSource target = (p, s) -> new TargetCell.Clear();
    providers.registerTarget(id, target);
    var blueprint =
        new LatticiumClient.BlueprintProvider() {
          public TargetCell target(Position p, Host.SessionId s) {
            return new TargetCell.DontCare();
          }

          public List<Bounds> finiteBounds(String name, Host.SessionId s) {
            return List.of();
          }
        };
    assertThrows(IllegalArgumentException.class, () -> providers.registerBlueprint(id, blueprint));
    assertSame(target, providers.target(id));
    assertNull(providers.blueprint(id));
    assertFalse(providers.hasBlueprint());
    var oldIds = providers.blueprintIds();
    var other = ResourceId.parse("test:other");
    providers.registerBlueprint(other, blueprint);
    assertSame(blueprint, providers.target(other));
    assertSame(blueprint, providers.blueprint(other));
    assertTrue(oldIds.isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> providers.blueprintIds().clear());
  }
}
