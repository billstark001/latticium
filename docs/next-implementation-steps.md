# Next implementation steps

The [architecture discussion](../discussion/004-host-capabilities.md) defines a larger host interface. The current code has a working finite job path, but several of those contracts are still represented by conservative, synchronous adapters. This page identifies the next code seams and the evidence needed before widening them.

## Fact requests and batch targets

`MinecraftSectionSource` currently copies block state, biome, fluid, light, solidity, player, and inventory facts for a section and its halo. `ClientJob` now skips bulk target reads when the bound selection expression contains no direct target-view function, using `TargetReads`. The next step is a bound expression dependency descriptor that names required fact columns and read radius. Keep that descriptor with `Compiler.Bound`, including dependencies of expanded functions and future registered primitives. Use it to request only those columns from the version adapter.

The bridge `target(position, session)` method is a correctness baseline. A target-dependent selection can still call it for many cells. Add an optional section target slice to `Host.TargetSource` or a companion interface; a provider without a batch implementation must retain the same four-state semantics (`Exact`, `Clear`, `DontCare`, `Unknown`). Snapshot identity and bounds checks must still run before a slice is accepted. Compare the slice's true/known masks with point reads for unloaded chunks, air, overlaps, and placement changes.

## Real revisions and stale plans

`ClientJob.epochs()` currently returns zero values. The action gateway therefore relies on its live checks of session, block, target, hotbar, reach, and predicted click context. Wire actual registry/tag, selection, inventory, target, and relevant world revisions into `Host.Epochs`. Each bound expression and proposal should declare which revisions it uses, so an unrelated block update does not invalidate all work. Test changed facts between scan, plan, submission, and receipt observation with a fake host before enabling worker-thread planning.

## Multi-step replacement

The native oracle predicts actions against the present world. A non-air replacement may need a confirmed break before the next placement can be predicted. The client now tracks the last confirmed state and refuses to start a break when fewer than two actions remain or no ordinary hotbar block item can supply the target block type. A more complete transition model should represent the post-break fact window and its uncertainty, then reserve sufficient budget for a verifiable follow-up. Preserve the current gateway rule: a proposal is never success until a server update and the expected world state are observed.

The planner now treats an exhausted search node budget as Deferred, so a partial search cannot authorize a destructive fallback. Its next step is a resumable search cursor or a larger adaptive budget for the same candidate, with a hard per-tick work cap. Otherwise a difficult candidate repeats the same bounded search on each retry without making progress. Test a path found only after the initial node cap and verify that no break is submitted before that path has been ruled out.

## Acceptance gate

Before raising supported behavior, run the [in-world validation matrix](26.2-26.3-validation.md) for all four main targets and all three optional bridges. Record per-tick capture time and allocation for a target-free profile and a target-dependent blueprint profile. Keep unsupported modded interactions explicit until their oracle and observation contracts have tests.
