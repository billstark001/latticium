package io.github.billstark001.latticium.mc;

import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.planning.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** Development-only adversarial predictor exercising the real native job and submission path. */
public final class NativePlanningProbe implements AutoCloseable {
  private final ClientJob job;
  private final InstrumentedOracle oracle;
  private int peakWork;
  private int attempts;
  private long maxNanos;
  private int peakRetries;
  private final int retryCapacity;

  public NativePlanningProbe(BlockPos at, int leaves, int failureMode, BlockState goal) {
    this(at, leaves, failureMode, goal, false);
  }

  public NativePlanningProbe(
      BlockPos at, int leaves, int failureMode, BlockState goal, boolean pressure) {
    retryCapacity = pressure ? 1 : 256;
    var minecraft = Minecraft.getInstance();
    var position =
        new Position(
            MinecraftStateCodec.id(minecraft.level.dimension().identifier()),
            at.getX(),
            at.getY(),
            at.getZ());
    oracle = new InstrumentedOracle(minecraft, leaves, failureMode);
    String json =
        """
        {"schema":1,"id":"test:native_budget","scope":"box(%d,%d,%d,%d,%d,%d)",
         "select":{"where":"all()"},"target":{"source":"test:goal"},
         "policy":{"break":"selected","refresh":"manual","max_actions_per_activation":8},
         "execution":{"planning":{"retry_ticks":1,"max_retry_entries":%d}}}
        """
            .formatted(
                at.getX(),
                at.getY(),
                at.getZ(),
                at.getX(),
                at.getY(),
                at.getZ() + (pressure ? 1 : 0),
                pressure ? 1 : 256);
    Host.TargetSource targets = (p, s) -> new TargetCell.Exact(goal);
    job =
        (ClientJob)
            NativeAccess.construct(
                "ClientJob",
                new Class<?>[] {
                  Minecraft.class,
                  Host.SessionId.class,
                  String.class,
                  Host.TargetSource.class,
                  Map.class,
                  MinecraftPlacementOracle.class
                },
                minecraft,
                new Host.SessionId(),
                json,
                targets,
                Map.of(),
                oracle);
  }

  public boolean tick() {
    int before = oracle.calls;
    long started = System.nanoTime();
    job.tick();
    maxNanos = Math.max(maxNanos, System.nanoTime() - started);
    var planning = NativeAccess.field(job, "planning");
    var adaptive = NativeAccess.field(planning, "adaptive");
    int remembered = ((Map<?, ?>) NativeAccess.field(adaptive, "retries")).size();
    peakRetries = Math.max(peakRetries, remembered);
    if (remembered > retryCapacity)
      throw new AssertionError("Native retry cache exceeded capacity");
    int work = oracle.calls - before;
    if (work > 0) attempts++;
    peakWork = Math.max(peakWork, work);
    if (work > 1024) throw new AssertionError("Native tick exceeded predictor node cap: " + work);
    if (attempts < 3 && job.snapshot().submittedActions() != 0)
      throw new AssertionError("Native job acted before the adaptive search found its solution");
    return job.isFinished();
  }

  public int attempts() {
    return attempts;
  }

  public int submitted() {
    return job.snapshot().submittedActions();
  }

  public int peakWork() {
    return peakWork;
  }

  public String status() {
    return job.status();
  }

  public long maxNanos() {
    return maxNanos;
  }

  public int peakRetries() {
    return peakRetries;
  }

  @Override
  public void close() {
    job.leaveWorld();
  }

  private static final class InstrumentedOracle extends MinecraftPlacementOracle {
    private final int leaves;
    private final int failureMode;
    private int calls;

    InstrumentedOracle(Minecraft minecraft, int leaves, int failureMode) {
      super(minecraft);
      this.leaves = leaves;
      this.failureMode = failureMode;
    }

    @Override
    public Planner.Prediction predict(Position pos, BlockState current, TargetCell goal) {
      calls++;
      if (failureMode == 2) throw new IllegalStateException("Instrumented predictor failure");
      if (failureMode == 3)
        return new Planner.Prediction.NoLegalPlacement("Require post-break prediction");
      if (current.block().equals(ResourceId.parse("test:unreachable_leaf")))
        return new Planner.Prediction.NoLegalPlacement("Instrumented leaf");
      var transitions = new ArrayList<Planner.Proposal>();
      var normal = super.predict(pos, current, goal);
      if (normal instanceof Planner.Prediction.Proposals proposals)
        for (var step : proposals.steps()) {
          // Conservative extra risk makes every known cheaper leaf leave the queue first.
          transitions.add(
              new Planner.Proposal(
                  step.action(),
                  step.result(),
                  step.affected(),
                  new Planner.Cost(
                      step.cost().actions(), step.cost().materials(), step.cost().risk() + 1),
                  step.ruleId(),
                  step.interaction()));
        }
      for (int i = 0; i < leaves; i++)
        transitions.add(
            new Planner.Proposal(
                Planner.Action.INTERACT,
                new BlockState(
                    ResourceId.parse("test:unreachable_leaf"),
                    Map.of("index", Integer.toString(i))),
                Set.of(pos),
                new Planner.Cost(1, 0, 0),
                "test:instrumented_leaf"));
      return new Planner.Prediction.Proposals(List.copyOf(transitions));
    }

    @Override
    public Planner.Prediction predictAfterBreak(Position pos, BlockState target) {
      if (failureMode == 3) throw new IllegalStateException("Instrumented post-break failure");
      return super.predictAfterBreak(pos, target);
    }
  }
}
