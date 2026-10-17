# Writing an optional blueprint bridge

A bridge adapts a schematic mod's **already active, in-memory placement** to finite selection bounds and target block states. It is a separate client mod with dependencies on one matching Latticium main mod, Minecraft/loader target, and source mod. It reads data only: Latticium retains action planning, ordinary player interaction and server-result confirmation. The existing [Litematica implementation](../integrations/litematica-fabric-26.2/src/main/java/io/github/billstark001/latticium/bridge/LitematicaBridge.java) and [Forgematica implementation](../integrations/forgematica-neoforge-26.2/src/main/java/io/github/billstark001/latticium/bridge/ForgematicaBridge.java) show the concrete loader APIs.

## Contract and registration

Implement `LatticiumClient.BlueprintProvider`, which extends `Host.SelectionSource` and `Host.TargetSource`. Register it once during the source loader's client initialization, after the main Latticium mod is available:

```java
LatticiumClient.get().registerBlueprintProvider(
    ResourceId.parse("example:active_blueprint"), provider);
```

Duplicate target IDs are rejected. A profile then uses `"scope": "selection(\"active_blueprint\")"` and `"target": {"source": "example:active_blueprint"}`. The current client takes the blueprint selection from the provider named by `target.source`. Profiles can set `include_air: true` when air must become a clear target.

| Method | Required result | Failure rule |
| --- | --- | --- |
| `finiteBounds(String selectionId, SessionId session)` | Immutable list of inclusive `SectionScanner.Bounds` in the active dimension | Return an empty list for a different name or no usable active placement. An empty scope cannot start a blueprint job. |
| `target(Position position, SessionId session)` | One `TargetCell` for the same placement/session | Return `Unknown` with a reason whenever the source cannot answer reliably. |
| `slice(Bounds window, SessionId session)` (optional) | Immutable `TargetSlice` qualified by exactly the requested window and session | Return `Optional.empty()` when batch capture is unavailable; the caller falls back to point reads. |

`Bounds` includes dimension and min/max X, Y and Z, all inclusive. Build each enabled subregion's box from both corners with `min`/`max`; do not assume source-mod corner order. Return a defensive, immutable copy. A placement spanning several subregions can return several boxes. Never invent a global or infinite scope from a point-read target API.

## Snapshot lifecycle

Each preview and job has a distinct `SessionId`. On `finiteBounds`, record the source placement object or stable ID, the active world/dimension, enabled subregion bounds and any source revision available. Keep snapshots keyed by session; do not let a preview overwrite the running job's snapshot. On `target`, verify that the client is still on the game thread, the world/dimension is unchanged, the same placement is selected and enabled, and its bounds or revision still match. Return `Unknown` if any check fails. A changed placement must not silently supply a new target under an old session.

The current bridges delegate identity, bounds and overlap checks to the neutral `BlueprintSnapshots` helper. Its supplier produces a fresh `View` containing the world and placement identities, optional revision, enabled bounds, conflicting placement bounds, and a cell reader. Bounds are sorted so source map iteration order cannot invalidate a session; duplicate regions remain present because they signal overlap. The helper uses weak session maps to avoid retaining abandoned previews, and establishes each session only once. Observing a changed or removed placement invalidates that session permanently: restoring the old metadata cannot revive it, and a new job/preview needs a fresh session. Repeating `finiteBounds` cannot authorize a different placement under an old session.

The three bundled bridges include the placement UUID, schematic metadata modification time and unsaved-change flag in their revision token. The UUID alone identifies the placement and is not a content revision. These source-mod markers catch reported edits even when bounds stay equal. `TargetSource.revision(session)` therefore advertises these markers as `SHORT_LIVED`, rather than a reusable cache/worker lease. Live tasks re-read the actual target cell. A direct container edit becomes visible when the source mod publishes/rebuilds its native schematic world; pending edits outside that API cannot be inferred from unchanged metadata. If a source API exposes a stronger monotonic lifecycle/content revision, advertise `VERSIONED` only after testing every relevant mutation and residency signal. Omitting the optional capability preserves fresh point reads.

Profiles can explicitly pin each claimed cell's target. This freezes task intent for that cell;
it does not change the provider's revision reliability or freeze the entire placement. Preview
and running-job namespaces remain separate. See [execution options](execution-options.md).

## Target semantics

