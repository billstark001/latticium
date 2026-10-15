# Profile execution options

Schema-1 profiles accept an optional `execution` object. Existing profiles retain the defaults
below. F8 -> **Execution options** edits the selected saved profile, including numeric limits
and extra offsets. Opening the editor neither saves nor starts a task. Saving uses the same
strict profile reader as commands. A running task follows the edit policy it had at startup;
start a new task to use its newly saved settings.

```json
{
  "execution": {
    "consistency": {
      "granularity": "cell",
      "selections": "stop",
      "rules": "stop",
      "targets": "live",
      "inventory": "relevant"
    },
    "dependencies": {
      "reads": "derived",
      "radius": 1,
      "extra_offsets": []
    },
    "dynamics": {
      "motion": "stable",
      "repeat": "once",
      "settle_ticks": 20
    },
    "planning": {
      "initial_nodes": 128,
      "max_nodes_per_tick": 1024,
      "max_retry_entries": 256,
      "max_candidates": 4096,
      "retry_ticks": 20
    }
  }
}
```

## Intent and invalidation

| Setting | Alternatives | Meaning |
|---|---|---|
| `granularity` | `cell`, `section` | Cell tracking checks the expression and chosen action's read positions. Section tracking also rejects work after other updates in a section it reads. |
| `selections` | `stop`, `freeze` | Stop after a committed edit to a referenced named selection (including activation inputs), or retain the task's captured bounds. Other selections do not stop the task. Automatic trigger start/stop decisions remain governed by the activation mode. |
| `rules` | `stop`, `freeze` | Stop after this saved profile changes, or retain its original definition. Registry/tag reloads always invalidate old bindings. |
| `targets` | `live`, `pinned` | Recheck the source before submission and during confirmation, or explicitly pin each claimed cell's target until it leaves the candidate queue. Pinning does not capture an entire blueprint. Unclaimed cells still require the original source session. |
| `inventory` | `relevant`, `hotbar`, `full` | Check slots required by concrete interactions, the whole hotbar, or all inventory slots. Required inventory-expression reads are always retained. Live slot, material and interaction checks still run before submission. |

World/dimension/connection identity, action budgets and authoritative confirmation cannot be
disabled. Frozen intent and short-lived live reads are different: freezing chooses what a task
means; it does not make an external provider's revision signal reliable. The three schematic
bridges expose observed metadata revisions as short-lived signals because direct container
edits may not change metadata. Default live tasks re-read the actual target cells.

The executable contracts are resources in `planning-core/src/main/resources/latticium/`:

* `revision-events.json`: event, scope, affected revision domains and default invalidation effect.
* `action-transitions.json`: ordered receipt/state transitions, including pause and cancellation.
* `prediction-reads.json`: action read columns and use of the concrete clicked/support position.

The compiler's expanded fact columns and spatial offsets are combined with concrete proposal
effects, clicked positions and pre-break placement proofs. Profile extensions only widen these
dependencies. `faces` adds the six neighbors; `halo` adds the cube of the configured radius;
`extra_offsets` adds state/fluid/support checks at explicit `[x,y,z]` offsets. Radius and
offset coordinates are bounded to 16, with at most 512 explicit offsets.

The state table below describes the executable controller transitions and native scheduling
conditions. Receipt rows are loaded from `action-transitions.json`; dependency invalidation is
derived from `revision-events.json`, compiler reads and `prediction-reads.json`. Candidate
ownership uses bounded queue/mask data rather than retaining a native search object.

