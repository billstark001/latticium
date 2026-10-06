package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Model.ResourceId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class SelectionBoundsTest {
  @Test
  void callerEditsCannotAlterASnapshotOrItsReturnedLists() {
    var box = new SectionScanner.Bounds(ResourceId.parse("overworld"), 0, 0, 0, 15, 15, 15);
    var boxes = new ArrayList<>(List.of(box));
    var source = new HashMap<String, List<SectionScanner.Bounds>>();
    source.put("build", boxes);
    var snapshot = SelectionBounds.copy(source);
    boxes.clear();
    source.clear();
    assertEquals(List.of(box), snapshot.get("build"));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
    assertThrows(UnsupportedOperationException.class, () -> snapshot.get("build").clear());
  }
}
