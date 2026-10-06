package io.github.billstark001.latticium.mc;

/** Immutable presentation data; satisfied candidates are not a count of placed blocks. */
public record ClientJobSnapshot(
    String profileId,
    Phase phase,
    boolean paused,
    boolean waiting,
    boolean continuous,
    int scannedSections,
    int totalSections,
    int pendingCandidates,
    int satisfiedCandidates,
    int submittedActions,
    int actionLimit,
    int deferredReads,
    int unsupportedSections,
    int dirtyReads,
    int refreshRemaining,
    int blockedCandidates) {
  public enum Phase {
    SCANNING,
    RUNNING,
    WAITING,
    PAUSED,
    MONITORING,
    COMPLETE,
    BLOCKED,
    BUDGET_EXHAUSTED
  }

  static Phase phase(
      boolean paused,
      boolean waiting,
      boolean exhausted,
      boolean scanWork,
      boolean continuous,
      int pending,
      int blocked,
      int unsupported) {
    if (waiting) return Phase.WAITING;
    if (exhausted) return Phase.BUDGET_EXHAUSTED;
    if (paused) return Phase.PAUSED;
    if (scanWork) return Phase.SCANNING;
    if (pending > 0) return Phase.RUNNING;
    if (blocked > 0 || unsupported > 0) return Phase.BLOCKED;
    return continuous ? Phase.MONITORING : Phase.COMPLETE;
  }
}
