package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.planning.SectionScanner.Bounds;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TargetSliceTest {
  private static final ResourceId DIM = ResourceId.parse("minecraft:overworld");

  @Test
  void indexedCellsRoundTripNegativeAndIntegerEdgeCoordinates() {
    for (int start : new int[] {-18, Integer.MIN_VALUE, Integer.MAX_VALUE - 3}) {
      var bounds = new Bounds(DIM, start, -2, -3, start + 3, 1, 0);
      var slice =
          TargetSlice.capture(
              new Host.SessionId(), bounds, p -> new TargetCell.Unknown(p.toString()));
      for (long z = -3; z <= 0; z++)
        for (long y = -2; y <= 1; y++)
          for (long x = start; x <= ((long) start + 3); x++) {
            var p = new Position(DIM, (int) x, (int) y, (int) z);
            assertEquals(new TargetCell.Unknown(p.toString()), slice.target(p));
          }
      assertInstanceOf(TargetCell.Unknown.class, slice.target(new Position(DIM, start, 2, 0)));
      assertInstanceOf(
          TargetCell.Unknown.class,
          slice.target(new Position(ResourceId.parse("test:other"), start, 0, 0)));
    }
  }

  @Test
  void rejectsOversizedOrMismatchedWindowsWithoutOverflow() {
    var session = new Host.SessionId();
    var one = new Bounds(DIM, 0, 0, 0, 0, 0, 0);
    assertThrows(IllegalArgumentException.class, () -> new TargetSlice(session, one, List.of()));
    assertThrows(
        NullPointerException.class,
        () -> new TargetSlice(session, one, java.util.Arrays.asList((TargetCell) null)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TargetSlice.capture(
                session,
                new Bounds(
                    DIM,
                    Integer.MIN_VALUE,
                    Integer.MIN_VALUE,
                    Integer.MIN_VALUE,
                    Integer.MAX_VALUE,
                    Integer.MAX_VALUE,
                    Integer.MAX_VALUE),
                p -> fail("Reader must not run")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TargetSlice.capture(
                session, new Bounds(DIM, 0, 0, 0, 48, 47, 47), p -> fail("Reader must not run")));
    assertEquals(
        TargetSlice.MAX_CELLS,
        TargetSlice.capture(
                session, new Bounds(DIM, 0, 0, 0, 47, 47, 47), p -> new TargetCell.DontCare())
            .cells()
            .size());
  }

  @Test
  void frozenCellsAndMappedPolicyDoNotRetainMutableInputs() {
    var cells = new ArrayList<TargetCell>(List.of(new TargetCell.Clear()));
    var slice = new TargetSlice(new Host.SessionId(), new Bounds(DIM, 0, 0, 0, 0, 0, 0), cells);
    cells.clear();
    assertThrows(UnsupportedOperationException.class, () -> slice.cells().clear());
    var mapped = slice.map(cell -> new TargetCell.DontCare());
    assertEquals(slice.bounds(), mapped.bounds());
    assertEquals(slice.session(), mapped.session());
    assertInstanceOf(TargetCell.Clear.class, slice.cells().getFirst());
    assertInstanceOf(TargetCell.DontCare.class, mapped.cells().getFirst());
  }
}
