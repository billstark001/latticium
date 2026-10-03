# Latticium

This repository is implementing the Minecraft-independent core of a client construction mod. The architecture and language decisions are in [`discussion/`](discussion/). Java packages use `io.github.billstark001.latticium`.

## Build and test

Use JDK 21 as the Gradle toolchain (set `JAVA_HOME` if Gradle does not discover it). The core modules compile with `--release 21` and have no Minecraft dependency.

```sh
./gradlew formatAll   # Spotless: google-java-format for Java, ktfmt for Gradle Kotlin scripts
./gradlew check       # format validation, compiler diagnostics, 500-line source limit, offline tests
./gradlew lint        # checks without running the tests
```

Each formatted Java or Kotlin file, including Gradle Kotlin scripts, must stay at or below 500 lines; both `formatAll` and `check` enforce the limit. Java compilation uses `-Xlint:all,-serial -Werror`. The official-data fetcher and catalog baker are Kotlin Gradle tasks; no Python runtime is needed.

To validate with locally downloaded Mojang 26.2/26.3 data, follow [official data validation](docs/official-data-validation.md).

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

- Set types: `PosSet`, `BlockSet`, `StateSet`, `ItemSet`, `BiomeSet`, `FluidSet`; type checked `!`, `&`, `|`, literals, declarations and nonrecursive `def` with set and bounded integer parameters. Trusted hosts can register typed, bounded read-only primitives.
- Position predicates: coordinates, boxes, player/point spheres, selection, neighbor/offset, current/target state, biome, fluid, light, solid, surface, target comparison and inventory.
- With a real registry, built-in state set predicates reject impossible block states, and numeric property predicates require a known integer-valued property at binding time.
- The offline air predicate recognizes the three vanilla air blocks (`air`, `cave_air`, `void_air`) for surface and clear-goal checks. A future game adapter must provide version-specific behavior for modded air-like blocks.
- Explicit `Truth.UNKNOWN` for missing facts, including under negation. Symbolic compilation allows a rule/profile to be checked before a world registry exists; real registry binding must happen before evaluation.
- Strict schema-1 profile JSON with duplicate/unknown key rejection, typed expression fields, explicit declaration module imports, target alternatives and policy budgets. An automatic scan requires a finite root scope expression. Activation tracking implements `enter` and `while` without turning unknown facts into false transitions.
- Profile binding isolates imported declarations from the caller's compiler. With a real registry, it rejects preferred items that are definitely outside `target.items`; membership depending on unavailable facts remains unresolved until runtime.
- Bounded read-only query execution, 4096-bit section result masks, inventory-aware material choice that distinguishes missing facts from unsupported placement, and a policy-filtered state transition search. `RuleBook` accepts exact before/after states and DSL guards; a version-specific oracle must supply real placement behavior.
- Neutral capture, action and observation contracts plus a session-bound job controller that waits for observation before confirming a submitted step.

Automatic profile scopes currently need a visible finite root such as `box(...)`, `sphere(...)`, or `selection(...)`; intersections with such a root and unions of finite roots are accepted. A named or function-wrapped finite expression is not yet proven finite by the profile reader.

## Offline budget behavior

`QueryRunner` computes complete match and unknown counts for the supplied finite domain. Its cell budget counts every visited coordinate, including visits to overlapping bounds. Exceeding the budget raises an error instead of returning partial counts.

`SectionScanner.scan` returns the first sections up to its budget on each call; it does not retain a cursor. A caller continuing across ticks should schedule remaining keys through `scanSection`, which accepts one section's captured facts.
`SectionResult` can combine same-section masks with three-valued `and` and `or`. Its `not(domain)` requires an explicit finite cell mask so positions outside the enumerated domain remain known false; unknown cells remain unknown inside it.

Module imports are limited to 64 dependency levels and 512 modules per loader. A compiler retains at most 10,000 declarations and functions in total. Rule planning treats fluid, light and solidity as unknown after a speculative block-state change until fresh world facts are captured.

A `target.clear` profile can bind with `policy.break: deny`; the planner then accepts an already clear cell or searches only allowed non-break transitions. If no known path reaches air, it returns `NoPlan`, or `Deferred` when relevant facts are unavailable.

## Current boundary

The offline implementation does not send actions, predict Minecraft placement, import `.litematic`, or promise compatibility with any game version. In particular, it does not yet include a live world driver, complete registry provenance validation, every grammar feature, section-prioritized spatial sorting, or per-section snapshot capture. These are required before shipping a working client mod. A planner result is only a proposed sequence; a future game adapter must recheck preconditions and observe world changes after each action.

For item targets, the offline material selector can recognize an already acceptable current state only through items available in its inventory snapshot and states reported by the placement oracle. Recognizing such a state without an available item needs a version-specific item-to-state mapping from a future adapter.
