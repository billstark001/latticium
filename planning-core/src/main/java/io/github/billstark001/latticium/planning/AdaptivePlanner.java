package io.github.billstark001.latticium.planning;

import io.github.billstark001.latticium.dsl.Model.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thread-confined retry budgets for hosts whose placement oracle reads fresh inputs each tick. This
 * object retains scheduling metadata, never an oracle, captured facts or search frontier.
 */
public final class AdaptivePlanner {
  /** Actual oracle work, so callers can share a hard tick budget with diagnostic searches. */
  public record Attempt(Planner.Result result, int expandedNodes) {}

  private record Input(BlockState current, TargetCell target, Profile.Policy policy) {}

  private record Retry(Input input, int nodes) {}

  private final int initialNodes;
  private final int maxNodes;
  private final int maxRemembered;
  private final Map<Position, Retry> retries = new LinkedHashMap<>(16, 0.75f, true);

  public AdaptivePlanner(int initialNodes, int maxNodes, int maxRemembered) {
    if (initialNodes <= 0 || maxNodes < initialNodes || maxRemembered <= 0)
      throw new IllegalArgumentException("Positive, ordered planning budgets required");
    this.initialNodes = initialNodes;
    this.maxNodes = maxNodes;
    this.maxRemembered = maxRemembered;
  }

  /**
   * Rebuilds the search from fresh facts on every call. Only a resumable node-budget deferral
   * increases the next attempt, doubling up to the configured cap. Missing facts never prove
   * NoPlan. The returned expansion count never exceeds the caller's remaining tick budget. Eviction
   * forgets a retry budget, not a target, action, or safety precondition.
   */
  public Attempt plan(
      Position pos,
      BlockState current,
      TargetCell target,
      Profile.Policy policy,
      Planner.Oracle oracle,
      int remainingTickNodes) {
    if (remainingTickNodes <= 0)
      throw new IllegalArgumentException("Positive tick budget required");
    var input = new Input(current, target, policy);
    var retry = retries.get(pos);
    int nodes = retry != null && input.equals(retry.input()) ? retry.nodes() : initialNodes;
    var search = new Planner().start(pos, current, target, policy, oracle);
    var result = search.advance(Math.min(nodes, remainingTickNodes));
    if (search.pending()) {
      int next = (int) Math.min(maxNodes, (long) nodes * 2);
      retries.put(pos, new Retry(input, next));
      if (retries.size() > maxRemembered) retries.remove(retries.keySet().iterator().next());
    } else retries.remove(pos);
    return new Attempt(result, Math.toIntExact(search.expandedNodes()));
  }

  /** Drops work scheduling metadata when a candidate is removed or explicitly refreshed. */
  public void forget(Position position) {
    retries.remove(position);
  }

  public void clear() {
    retries.clear();
  }
}
