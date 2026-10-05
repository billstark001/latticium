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

`Bounds` includes dimension and min/max X, Y and Z, all inclusive. Build each enabled subregion's box from both corners with `min`/`max`; do not assume source-mod corner order. Return a defensive, immutable copy. A placement spanning several subregions can return several boxes. Never invent a global or infinite scope from a point-read target API.

## Snapshot lifecycle

Each preview and job has a distinct `SessionId`. On `finiteBounds`, record the source placement object or stable ID, the active world/dimension, enabled subregion bounds and any source revision available. Keep snapshots keyed by session; do not let a preview overwrite the running job's snapshot. On `target`, verify that the client is still on the game thread, the world/dimension is unchanged, the same placement is selected and enabled, and its bounds or revision still match. Return `Unknown` if any check fails. A changed placement must not silently supply a new target under an old session.

The current bridge implementations use weak session maps to avoid retaining abandoned previews. If the source API exposes an explicit lifecycle or content revision, include it in the snapshot. A bounds-only comparison cannot detect every in-place schematic edit; document that limit and test the source mod's available change signal.

## Target semantics

Return `TargetCell.Exact` for a known block state, including **exact air** inside a known blueprint region. Latticium maps exact air to `DontCare` by default, or to `Clear` when the profile requests `include_air`. Return `DontCare` outside the placement. Return `Unknown` for unloaded or unavailable schematic chunks, removed placements, ambiguous overlaps, source API failures, or state conversion that cannot be represented. Do not use `DontCare` or air as a fallback for missing data.

For any position inside more than one enabled subregion or conflicting active placement, return `Unknown` unless the source API has a documented, stable precedence rule that the bridge explicitly implements. Check membership before reading schematic-world state. Convert source states to Latticium's neutral `Model.BlockState` through the version adapter; preserve properties rather than only the block ID. Catch source-mod exceptions at the boundary where useful and provide a specific reason. Latticium's `TargetSources.guarded` also converts runtime exceptions or null into `Unknown`, but a bridge should not rely on that for routine states.

## Performance and threading

The current callbacks run on the client game thread. Never open or parse a schematic file, load chunks, or block on I/O in `target`. Target-dependent section scanning may request many point reads, so cache immutable placement metadata per session and keep each read bounded. A target-free selection skips bulk target capture, but candidate and action rechecks still request targets. A future optional section-slice API is tracked separately; do not implement a private batch protocol that changes `Exact`/`Clear`/`DontCare`/`Unknown` behavior.

The bridge has no authority to send game packets, change inventory or bypass `policy.break`. Only the Latticium action gateway performs interaction and waits for server observation. Keep all source-mod Java classes inside the bridge module so the main mod starts without that mod installed.

## Packaging and verification

Create a version-specific project under `integrations/`, following the existing bridge Gradle and metadata files. Declare the matching Minecraft, loader, main Latticium and source-mod versions. Reference source-mod APIs at compile time; package the bridge separately. Avoid bundling the source mod into the bridge JAR. Test the assembled JAR as well as a Gradle development run.

At minimum, verify in a live client:

1. Main Latticium launches and handles non-blueprint jobs without the bridge or source mod.
2. The bridge registers once with a source mod present; no active placement yields no finite bounds and a clear profile error.
3. A known non-air state, exact air with both `include_air` values, outside placement and an unavailable schematic chunk produce the specified cells.
4. Multiple subregions, overlaps, placement switch/removal, world switch and a second preview while a job runs cannot reuse stale targets.
5. The bridge JAR metadata enforces matching dependencies; preview and real interaction use the expected source version.

Record each Minecraft/loader/source-mod version and result in the [validation matrix](26.2-26.3-validation.md). Current title-screen checks do not constitute blueprint construction acceptance.