| Object / current state | Event or guard | Result |
|---|---|---|
| Task / scanning or running | World generation changes; registry binding changes | Stop, discard the old world lifetime; no further submission |
| Task / scanning or running | Referenced selection/profile edited | `stop`: cancel; `freeze`: keep captured intent |
| Candidate / discovered | Capacity available | Admit with a projected scan stamp |
| Candidate / unstarted | Capacity full | Prefer near work; park displaced scope for rediscovery |
| Candidate / confirmed partial or pinned | Capacity full | Keep ownership; report backpressure |
| Candidate / deferred | Retry becomes eligible | Rotate with other ready work; capture fresh inputs |
| Plan / ready | Relevant input changes or evidence expires | Discard proposal and replan; never replace its stamp with fresh metadata |
| Receipt / waiting | No authoritative evidence or temporarily unloaded cell | Keep waiting until the finite timeout |
| Receipt / waiting | Expected authoritative transition confirmed | Advance exactly once; fresh planning for a qualified remainder |
| Receipt / waiting | Contradictory payload or timeout | Stop the action and block continuation |
| Task / paused or cancelled | An action was already accepted | Observe that receipt without sending another action |
| Candidate / satisfied | `repeat: once` | Record the satisfied mask; relevant continuous updates or explicit refresh can rearm it |
| Candidate / satisfied | `repeat: while_selected` | Wait `settle_ticks`, re-read selection and target, retain the activation budget |

## Moving blocks and fixed-position work

`motion: "stable"` requires a newer authoritative expected-state packet and the expected state
visible in the loaded client world. `motion: "settling"` instead accepts a retained authoritative
packet proving the expected transition occurred, even if the block subsequently moved. This is
operation confirmation, not proof of a stable final block. Local prediction, unrelated packets,
unloaded cells and forgotten history cannot confirm it. The bounded packet history retains at
most eight payloads per tracked position; absence of evidence stays pending or times out.

`repeat: "while_selected"` freshly rechecks a position after each confirmed operation and waits
`settle_ticks` before trying again. It retains the activation's action budget. The default `once`
records satisfied positions with compact per-section masks; a relevant subsequent world update
or explicit refresh lets continuous tasks reconsider them. Confirmed partial replacements keep
their target through material shortages. Stable tasks block on intervening state changes;
settling tasks wait and require fresh selection eligibility before further work.

For a sand feed, use a finite scope around the intended placement position, select air, target
`i{minecraft:sand}`, and add:

```json
"execution": {
  "dynamics": {"motion": "settling", "repeat": "while_selected", "settle_ticks": 10}
}
```

A sand-mining profile can instead select `current(b{minecraft:sand})`, use `"target":
{"clear": true}`, and explicitly allow `"policy": {"break": "selected"}`. Repetition never
bypasses the action budget or permits a second break to continue through an unconfirmed block
transition. Fluid-containing and coupled multi-block destructive effects still defer.

## Finite work and evidence

Native attempts always capture fresh facts and start a fresh search. Only bounded retry metadata
survives; no native cross-tick frontier or worker is enabled. Search budgets must satisfy
`1 <= initial_nodes <= max_nodes_per_tick <= 65536`. Retry entries are bounded to 1..65536,
candidate capacity to 1..1000000, and retry/settle intervals to 1..1200 ticks. The search also
has its independent state/frontier capacity. Diagnostics share the attempt's expansion cap.
Node limits are not a time guarantee for an individual synchronous mod predictor call.

If retry metadata is evicted under pressure, a bounded shared starting floor is promoted
instead of repeatedly restarting every evicted candidate at the initial cap. This stores
one integer, never search nodes or predictions, and cannot exceed `max_nodes_per_tick`.
Known changed inputs reset their cached attempt; a full refresh resets the shared floor.
Thus a finite round-robin queue can still reach a solution within the cap even when it
contains more candidates than `max_retry_entries`.

At candidate capacity, undiscovered/displaced sections remain queued for rediscovery. Deferred
unstarted work can rotate; confirmed partial progress and explicitly pinned targets reserve
their capacity. Movement wakes nearby parked sections across the native interaction range,
including reachable neighboring sections, before periodic distant retries. Satisfied masks
are bounded by the finite 65536-section domain. Diagnostic
position samples are bounded with the candidate capacity; they describe known work rather than
the total unsurveyed domain. The capacity diagnostic explains how to free or increase space.

State/fluid-only immutable captures can carry version-qualified spatial evidence. Other native
columns and opaque external/native prediction inputs remain short-lived and are freshly checked.
Expired or evicted revision evidence does not mean unchanged. The [implementation plan](issues-2-3-plan.md)
and [validation record](26.2-26.3-validation.md) track the evidence for these contracts separately
from packaging/startup and the declared compatibility boundaries.
