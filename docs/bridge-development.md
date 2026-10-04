# Writing an optional blueprint bridge

A bridge converts an already active schematic placement into neutral bounds and target cells. It runs in the client game process, depends on a matching Latticium main mod and source mod, and should be packaged separately so the main mod remains usable without that source. The existing [Litematica bridge](../integrations/litematica-fabric-26.2/src/main/java/io/github/billstark001/latticium/bridge/LitematicaBridge.java) and [Forgematica bridge](../integrations/forgematica-neoforge-26.2/src/main/java/io/github/billstark001/latticium/bridge/ForgematicaBridge.java) are concrete examples.

Implement `LatticiumClient.BlueprintProvider`, which combines `Host.SelectionSource` and `Host.TargetSource`, and register it once during the loader's client initialization:

```java
LatticiumClient.get().registerBlueprintProvider(
    ResourceId.parse("example:active_blueprint"), provider);
```

A profile's `target.source` names the registered ID. `finiteBounds("active_blueprint", session)` must return inclusive `SectionScanner.Bounds` for the active placement, in the correct dimension. An empty list means no enumerable selection; starting a blueprint profile then fails. `target(position, session)` returns `TargetCell.Exact` for a known block state, `DontCare` outside the placement, or `Unknown` with a useful reason when the source cannot answer. Return an exact air state for blueprint air; Latticium applies the profile's `include_air` setting and turns it into `DontCare` or `Clear`. Do not use `DontCare` for an unavailable chunk or a changed placement.

Both calls happen on the client thread today. Take a stable identity and bounds snapshot when enumerating, then check that identity and bounds during target reads. Report removed or changed placements, dimension changes, and ambiguous overlap as `Unknown`. Each preview or job receives a distinct session; keep its snapshot separate from other sessions. Latticium converts a provider exception or `null` target into `Unknown`, but a bridge should return an explicit reason itself. Keep the bridge read-only: ordinary player interaction and server observation remain in Latticium's action gateway.

For a selection expression that reads target facts, section scanning may call `target` for many cells. Keep those reads bounded and avoid opening schematic files in the callback. When the selection expression has no target-view function, the current adapter skips bulk target capture and reads targets only for candidates and action rechecks. A future batch target API can reduce the remaining repeated placement validation.

The main and bridge JARs are assembled in the version-specific Gradle projects under `versions/` and `integrations/`. Match the bridge's Minecraft, loader, Latticium, and source-mod versions in its metadata. Verify registration, empty and changed placements, air handling, and overlap behavior in a live client before claiming support. The [validation record](26.2-26.3-validation.md) tracks the current tested matrix.
