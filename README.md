# Latticium — developer README

Latticium is a client-side construction engine for Minecraft 26.2 and 26.3 on Fabric and NeoForge. This README describes the repository and its contracts. The player-facing text for Modrinth is in [README_MODRINTH.md](README_MODRINTH.md); gameplay instructions are in [Getting started](docs/getting-started.md).

Version 0.0.2 adds native task controls, a small HUD, diagnostics, and one Cloth Config / AutoConfig settings model for both loaders. UI translations are centralized in `assets/lang/` and packaged into each shared game adapter. Cloth Config is bundled; Fabric Mod Menu is an optional settings entry and NeoForge uses its built-in mod-list entry. F8 opens task controls and F9 pauses/resumes a task; selection rendering is outside this release's scope.

## Repository layout

| Path | Responsibility | Java |
| --- | --- | --- |
| `dsl-core/` | Typed, read-only expression parser and binder | 21 |
| `planning-core/` | Profiles, finite section scanning, target semantics, planner, job control | 21 |
| `versions/shared-mc-26.*/` | Minecraft state, world, inventory, placement and action adapters | 25 |
| `versions/fabric-26.*/`, `versions/neoforge-26.*/` | Loader entry points and distributable main mods | 25 |
| `integrations/` | Optional, separately packaged schematic bridges | 25 |
| `scripts/` | Official data, local smoke checks and release assembly | — |

The core receives neutral facts and capabilities; it does not depend on Minecraft or loader classes. Each game adapter captures live data on the client thread, and the action gateway submits ordinary player interactions. A submitted action is only complete after a server update and the expected world state are observed. The [architecture and host contract](docs/architecture.md) records the durable decisions behind these boundaries. The [DSL reference](docs/dsl-reference.md) describes implemented syntax; the [bridge guide](docs/bridge-development.md) describes extension points.

## Build and test

Use JDK 25 to run Gradle. Core modules compile with `--release 21`; game modules compile with `--release 25`.

```powershell
.\gradlew.bat formatAll check buildAll
.\gradlew.bat fetchOfficialMinecraft bakeOfficialMinecraft :planning-core:officialTest
```

The build produces four main JARs and three optional bridge JARs. Bridge compile dependencies are downloaded to `.tmp/reference-mods/` with pinned SHA-512 hashes; they are not bundled into bridge JARs. See [official data validation](docs/official-data-validation.md), the [test record](docs/26.2-26.3-validation.md), and [release procedure](docs/releasing.md).

| Minecraft | Loader | Main project | Optional bridge project |
| --- | --- | --- | --- |
| 26.2 | Fabric | `versions/fabric-26.2` | `integrations/litematica-fabric-26.2` |
| 26.3 | Fabric | `versions/fabric-26.3` | `integrations/litematica-fabric-26.3` |
| 26.2 | NeoForge | `versions/neoforge-26.2` | `integrations/forgematica-neoforge-26.2` |
| 26.3 | NeoForge | `versions/neoforge-26.3` | No validated schematic source yet |

## IntelliJ IDEA and local clients

Import the repository root as a Gradle project with JDK 25 as the Gradle JVM. All game modules use a Java 25 toolchain for compilation and Gradle client launches. On Gradle import, Loom generates the Fabric main and bridge run configurations; those configurations delegate to Gradle so the Java 25 launcher is used. NeoForge clients can be launched or debugged through their Gradle `runClient` tasks. If an old IDEA configuration still points at JDK 21, refresh the Gradle project and remove that stale local configuration. No shared `.run/` files are required. To explicitly regenerate Fabric configurations from IDEA, run the root `syncIdeaRunConfigurations` task.

For a reproducible client launch, run one of these tasks (the optional bridges have matching `:integrations:*:runClient` tasks):

```powershell
.\gradlew.bat :versions:fabric-26.2:runClient
.\gradlew.bat :versions:neoforge-26.2:runClient
```

Each target has its own `run/` directory. The [contribution guide](CONTRIBUTING.md) covers local smoke checks and test expectations. Do not commit generated IDEA settings, worlds, logs, or build output.

## Current maturity

The Java build and tests pass, and development clients have reached the title screen; NeoForge 26.2 has entered a singleplayer world. The [validation record](docs/26.2-26.3-validation.md) distinguishes those checks from gameplay acceptance. Full live fill, replace, clear, and blueprint construction checks across all targets are still open. Main mods work without schematic mods; bridges require their matching Latticium and source mod versions.
