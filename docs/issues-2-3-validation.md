# Issues #2 and #3: implementation and acceptance

Date: 2026-10-16. Baseline: `0b35e15` / released 0.0.3. These changes are prepared as
0.0.4 without a release tag. A JAR reporting 0.0.3 in the following development runs
includes the implementation changes and is not the previously published release artifact.

The [implementation plan](issues-2-3-plan.md) defines the accepted gates. The
[execution reference](execution-options.md) defines defaults, configurable alternatives and
their limits. Native cross-tick search frontiers and worker planning remain disabled.

## 0.0.4 version preparation

The version was bumped to 0.0.4 on 2026-10-16 without creating a tag or publishing a release.
`check buildAll :planning-core:officialTest`, seven-artifact verification and release collection
passed. All three bridges require the matching 0.0.4 main mod. Runtime class bytes in all
seven JARs are identical to the accepted development artifacts below; this preparation changes
version metadata and documentation. The 33 native reports retain their original 0.0.3 labels
and are not presented as new 0.0.4 launches. The historical collector now uses each report's
recorded runtime version and successfully revalidated those reports after the bump.
[Prepared artifact hashes](evidence/0.0.4-artifacts.json) are recorded separately from the
original acceptance evidence.

## Acceptance mapping

| Issue requirement | Implementation and evidence |
|---|---|
| #2 actual registry/world/target/selection/inventory/rules revisions | `ClientRevisions`, packet/world hooks, committed edit notifications and immutable slot samples in both native adapters; `NativeRevisionProbe` |
| #2 dependency-qualified bound reads, captures, candidates and proposals | Compiler fact/offset projections, `SpatialReads`, original plan stamps, `ClientProof.forPlan`; `PredictionReadsTest`, `SpatialRevisionsTest`, native projected support cases |
| #2 relevant changes between scan, plan, submit and observe | Every `revision-events.json` row runs through the capture/planner/controller/gateway/receipt boundaries in `RevisionStagesTest`; `RevisionPipelineTest` rejects fresh metadata laundering |
| #2 unrelated changes preserve independent work | Fine versus section native mutations, unrelated selections/rules/slots and preview namespaces; cell/support projection tests |
| #2 source placement/world/dimension/new-preview isolation | Existing actual bridge placement switch/removal/metadata restoration checks; native world exit, actual Nether round trip and real remote reconnect/travel |
| #2 unreliable source revisions and direct edits | Metadata-only source revisions are `SHORT_LIVED`; actual source-container edits with unchanged metadata are read after source-mod rebuild; old slices stay immutable; per-cell pinned intent is explicit |
| #2 invalidation documented and measured before workers | Bounded ledger/event journal and ABA/eviction tests; capture/allocation/scan benchmarks; no reusable lease is advertised for opaque prediction inputs |
| #3 eventually find a solution beyond the initial cap | Native injected predictor has 300 cheaper dead ends before the real repeater interaction: fresh 128/256/512 attempts find it at 301 expansions, with no early break |
| #3 exhaustion and failures cannot authorize destruction | Native 1,500-leaf fixture remains deferred at cap 1,024; primary and post-break exceptions submit zero actions; separately registered throwing `BlockItem` exercises actual native placement code |
| #3 confirmed post-break state and changed support/target | Qualified remainders always replan; pre-break proof retains the actual future support; authoritative contradictory state/provider-loss/changed-after-break fixtures stop continuation |
| #3 finite budgets, denied breaking and material policy | One-action replacement and forbidden material preserve the source; snow/repeater, exhaustion/refill and paused slot relocation remain regression gates |
| #3 broader native/modded/reach/lifecycle acceptance | Registered axis x/y/z blocks, fixed-position sand feed/mining, real dimension travel, remote survival refill and natural remote unload/reload; bridge targets use the actual registered source |
| #3 independent candidate bound and larger survival work | 1,024 selected cells, cap 16, minimum retry interval, protected partial progress, fair rediscovery, exact server coordinates/material accounting; actual 32×48 blueprint on bridge targets |
| #3 retry-cache oversubscription | Two native complex candidates compete for one retry record; both reach the 301-expansion non-breaking solution. A bounded shared work floor prevents repeated eviction from restarting every candidate forever. |
| #3 performance | Separate capture, pure scan, predictor, job callback and whole-game frame/server measurements; finite smoke gates, no universal frame-time claim |

