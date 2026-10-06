package io.github.billstark001.latticium.planning;

import io.github.billstark001.latticium.dsl.Model.TargetCell;
import io.github.billstark001.latticium.planning.SectionScanner.Bounds;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

/** Defensive boundary between a job and an optional, independently versioned target provider. */
public final class TargetSources {
  private TargetSources() {}

  /** Keeps provider failures and stale sessions explicit rather than aborting a client tick. */
  public static Host.TargetSource guarded(Host.TargetSource provider, Host.SessionId session) {
    Objects.requireNonNull(provider, "provider");
    Objects.requireNonNull(session, "session");
    return new Host.TargetSource() {
      @Override
      public TargetCell target(
          io.github.billstark001.latticium.dsl.Model.Position position,
          Host.SessionId requestedSession) {
        if (!session.equals(requestedSession))
          return new TargetCell.Unknown("Target session changed");
        try {
          var target = provider.target(position, requestedSession);
          return target == null ? new TargetCell.Unknown("Target provider returned null") : target;
        } catch (RuntimeException error) {
          String message = error.getMessage();
          return new TargetCell.Unknown(
              "Target provider failed: "
                  + error.getClass().getSimpleName()
                  + (message == null || message.isBlank() ? "" : ": " + message));
        }
      }

      @Override
      public Optional<TargetSlice> slice(Bounds bounds, Host.SessionId requestedSession) {
        return session.equals(requestedSession)
            ? checkedSlice(provider, bounds, session)
            : Optional.empty();
      }
    };
  }

  /** Optional acceleration never changes the point-provider contract, even on failure. */
  public static Optional<TargetSlice> checkedSlice(
      Host.TargetSource provider, Bounds bounds, Host.SessionId session) {
    try {
      var captured = provider.slice(bounds, session);
      return captured == null
          ? Optional.empty()
          : captured.filter(
              slice -> session.equals(slice.session()) && bounds.equals(slice.bounds()));
    } catch (RuntimeException error) {
      return Optional.empty();
    }
  }

  /** Applies the same target policy to point reads and optional captured windows. */
  public static Host.TargetSource map(
      Host.TargetSource provider, UnaryOperator<TargetCell> transform) {
    Objects.requireNonNull(provider, "provider");
    Objects.requireNonNull(transform, "transform");
    return new Host.TargetSource() {
      @Override
      public TargetCell target(
          io.github.billstark001.latticium.dsl.Model.Position position, Host.SessionId session) {
        return transform.apply(provider.target(position, session));
      }

      @Override
      public Optional<TargetSlice> slice(Bounds bounds, Host.SessionId session) {
        return checkedSlice(provider, bounds, session).map(captured -> captured.map(transform));
      }
    };
  }
}
