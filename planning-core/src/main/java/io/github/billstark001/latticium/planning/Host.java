package io.github.billstark001.latticium.planning;

import io.github.billstark001.latticium.dsl.Model.*;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Neutral contracts for the client adapters and fake offline hosts. */
public final class Host {
  private Host() {}

  /** Identity for one capture or job lifetime; stale snapshots must not cross sessions. */
  public record SessionId(UUID value) {
    public SessionId {
      Objects.requireNonNull(value, "value");
    }

    public SessionId() {
      this(UUID.randomUUID());
    }
  }

  public record Epochs(
      long registry, long world, long target, long selection, long inventory, long rules) {}

  /** Session and epochs with optional facts for action-only rechecks. */
  public record Snapshot(SessionId session, Epochs epochs, Facts facts) {
    public Snapshot {
      Objects.requireNonNull(session, "session");
      Objects.requireNonNull(epochs, "epochs");
    }
  }

  /** Session-qualified section identity for capture and cache keys. */
  public record SectionCaptureKey(SessionId session, SectionScanner.SectionKey section) {
    public SectionCaptureKey {
      Objects.requireNonNull(session, "session");
      Objects.requireNonNull(section, "section");
    }
  }

  /** Unloaded means no section snapshot yet; Deferred may become ready; Unsupported cannot. */
  public sealed interface Capture
      permits Capture.Ready, Capture.Unloaded, Capture.Deferred, Capture.Unsupported {
    record Ready(Snapshot snapshot) implements Capture {
      public Ready {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.facts() == null)
          throw new IllegalArgumentException("Ready capture requires facts");
      }
    }

    record Unloaded() implements Capture {}

    record Deferred(String reason) implements Capture {
      public Deferred {
        Objects.requireNonNull(reason, "reason");
      }
    }

    record Unsupported(String fact) implements Capture {
      public Unsupported {
        Objects.requireNonNull(fact, "fact");
      }
    }
  }

  public interface SectionSnapshotSource {
    /** Captures immutable facts for one section and its requested neighbor halo. */
    Capture capture(SectionCaptureKey key, int halo);
  }

  public interface SelectionSource {
    /** Returns finite bounds for one named selection in the requested session. */
    List<SectionScanner.Bounds> finiteBounds(String selectionId, SessionId session);
  }

  public interface TargetSource {
    /** Returns a target cell or an explicit unknown result for unavailable target data. */
    TargetCell target(Position pos, SessionId session);
  }

  public record Preconditions(
      SessionId session,
      Epochs epochs,
      Position position,
      BlockState expectedCurrent,
      TargetCell expectedTarget) {
    public Preconditions {
      Objects.requireNonNull(session, "session");
      Objects.requireNonNull(epochs, "epochs");
      Objects.requireNonNull(position, "position");
      Objects.requireNonNull(expectedCurrent, "expectedCurrent");
      Objects.requireNonNull(expectedTarget, "expectedTarget");
    }
  }

  public record Receipt(UUID id, SessionId session) {
    public Receipt {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(session, "session");
    }
  }

  /** Accepted means sent for observation, not that the expected world state exists. */
  public sealed interface Submission
      permits Submission.Accepted, Submission.Stale, Submission.Deferred, Submission.Rejected {
    record Accepted(Receipt receipt) implements Submission {
      public Accepted {
        Objects.requireNonNull(receipt, "receipt");
      }
    }

    record Stale(String reason) implements Submission {
      public Stale {
        Objects.requireNonNull(reason, "reason");
      }
    }

    record Deferred(String reason) implements Submission {
      public Deferred {
        Objects.requireNonNull(reason, "reason");
      }
    }

    record Rejected(String reason) implements Submission {
      public Rejected {
        Objects.requireNonNull(reason, "reason");
      }
    }
  }

  public interface ActionGateway {
    /** Rechecks every precondition before sending an action; acceptance is not confirmation. */
    Submission submit(Planner.Proposal step, Preconditions preconditions, Profile.Policy policy);
  }

  public enum Observation {
    CONFIRMED,
    STILL_PENDING,
    CONTRADICTED,
    TIMED_OUT
  }

  public interface ObservationSource {
    /** Reports whether the accepted action has produced its expected world state. */
    Observation observe(Receipt receipt, BlockState expected);
  }
}
