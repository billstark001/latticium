package io.github.billstark001.latticium.planning;

import io.github.billstark001.latticium.dsl.Model.TargetCell;
import io.github.billstark001.latticium.planning.SectionScanner.Bounds;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

/** Defensive boundary between a job and an optional, independently versioned target provider. */
public final class TargetSources {
  private TargetSources() {}

  /** Explicit per-claimed-cell intent, not a frozen copy of an entire blueprint. */
  public static final class Pinned implements Host.TargetSource {
    private final Host.TargetSource source;
    private final Host.SessionId session;
    private final java.util.Map<io.github.billstark001.latticium.dsl.Model.Position, TargetCell>
        cells = new java.util.HashMap<>();

    public Pinned(Host.TargetSource source, Host.SessionId session) {
      this.source = guarded(source, session);
      this.session = session;
    }

    public void pin(
        io.github.billstark001.latticium.dsl.Model.Position position, TargetCell target) {
      if (target instanceof TargetCell.Unknown || target instanceof TargetCell.DontCare)
        throw new IllegalArgumentException("Only a concrete target can be pinned");
      cells.putIfAbsent(position, Objects.requireNonNull(target));
    }

    public void forget(io.github.billstark001.latticium.dsl.Model.Position position) {
      cells.remove(position);
    }

    @Override
    public TargetCell target(
        io.github.billstark001.latticium.dsl.Model.Position position, Host.SessionId requested) {
      if (!session.equals(requested))
        return new TargetCell.Unknown("Pinned target session changed");
      var pinned = cells.get(position);
      return pinned == null ? source.target(position, requested) : pinned;
    }
  }

  /** Keeps provider failures and stale sessions explicit rather than aborting a client tick. */
  public static Host.TargetSource guarded(Host.TargetSource provider, Host.SessionId session) {
    Objects.requireNonNull(provider, "provider");
    Objects.requireNonNull(session, "session");
    return new Host.TargetSource() {
      @Override
      public Optional<Host.SourceRevision> revision(Host.SessionId requestedSession) {
        if (!session.equals(requestedSession)) return Optional.empty();
        try {
          var revision = provider.revision(session);
          return revision == null ? Optional.empty() : revision;
        } catch (RuntimeException unavailable) {
          return Optional.empty();
        }
      }

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
      public Optional<Host.SourceRevision> revision(Host.SessionId session) {
        return provider.revision(session);
      }

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
