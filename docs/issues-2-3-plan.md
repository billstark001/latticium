# Revision-safe bounded execution: issues #2 and #3

Status: implementation and accepted validation complete, 2026-10-16. Baseline: `0b35e15` (0.0.3).

## Accepted scope

Complete [#2](https://github.com/billstark001/latticium/issues/2) and
[#3](https://github.com/billstark001/latticium/issues/3) against explicit, reproducible
contracts and acceptance evidence. Keep native planning synchronous, with fresh inputs on
each adaptive attempt. Do not retain native search frontiers across ticks or enable worker
planning. Existing accepted actions still require authoritative server confirmation.

The user requested data-driven event/revision/dependency/transition contracts and multiple
profile-configurable consistency standards, including intentional placement/mining of
falling blocks at a fixed position. Configuration changes intent and invalidation policy;
it never makes missing evidence a success, bypasses action budgets, or authorizes an
unmodelled destructive replacement.

## Decisions and configuration contract

* Keep schema-1 profiles backwards compatible. Add a strict optional execution configuration,
  accessible through JSON profiles and useful localized UI presets. Reject invalid and
  unknown options with JSON-pointer diagnostics. Preserve all execution options when deriving
  a remaining-action policy.
* Separate connection/world generations, job/preview identity, versioned live inputs,
  immutable intent snapshots and authoritative receipt evidence. Never compare every global
  counter to cancel every job: own placements and inventory consumption are expected events.
* Derive dependencies from existing bound fact columns/spatial offsets, actual prediction
  reads and declared block effects where possible. Explicit profile dependency extensions
  may conservatively widen this set. They cannot silently remove required reads.
* Offer fine cell dependencies and conservative section dependencies. Source identity,
  chunk generation/residency, registry and relevant rules remain mandatory checks. Version
  eviction means Unknown; A -> B -> A must not restore an old reusable lease.
* Offer frozen versus stop-on-edit named selections and profile/rule definitions. Registry
  replacement always invalidates old bindings. Offer live versus explicitly pinned claimed
  target cells; pinned targets are job intent, not permission to reuse a source snapshot.
  Do not describe lazily pinned cells as a full frozen blueprint.
* Unknown/unversioned external sources use fresh short-lived reads at capture, planning,
  submission and observation. Cross-tick cache/worker leases require explicit reliable
  source capability. Point reads remain the compatibility fallback for existing providers.
* Stable-state tasks stop/replan after contradictory world changes. Opt-in settling tasks
  wait and freshly re-evaluate the selected position after confirmed actions. Repeated
  fixed-position tasks may act again while the predicate selects that position, within the
  original finite scope and action budget. Gravity is an external transition; never fabricate
  a confirmed final sand block from a falling entity or a locally predicted state.
* Native adaptive budgets retain only bounded scheduling metadata. Defaults remain
  128 -> 256 -> 512 -> 1024 expansions. Diagnostics share the work budget. Configurable
  limits remain finite and validated. Exhaustion/errors defer and back off; they cannot prove
  NoPlan and authorize a break. One slow synchronous third-party call cannot be preempted.
* Candidate memory receives a capacity limit and fair streaming/backpressure policy.
  Capacity eviction must not lose claimed targets, confirmed partial progress, or undiscovered
  scope positions permanently. Expose saturation and deferred reasons to the user.
  Retry-cache eviction must not make a finite round-robin queue repeat the initial search cap
  forever: promote a bounded shared starting floor without retaining any search object.
* Separate the outcome of an already submitted action from eligibility to continue the
  plan. Stop further interaction on cancellation/relevant intent changes, settle or discard
  the original receipt according to its world lifetime, and never refund/duplicate a sent
  action merely because its confirmation is late.

## Work packages and gates

| ID | Deliverable | Gate |
|---|---|---|
| P0 | This plan, executable event/dependency/transition catalog, fixture inventory and benchmark protocol | Every issue acceptance item maps to a test/evidence ID; defaults and configurable alternatives are documented |
| P1 | Neutral revision/read-set contracts, strict execution options, fake-host tests | Relevant mutations invalidate; unrelated mutations preserve independent work; ABA, eviction, unload, unknown sources and own actions have explicit results |
| P2 | Both native adapters and three blueprint providers connected to revisions and dependency reads | World/dimension/session, selections, inventory, targets and rule/profile edits cannot reuse old proposals; previews remain independent |
| P3 | Submission/observation and multi-step recovery integrated with configurable intent semantics | Own updates do not cancel tasks; one-action/denied-break guards hold; interruption, provider loss, refill, relocation and settling are server-checked |
| P4 | Native complexity/error fixtures, bounded planning diagnostics and candidate streaming | Beyond-initial-budget success, final-cap deferral and predictor failure are distinct native PASS cases; memory has an independent bound |
| P5 | Native acceptance and performance matrix, ordinary packaging checks | Fresh complete matching PASS reports and successful Gradle exits; exact runtime, fixture conditions, sizes and metrics recorded |
| P6 | Documentation, changelog and issue evidence reconciliation | Both original acceptance lists and latest remaining-work lists are accounted for; no unsupported case is silently counted as passed |

P4 fixture development and performance baselines may proceed independently of P1/P2.
P3 depends on the revision contracts, not merely additional global epoch counters.

## Executable acceptance catalog

The implementation must keep events, dependency domains and invalidation actions in data
consumed by tests and the native adapters where practical. Native event hooks translate
source events into that catalog. The catalog is not proof that an external API emits an
event; unobservable sources retain short-lived capability.

| ID | Required evidence |
|---|---|
| R01 | Mutate every relevant input between scan, plan, submit and observation; no stale submission/continuation |
| R02 | Change unrelated cells, including in the same section, unrelated selections/rules and unused inventory; fine-grained independent work survives |
| R03 | World/dimension/reconnect/new-preview/new-job separation, source placement switch/removal and metadata restoration |
| R04 | Version eviction/unload, A -> B -> A, source exceptions/nulls and direct source-container edits without metadata changes |
| R05 | Profile consistency alternatives, derived and widened dependencies, own consumption/placement, frozen selections and pinned target intent |
| A01 | Confirmed break followed by changed support/state/target; stop/replan without stale placement |
| A02 | One remaining action and denied breaking submit no break; material predicate applies before destructive fallback |
| A03 | Snow/repeater multi-step actions, survival exhaustion/refill and paused hotbar relocation preserve confirmed progress |
| A04 | Opt-in repeated fixed-position sand placement/mining, settling transitions and bounded termination; default behavior stays conservative |
| B01 | Native instrumented predictor needs >128 expansions; adaptive attempts find a non-breaking solution with no early break |
| B02 | Native search beyond final cap and throwing/unavailable predictors defer without destructive fallback or client-tick crash |
| B03 | Predictor work, capture/scan allocation/time and frame/tick latency measured separately with fixed fixture sizes |
| C01 | Candidate saturation/backpressure, fair rediscovery, memory bound and protected confirmed progress |
| N01 | Four main clients and three bridges: integrated-server matrix with independent authoritative assertions |
| N02 | Corresponding dedicated-server connections, reach/inventory cases and natural-travel unload/reload; no integrated-server substitution |
| N03 | Stateful/modded-block fixture, larger survival blueprint, explicit tested mods/versions and limits |
| N04 | Ordinary JAR install/startup, main without bridge, disabled-profile scan/action inactivity, probe exclusion |

## Validation protocol

Run focused neutral tests while changing contracts, then both native test suites and probe
compilation. At the integration gate run:

```powershell
.\gradlew.bat formatAll check buildAll :planning-core:officialTest
.\scripts\run-minedriver-audit.ps1 -AcceptKnownLogoWarnings `
  -SchematicsDirectory 'E:/Games/Minecraft/prism/instances/Vanilla_Latest/.minecraft/schematics'
```

Add focused scenarios for revision, predictor-budget, settling, streaming and server cases.
Do not run concurrent Gradle/native launches against shared output directories. Keep probes
out of production JARs and schematic fixtures read-only. A native pass requires a fresh
matching `runId`, `complete=true`, all required assertions, `status=PASS` and exit code zero.
Record failures, repairs and launch conditions. Extend `docs/26.2-26.3-validation.md` with
actual evidence; existing 0.0.3 reports are regression baselines, not evidence for new code.

Performance fixtures must state hardware/JVM, warmup/sample count, loaded scope and candidate
counts, and separate pure scanning, capture, prediction and server wait. Record percentiles,
peak retained candidates and rediscovery latency. Establish numerical gates from the baseline
before claiming a bounded frame cost; node caps alone do not establish a time guarantee.

## Completion ledger

* [x] P0: plan saved; implementation started.
* [x] P1: neutral contracts/configuration and catalog-driven mutation matrix through actual planner/controller boundaries; full JVM check passes.
* [x] P2: both native source adapters and three bridge revision providers compile; native dependency projection/intent cases pass on all seven targets.
* [x] P3: qualified multi-step replanning and configurable settling/repetition implemented; server-confirmed sand feed/mining and replacement/snow/repeater/replenishment regressions pass on all seven targets.
* [x] P4: native 301-expansion success, 1,024-cap exhaustion, primary/post-break exceptions, bounded admission, retry-cache pressure and fair reachable-work rediscovery pass their pure and native gates.
* [x] P5: seven-target integrated, final planning/performance, final streaming, genuine dedicated and ordinary JAR coverage complete; 33 fresh reports verified.
* [x] P6: configuration, failure/repair history, exact run IDs, measurements and closure mapping reconciled in the acceptance report and portable evidence JSON.

Update this ledger with implementation/test evidence as work progresses. Only close an issue
when its accepted gate is actually satisfied. Publishing a release is outside this task.

Final verification: `formatAll check buildAll :planning-core:officialTest` passed on
2026-10-16 with 297 ordinary JVM tests and five official-data tests, zero failures/errors/skips.
`python scripts/verify-artifacts.py` verified all seven JARs, including executable catalogs,
localized execution UI, required classes and exclusion of driver/probe/fixture classes.
The [acceptance report](issues-2-3-validation.md) records the completed native matrix and its
deliberate compatibility limits. Release publication remains outside this work.
