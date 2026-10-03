# Latticium

This repository is implementing the Minecraft-independent core of a client construction mod. The architecture and language decisions are in [`discussion/`](discussion/). Java packages use `io.github.billstark001.latticium`.

## Build and test

Run `./gradlew test` with JDK 21 available as a Gradle toolchain. `dsl-core` and `planning-core` compile with `--release 21` and run their tests on Java 21. They have no Minecraft dependency.

## Modules

| Directory | Responsibility |
|---|---|
| `dsl-core` | Typed set syntax, symbolic and registry binding, three-valued predicate evaluation, neutral world model |
| `planning-core` | Strict JSON profiles, finite section scanner, declarative exact-state rules, bounded transition planner |
| `rules-vanilla` | Future version-scoped declarative rule bundles |
| `versions/shared-mc-*` | Future Minecraft-version registry, snapshot and placement adapters |
| `versions/{fabric,neoforge}-*` | Future loader entrypoints, actions and observation |
| `integrations/*` | Future optional schematic providers |

The `versions` and `integrations` directories are integration boundaries, not build modules yet. Each eventual Minecraft/loader release will assemble one client JAR. No Minecraft artifacts, API mappings or loader assumptions are pinned in the offline build.

## Implemented offline contract

- Set types: `PosSet`, `BlockSet`, `StateSet`, `ItemSet`, `BiomeSet`, `FluidSet`; type checked `!`, `&`, `|`, literals, declarations and nonrecursive `def` with set parameters. Trusted hosts can register typed, bounded read-only primitives.
- Position predicates: coordinates, boxes, player/point spheres, selection, neighbor/offset, current/target state, biome, fluid, light, solid, surface, target comparison and inventory.
- Explicit `Truth.UNKNOWN` for missing facts, including under negation. Symbolic compilation allows a rule/profile to be checked before a world registry exists; real registry binding must happen before evaluation.
- Strict schema-1 profile JSON with duplicate/unknown key rejection, typed expression fields, target alternatives and policy budgets. An automatic scan requires a finite root scope expression.
- Bounded read-only query execution, 4096-bit section result masks, inventory-aware material choice, and a policy-filtered state transition search. `RuleBook` accepts exact before/after states and DSL guards; a version-specific oracle must supply real placement behavior.
- Neutral capture, action and observation contracts plus a session-bound job controller that waits for observation before confirming a submitted step.

## Current boundary

The offline implementation does not send actions, predict Minecraft placement, import `.litematic`, or promise compatibility with any game version. In particular, it does not yet include a live world driver, full registry schema validation, all grammar features such as integer `def` parameters, optimized sorting, or per-section snapshot capture. These are required before shipping a working client mod. A planner result is only a proposed sequence; a future game adapter must recheck preconditions and observe world changes after each action.