## Reproduction and launch conditions

```powershell
.\gradlew.bat formatAll check buildAll :planning-core:officialTest
python scripts/verify-artifacts.py
.\scripts\run-minedriver-audit.ps1 -TimeoutSeconds 1800 -AcceptKnownLogoWarnings `
  -SchematicsDirectory 'E:/Games/Minecraft/prism/instances/Vanilla_Latest/.minecraft/schematics'
.\scripts\run-minedriver-audit.ps1 -Scenario PlanningScenario -AcceptKnownLogoWarnings
.\scripts\run-minedriver-audit.ps1 -Scenario StreamingScenario -TimeoutSeconds 900 -AcceptKnownLogoWarnings
.\scripts\run-dedicated-audit.ps1 -Version 26.2 -AcceptKnownLogoWarnings `
  -Targets fabric-26.2,neoforge-26.2,litematica-fabric-26.2,forgematica-neoforge-26.2
.\scripts\run-dedicated-audit.ps1 -Version 26.3 -AcceptKnownLogoWarnings `
  -Targets fabric-26.3,neoforge-26.3,litematica-fabric-26.3
.\scripts\run-minedriver-audit.ps1 -Scenario JarInstallScenario -AcceptKnownLogoWarnings
```

Windows, Intel Core i7-11800H at 2.30 GHz (16 logical processors), 63.73 GiB physical RAM,
JDK 25.0.2, MineDriver 0.2.0. The native renderer selected Intel UHD Graphics, OpenGL driver
32.0.101.7085, despite a discrete GPU also being present. JVM heap and exact loaded mod
versions are recorded in the native result/runtime output.

Clients run sequentially in owned disposable directories/worlds. Fabric excludes the two
known development GameTest modules; Mod Menu remains enabled. Only the previously diagnosed
Cloth/Forgematica metadata warning pairs are accepted. Exact-state probes set
`random_tick_speed=0`; dynamic/streaming fixtures run peaceful with mob spawning disabled.
The separately registered acceptance mod is opt-in and excluded from all distributable JARs.
Dedicated tests use genuine remote client connections to owned loopback vanilla servers;
final states are queried independently through RCON. No integrated server substitutes for
these checks. The real schematic library is read-only and manifests include hidden files,
hashes, sizes and modification times.

## Build and native evidence

The full Gradle command passed with 297 ordinary JVM tests plus five official-data tests,
zero failures/errors/skips. Source-size checks and compilation cover both native adapters.
All seven built JARs passed metadata, required class/resource, localization, embedded Cloth
and probe/fixture exclusion inspection (`build/artifact-verification.json`).

33 fresh complete PASS reports cover the seven-target full matrix, final
planning/known-window benchmark follow-ups, final scheduler streaming coverage, real dedicated
connections and ordinary JAR discovery. The five earlier Fabric/bridge full passes precede
final retry-cache and boundary-wake corrections; focused planning and streaming follow-ups
recheck those changed paths on all targets. Both NeoForge full passes already include them.

[Machine-readable evidence](evidence/issues-2-3.json) retains exact run IDs, source report paths,
loaded mod versions, durations, metrics and excluded failed runs. Every accepted report has
`complete=true`, matching fresh `runId`, all steps PASS and exit code zero.

| Target | Full | Final streaming | Final planning | Dedicated | Built JAR |
|---|---|---|---|---|---|
| fabric-26.2 | PASS | PASS | PASS | PASS | PASS |
| fabric-26.3 | PASS | PASS | PASS | PASS | PASS |
| neoforge-26.2 | PASS | PASS | PASS | PASS | PASS |
| neoforge-26.3 | PASS | PASS | PASS | PASS | PASS |
| litematica-fabric-26.2 | PASS | PASS | PASS | PASS | PASS |
| litematica-fabric-26.3 | PASS | PASS | PASS | PASS | PASS |
| forgematica-neoforge-26.2 | PASS | PASS | PASS | PASS | PASS |

Final streaming uses 1,024 selected cells per target, candidate cap 16 and ordinary survival.
Server final states, confirmed/submitted operations, consumed items and actual received clicks
all equal 1,024; every run recorded zero build-limit messages. Tile wait includes eight
confirmed operations and polling/server delays; it is distinct from CPU time in a job callback.

| Target | Peak candidates | Callback p50 / p95 / max (ms) | Tile wait p50 / p95 / max (s) | Frame p95 (ms) | Server average tick (ms) |
|---|---|---|---|---|---|
| fabric-26.2 | 16 | 1.142 / 2.436 / 73.528 | 0.592 / 1.269 / 2.904 | 35.555 | 2.676 |
| fabric-26.3 | 16 | 1.104 / 2.961 / 111.135 | 0.605 / 1.370 / 1.686 | 35.349 | 2.104 |
| neoforge-26.2 | 16 | 1.011 / 1.945 / 7.029 | 0.771 / 1.672 / 3.776 | 36.219 | 1.293 |
| neoforge-26.3 | 16 | 1.048 / 2.397 / 8.104 | 0.569 / 1.470 / 1.672 | 35.043 | 2.314 |
| litematica-fabric-26.2 | 16 | 1.252 / 2.768 / 63.850 | 0.568 / 1.272 / 2.687 | 35.770 | 4.703 |
| litematica-fabric-26.3 | 16 | 1.192 / 2.631 / 78.805 | 0.569 / 1.371 / 2.067 | 35.481 | 2.586 |
| forgematica-neoforge-26.2 | 16 | 1.450 / 3.732 / 62.300 | 0.573 / 1.371 / 1.855 | 35.694 | 2.338 |

Whole-game frame distributions retain at most the latest 4,096 intervals. Complete profile
durations, observed/dropped frames, GC count/time and used heap are retained in the JSON.
A low callback percentile does not establish a universal frame-time bound.

Final focused capture measurements use 10 warmups and 30 samples per halo. Every requested
cell is known: halos 0 / 1 / 8 contain 4,096 / 5,832 / 32,768 cells respectively. Pure scan
evaluates the central 4,096 cells. Allocation uses the game thread allocation counter.

| Target | Capture p95 halo 0 / 1 / 8 (ms) | Halo 8 scan p95 (ms) | Halo 8 allocated p95 (KiB) |
|---|---|---|---|
| fabric-26.2 | 2.792 / 1.666 / 3.909 | 0.424 | 331.3 |
| fabric-26.3 | 2.013 / 0.885 / 2.940 | 0.385 | 331.3 |
| neoforge-26.2 | 2.213 / 1.584 / 4.012 | 0.549 | 331.3 |
| neoforge-26.3 | 2.339 / 1.360 / 7.142 | 0.794 | 331.3 |
| litematica-fabric-26.2 | 2.457 / 1.646 / 3.944 | 0.448 | 331.3 |
| litematica-fabric-26.3 | 3.479 / 2.094 / 10.636 | 0.554 | 331.3 |
| forgematica-neoforge-26.2 | 2.658 / 2.228 / 4.317 | 0.407 | 331.3 |

The final two-candidate/one-entry retry fixture succeeds on every target, reaching the
301-expansion non-breaking solution for each candidate while retaining at most one retry
record. Exhaustion beyond 1,024 expansions and both predictor exception paths submit zero
destructive fallbacks. Remote checks independently query the owned vanilla server over RCON,
including refill and an actual registered blueprint source on each bridge.

Evidence wrapper directories:

* `build\minedriver-audit\20261016-061557-167`
* `build\minedriver-audit\20261016-070651-292`
* `build\minedriver-audit\20261016-071619-218`
* `build\minedriver-audit\20261016-073025-664`
* `build\minedriver-audit\20261016-073803-103` (excluded failed fixture initialization)
* `build\minedriver-audit\20261016-074032-650`
* `build\minedriver-audit\20261016-074440-255`
* `build\minedriver-audit\20261016-074750-441`

`scripts/collect-issue-evidence.py` verifies these wrapper directories together. It excludes
failed rows explicitly and refuses completion if any target lacks a required scenario or
final streaming wait measurements. `scripts/verify-artifacts.py` separately verifies all
seven distributable JARs; their SHA-256 hashes are retained with the evidence. Installed main
and bridge JAR hashes match those exact built artifacts, and the loaded main code source
points into the corresponding owned run's mods directory.


Independent guarded streaming pass on NeoForge 26.3:
`1792097513454-cdf5251b-725c-40dc-a383-452af21f46b0`, wrapper
`build/minedriver-audit/20261016-055121-693`. All 1,024 selected cells, 1,024 submitted and
confirmed operations, 1,024 consumed dirt items and 1,024 actual server-received clicks agree;
zero build-limit messages. Retained candidates peak at 16. Job callback p50/p95/max were
0.7623 / 2.1521 / 54.261 ms (2,280 samples). Whole-game profiling retained the latest 4,096
of 10,189 frames: frame interval p50/p95/max 33.3622 / 36.7124 / 82.7243 ms, 31 collections
and 317 ms GC, final used heap 472,070,160 bytes, final server average tick 3.202448 ms.
The bounded frame ring is not a complete-run percentile distribution.

## Fixture geometry and repaired failures

Streaming selects two 32-cell rows followed by one unselected walking row, repeated sixteen
times in a 32×48 footprint (1,024 selected cells). These regular gaps are part of the fixture,
not unfilled selected cells. Server assertions check every selected coordinate. Each 4×2 tile
is approached along the walking row in ordinary survival. Before travel/refill, the job is
paused and its receipt settles; resumption waits for client position/inventory and the server's
teleport acknowledgement. No flight permission or artificial interaction reach is granted.

During development, slime attacks displaced the player and invalidated the reach fixture.
Peaceful mode and disabled spawning remove that disturbance. A one-tick retry also exposed
real candidate starvation, and moving into parked manual-scan work did not immediately wake
it. Fair retry ordering and movement-driven parked-section rediscovery now have regressions.
Earlier fixture variants had overly distant tiles and raced teleports/refills with receipts;
those failed runs are not PASS evidence.

The user's saved screenshot `2026-10-16_06.58.37.png` in the NeoForge 26.2 run
`1792101202056-e8adf84c-7e58-4ff1-b8c5-ff136bc884a2` shows the automatic-activation
suspension HUD, rather than a cache-reset error. Some tile approaches took over ten seconds.
Movement woke only the player's own section, leaving reachable work across a section boundary
behind the distant retry rotation. Movement now wakes parked sections intersecting the native
interaction range, and event-driven local scans precede periodic distant retries. A regression
queues distant work first and verifies the adjacent section is scanned immediately; native
streaming reports tile wait p50/p95/max separately from job callback time.

That run also logged `Saving and pausing game...` before a teleport/inventory synchronization
timeout at 648 placements. The disposable audit client now disables lost-focus pause; normal
user settings are untouched. Its retained final 4,096 frame intervals had a 64.6614 ms maximum,
which does not establish a continuous multi-second render stall. The failed run remains
failure evidence. Final streaming follow-ups on all seven targets use the repaired scheduler
and fixture synchronization; focused planning follow-ups cover the final retry-cache change.

The user observed a highest-height-319 message in an earlier run. Its exact historical cause
was not proven. The final fixture inspects actual server ingress coordinates and fails on any
build-limit message. Production prediction/submission now rejects targets and click positions
outside the current dimension's native build height; upper/lower boundary checks do not
hard-code Overworld limits. A successful guarded run establishes the tested coordinates and
absence of that message, not retrospective proof of the original message's cause.

Other repaired fixture failures: dedicated startup initially inherited a whitelist; it is now
explicitly disabled in the owned server. The first final Fabric remote run also exposed an
initialization order error: filling the remote fixture before its chunks loaded silently left
the destination without its support floor. The fixture now travels to the destination, waits
for all affected client chunks, fills, independently verifies the server support, then places
the player and inventory for the test. No forced chunk retention is used, preserving the
natural unload/reload assertion. The failed run is
`1792103907613-6bbb37fe-3838-45c8-a75a-fb5a847d2408`.
A falling-sand shaft initially ended below world
minimum; its floor is now inside native bounds. A nonreplaceable source bypassed the intended
throwing predictor; replaceable short grass now actually invokes it. A benchmark initially
ran before return-trip chunks arrived; it now waits for residency. The 26.2 placement packet
accessor differs from the 26.3 record accessor; the test fixture handles both. Failed runs
remain retained locally and are not included as final matrix passes.

Final review added an activation-selection mutation case. It exposed a genuine tracking gap:
the selection dependency collector handled quoted string arguments but omitted shorthand
names such as `selection(trigger)`. Both syntaxes and activation/scope/select references now
participate in intent tracking, with pure and native regression checks. The matrix is rerun
after this correction rather than treating its intermediate failed run as a pass.

Review also found retry-budget eviction starvation when fair round-robin work exceeds the
retry-cache capacity. A bounded shared work floor now promotes evicted progress instead of
resetting it indefinitely. It retains no prediction/frontier and cannot exceed the existing
node cap. A pure three-candidate/one-entry test and a native two-candidate/one-entry
301-expansion fixture cover this separately from ordinary candidate admission pressure.

The final focused planning/performance follow-up waits for all nine surrounding client
chunks, chooses a section inside the dimension's build bounds and verifies every requested
halo cell is known. Its measured windows contain exactly 4,096 / 5,832 / 32,768 known cells.
This avoids presenting a partially available halo or below-world air as a fully loaded
32,768-cell benchmark. Earlier full-run capture figures remain intermediate measurements;
the final focused reports provide the comparable loaded-window capture table.

## Deliberate limits

The native graph fixture injects adversarial transitions into the actual native job; its
winning action is validated by the ordinary gateway and server. Registered stateful/throwing
blocks provide separate real mod API coverage. Neither establishes compatibility with every
third-party mod or permits preemption of a slow synchronous predictor. Default node caps,
candidate bounds and finite retry metadata are resource limits, not a wall-clock deadline.

Benchmark smoke gates are p95 capture under 500 ms, pure scan under 100 ms and capture
allocation under 64 MiB at the stated fixture sizes. They catch gross regressions; they are
not an acceptable interactive frame budget or universal modpack performance claim. Broad
halos may remain expensive. Evidence eviction conservatively invalidates/defer work; the
largest combinations of configured halo/target reads do not promise uninterrupted progress
through a fixed-size revision ledger.

The sand mode confirms an authoritative operation transition even if the block subsequently
falls. It does not solve arbitrary gravity/redstone evolution. Fluid-containing and coupled
multi-block destructive actions, unverified property-sensitive replacement and arbitrary
entity/container contexts still defer. Metadata revisions do not detect source edits before
the source mod publishes them to its native schematic world; fresh reads observe that API,
not an unsignalled pending edit outside it.

Artifact-discovery tests load actual built JARs from an owned mods directory and verify
code-source locations, disabled-profile inactivity and optional bridge registration. The
surrounding Minecraft launch remains a development runtime. Full production modpack
certification, arbitrary modded predictors, worker planning and reusable native search
frontiers are outside the accepted issue closure contract.

The historical release backlog also contains broader UI transaction experiments (switching
controller jobs with a pending receipt and invalid inline-profile replacement), and a
dedicated unloaded-neighbor `surface()`/`adjacent(...)` fixture. This issue work retains
existing guards and tests general capture unknowns, offset refresh and unload/reload; it
does not claim those additional exact UI/neighbor scenarios were all run natively.