Return `TargetCell.Exact` for a known block state, including **exact air** inside a known blueprint region. Latticium maps exact air to `DontCare` by default, or to `Clear` when the profile requests `include_air`. Return `DontCare` outside the placement. Return `Unknown` for unloaded or unavailable schematic chunks, removed placements, ambiguous overlaps, source API failures, or state conversion that cannot be represented. Do not use `DontCare` or air as a fallback for missing data.

For any position inside more than one enabled subregion or conflicting active placement, return `Unknown` unless the source API has a documented, stable precedence rule that the bridge explicitly implements. Check membership before reading schematic-world state. Check actual chunk residency through the schematic world’s chunk cache (`getChunkSource().hasChunk`); a client level’s `hasChunk` can be permissive and must not turn an unloaded target into exact air. Convert source states to Latticium's neutral `Model.BlockState` through the version adapter; preserve properties rather than only the block ID. Catch source-mod exceptions at the boundary where useful and provide a specific reason. Latticium's `TargetSources.guarded` also converts runtime exceptions or null into `Unknown`, but a bridge should not rely on that for routine states.

## Performance and threading

The current callbacks run on the client game thread. Never open or parse a schematic file, load chunks, or block on I/O in `target`. Target-dependent capture first requests an optional `slice`, then falls back to point reads. `BlueprintSnapshots` obtains fresh placement metadata once per slice rather than once per block. The current section-plus-halo limit is 48³ cells; `TargetSlice` checks the allocation limit before multiplying coordinate extents. Its cells use x-fastest, then y, then z order and include all four target variants. Return exactly the requested bounds and session; `TargetSources.checkedSlice` rejects mismatches, nulls and failed optional batch calls. A slice is a short-lived capture, not a lease across placement changes. Live candidate/action target rechecks still use fresh reads. `TargetSources.map` applies policies such as air handling equally to point and batch reads. A target-free selection skips bulk target capture. Target requests use their own spatial bounds: a target at offset Y=16 does not require reading every intervening target cell. `FactWindow` may contain a smaller target slice inside the overall world capture window; the provider still returns exactly its requested bounds.

The bridge has no authority to send game packets, change inventory or bypass `policy.break`. Only the Latticium action gateway performs interaction and waits for server observation. Keep all source-mod Java classes inside the bridge module so the main mod starts without that mod installed.

The Forgematica bridge also contains a narrow startup compatibility mixin for MaFgLib 0.5.3. NeoForge constructs mods in parallel, while that library's initialization dispatcher performs an unsynchronized `contains`/`add` pair on an `ArrayList`. The wrapper serializes registration on the dispatcher instance and preserves the original method and duplicate check. It does not change schematic content or construction actions. The native concurrency probe makes 128 calls for 32 identity-distinct handlers, verifies exactly one registration each, then removes all test handlers before creating a world.

## Packaging and verification

Create a version-specific project under `integrations/`, following the existing bridge Gradle and metadata files. Declare the matching Minecraft, loader, main Latticium and source-mod versions. Reference source-mod APIs at compile time; package the bridge separately. Avoid bundling the source mod into the bridge JAR. Test the assembled JAR as well as a Gradle development run.

At minimum, verify in a live client:

1. Main Latticium launches and handles non-blueprint jobs without the bridge or source mod.
2. The bridge registers once with a source mod present; no active placement yields no finite bounds and a clear profile error.
3. A known non-air state, exact air with both `include_air` values, outside placement and an unavailable schematic chunk produce the specified cells.
4. Multiple subregions, overlaps, placement switch/removal, world switch and a second preview while a job runs cannot reuse stale targets.
5. The bridge JAR metadata enforces matching dependencies; preview and real interaction use the expected source version.

Record each Minecraft/loader/source-mod version and result in the [validation matrix](26.2-26.3-validation.md). Separate title-screen, read-only target/preview, small gameplay and large survival/dedicated-server evidence. The [issues #2/#3 evidence](issues-2-3-validation.md) records the expanded fixtures and their exact runtime limits. Artifact JAR discovery under a development game launch is distinct from general production-modpack certification.

Session metadata compares world and placement identities through weak references. A completed preview or job must not keep a disconnected client world or removed schematic alive through the provider cache. Bounds and immutable revision tokens remain neutral values. The helper rejects a stale or collected identity conservatively.
