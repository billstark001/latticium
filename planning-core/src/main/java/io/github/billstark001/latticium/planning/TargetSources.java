package io.github.billstark001.latticium.planning;

import io.github.billstark001.latticium.dsl.Model.TargetCell;
import java.util.Objects;

/** Defensive boundary between a job and an optional, independently versioned target provider. */
public final class TargetSources {
  private TargetSources() {}

  /** Keeps provider failures and stale sessions explicit rather than aborting a client tick. */
  public static Host.TargetSource guarded(Host.TargetSource provider, Host.SessionId session) {
    Objects.requireNonNull(provider, "provider");
    Objects.requireNonNull(session, "session");
    return (position, requestedSession) -> {
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
    };
  }
}
