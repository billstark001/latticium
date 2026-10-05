# Architecture and host contracts

This document preserves the durable decisions from the original architecture, packaging, DSL and host-interface discussions. It describes the implemented boundary first; proposed extensions are identified as such. For current syntax and schema-1 fields, use the [DSL reference](dsl-reference.md), not an older design sketch.

## Product boundary

Latticium is a client-side construction engine. Every Minecraft 26.2/26.3 × Fabric/NeoForge target has one main mod JAR with commands, profiles and the API. Optional, separately packaged bridges read active Litematica or Forgematica placements. The main mod does not require a schematic mod, read `.litematic` files, or install a server companion. Installation alone does not start scans or actions; a command or explicitly enabled activation starts work.

The pure `dsl-core` and `planning-core` modules compile for Java 21. Game adapters and loader entry points compile and run on Java 25. The core receives neutral IDs, positions, block states, facts and actions rather than `net.minecraft.*` or loader classes.

```text
finite scope + selection predicate + target + policy
  -> section capture and three-valued scan
  -> candidate and frozen target
  -> planner / placement oracle
  -> client action gateway
  -> server update and expected-state observation
  -> complete, retry, defer or block
```

The DSL is read-only: it describes typed sets and coordinate predicates. A profile declares scope, selection, target, activation and action limits. Action rules and native placement prediction are separate, trusted capabilities. The policy gate always applies, including when a rule proposes breaking.

## Finite scope and unknown facts

Every job has enumerable, finite bounds. Complement (`!`) only produces coordinates inside that scope; `offset` and `adjacent` may read a bounded halo beyond it. Unloaded chunks, missing target data and unavailable facts remain **Unknown**. `!Unknown` remains Unknown; neither `false` nor `DontCare` is a valid substitute. `SectionScanner` represents a section with true and known masks, so a partial section cannot silently become empty.

`Host.SelectionSource.finiteBounds` returns inclusive, dimension-qualified boxes. `Host.TargetSource.target` distinguishes `Exact`, `Clear`, `DontCare` and `Unknown`. Blueprint air is returned as an exact air state by the bridge; profile `include_air` decides whether to ignore or clear it. External sources must validate their session, placement identity and bounds before answering. Conflicting overlapping subregions are Unknown.

## Sessions, actions and confirmation

A session identifies one preview or job and prevents stale facts from crossing worlds or job lifetimes. The client captures world facts on the game thread. Pure scanning and planning can operate on immutable snapshots; a future worker implementation must never read live `ClientLevel` off-thread. Before submission, the action gateway checks the current session, target, world state, inventory, reach and policy. `Accepted` means only that an interaction was sent; success requires a server block/section update and the expected state. A source predicate that becomes false after the first step must not make the already claimed candidate disappear.

The current job uses conservative synchronous adapters. `Host.Epochs` exists, but the game job currently reports zero values; relevant registry, world, target, selection and inventory revisions are future work. Bound-expression fact requests and section target slices are also future work. Until then, live rechecks and short-lived reads protect correctness at a higher capture cost. The [open issues](https://github.com/billstark001/latticium/issues) track those extensions.

## Extension contract

The public blueprint entry point is `LatticiumClient.registerBlueprintProvider(ResourceId, BlueprintProvider)`, where `BlueprintProvider` combines the neutral selection and target interfaces. Registration occurs during loader client initialization, before profiles bind to a source ID. The bridge stays read-only and cannot replace the action gateway. The [bridge development guide](bridge-development.md) gives implementation and test steps.

The original discussions also considered a larger capability catalog, symbolic/offline binding, rule-source registry, section column requests, batch target snapshots, worker planning and a general mod SPI. Those are design directions, not currently supported Java APIs. The implemented DSL remains intentionally bounded: no arbitrary script execution, recursive functions, action calls or unbounded world query. New primitives must specify type, locality, facts, invalidation and cost before entering the section scanner.

## Binding, job state and resource budgets

Parsing is independent of Minecraft: resource IDs, tags and block properties remain symbolic until a game registry is available. The binder validates those symbols against the current registry and reports missing or invalid values rather than treating them as an empty set. A future capability catalog can expose signatures to offline tools without loading a game client, but offline type checking cannot claim a resource exists in a particular world. Registry or tag reloads must invalidate affected bindings.

User profiles are declarative JSON, not scripts. Activation is opt-in. `enter` reacts to a known outside-to-inside transition; `while` remains active while its predicate is known true. Unknown samples cannot create an edge. A player-relative scope freezes its anchor for a scan fragment and is refreshed after movement or explicit `/latticium refresh`. Persist profile definitions, enabled flags and saved selections by world and dimension; do not persist unconfirmed packets or assume an old action succeeded after reconnection.

The job controller retains claimed candidates and frozen targets across a multi-step attempt. A source predicate may stop matching after a break even though the target is unfinished. Cancellation and world exit stop new submissions; an already submitted action needs its receipt settled or discarded according to session lifetime. A policy limits per-tick actions and per-activation actions, and default breaking is denied. The scanner has finite section budgets and reports partial preview/query results explicitly.

The intended optimization path is to bind a dependency descriptor to each expression, request only needed section fact columns and halo, scan palette and coordinate masks, and send only candidates to the planner. A section is 16³ cells; known and true masks preserve three-valued logic. Coordinates, palette filtering and cheap predicates can run before expensive target or placement calls as long as the result is equivalent under Unknown. Main-thread capture time, allocation, scan throughput, cache invalidation, planner work and confirmation latency should be measured separately. Worker planning requires immutable leased snapshots and real revisions first.

## Validation and releases

Core tests validate DSL, profile, scanner, planner and fake-host behavior. Compilation and title-screen launches establish packaging and startup only. The [validation matrix](26.2-26.3-validation.md) records in-world interaction and bridge cases separately. Four main JARs and three validated bridge JARs are collected by the [release process](releasing.md); NeoForge 26.3 has no validated blueprint bridge.
