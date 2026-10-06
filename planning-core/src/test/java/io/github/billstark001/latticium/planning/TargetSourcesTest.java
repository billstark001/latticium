package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Model.Position;
import io.github.billstark001.latticium.dsl.Model.ResourceId;
import io.github.billstark001.latticium.dsl.Model.TargetCell;
import org.junit.jupiter.api.Test;

class TargetSourcesTest {
  private static final Position POSITION =
      new Position(ResourceId.parse("minecraft:overworld"), 0, 64, 0);

  @Test
  void wrongSessionNeverCallsProvider() {
    var session = new Host.SessionId();
    var guarded =
        TargetSources.guarded((pos, requested) -> fail("Stale session reached provider"), session);
    assertInstanceOf(TargetCell.Unknown.class, guarded.target(POSITION, new Host.SessionId()));
  }

  @Test
  void providerFailuresBecomeExplicitUnknownTargets() {
    var session = new Host.SessionId();
    var absent = TargetSources.guarded((pos, requested) -> null, session);
    assertEquals(
        "Target provider returned null",
        assertInstanceOf(TargetCell.Unknown.class, absent.target(POSITION, session)).reason());
    var failed =
        TargetSources.guarded(
            (pos, requested) -> {
              throw new IllegalStateException("placement removed");
            },
            session);
    assertTrue(
        assertInstanceOf(TargetCell.Unknown.class, failed.target(POSITION, session))
            .reason()
            .contains("placement removed"));
  }

  @Test
  void optionalSlicesAreQualifiedAndFailuresFallBackToPoints() {
    var session = new Host.SessionId();
    var bounds = new SectionScanner.Bounds(POSITION.dimension(), 0, 64, 0, 0, 64, 0);
    var valid = TargetSlice.capture(session, bounds, p -> new TargetCell.Clear());
    var response =
        new java.util.concurrent.atomic.AtomicReference<java.util.Optional<TargetSlice>>(
            java.util.Optional.of(valid));
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    Host.TargetSource provider =
        new Host.TargetSource() {
          public TargetCell target(Position pos, Host.SessionId requested) {
            return new TargetCell.Clear();
          }

          public java.util.Optional<TargetSlice> slice(
              SectionScanner.Bounds window, Host.SessionId requested) {
            calls.incrementAndGet();
            return response.get();
          }
        };
    var guarded = TargetSources.guarded(provider, session);
    assertEquals(java.util.Optional.of(valid), guarded.slice(bounds, session));
    assertTrue(guarded.slice(bounds, new Host.SessionId()).isEmpty());
    assertEquals(1, calls.get());
    response.set(
        java.util.Optional.of(
            TargetSlice.capture(new Host.SessionId(), bounds, p -> new TargetCell.Clear())));
    assertTrue(guarded.slice(bounds, session).isEmpty());
    response.set(
        java.util.Optional.of(
            TargetSlice.capture(
                session,
                new SectionScanner.Bounds(POSITION.dimension(), 1, 64, 0, 1, 64, 0),
                p -> new TargetCell.Clear())));
    assertTrue(guarded.slice(bounds, session).isEmpty());
    response.set(null);
    assertTrue(guarded.slice(bounds, session).isEmpty());
    Host.TargetSource throwing =
        new Host.TargetSource() {
          public TargetCell target(Position pos, Host.SessionId requested) {
            return new TargetCell.Clear();
          }

          public java.util.Optional<TargetSlice> slice(
              SectionScanner.Bounds window, Host.SessionId requested) {
            throw new IllegalStateException("No batch support now");
          }
        };
    var fallback = TargetSources.guarded(throwing, session);
    assertTrue(fallback.slice(bounds, session).isEmpty());
    assertInstanceOf(TargetCell.Clear.class, fallback.target(POSITION, session));
    response.set(java.util.Optional.of(valid));
    var mapped = TargetSources.map(guarded, cell -> new TargetCell.DontCare());
    assertEquals(
        mapped.target(POSITION, session),
        mapped.slice(bounds, session).orElseThrow().target(POSITION));
  }
}
