# Contributing

Use JDK 25 for the Gradle build. The pure Java core is compiled with `--release 21`.

```powershell
.\gradlew.bat formatAll check buildAll
```

For changes to game adapters or bridges, launch the affected clients and record the Minecraft and loader versions in the test report. The [validation record](docs/26.2-26.3-validation.md) lists live interaction checks that are still open. A title screen launch alone does not establish gameplay compatibility.

Import the repository root as a Gradle project in IntelliJ IDEA with JDK 25 as the Gradle JVM, then reload Gradle. Loom generates Fabric main and bridge client configurations from the Gradle projects; they delegate to Gradle's `runClient` task and its Java 25 launcher. Run or debug NeoForge clients through the corresponding `:versions:*:runClient` Gradle task. For optional bridges, use `:integrations:*:runClient`. Avoid IDEA's gutter action on `net.neoforged.devlaunch.Main.main()`, which creates an unrelated Java run configuration. Each target keeps its own `run` directory. Do not commit generated IDEA configurations, worlds, logs or build output.

On Windows, `./scripts/run-local-neoforge-smoke.ps1` uses the sibling MineDriver checkout to start the two main NeoForge clients and the Forgematica bridge client one at a time in isolated directories. It asserts the native title screen after overlays finish, saves a screenshot and verifies fresh complete reports, then closes each owned client. `-MineDriverRoot` selects another checkout. This is menu-readiness evidence; use the gameplay matrix for in-world interactions. Diagnosed Cloth Config metadata warnings require the explicit `-AcceptClothLogoWarning` switch described below.

`CHANGELOG.md` keeps changes under `Unreleased` until a release is prepared. A release tag must match `mod_version` and a dated changelog section. See [release instructions](docs/releasing.md).

## Gameplay probes with MineDriver

The optional [MineDriver 0.2.0](https://github.com/billstark001/minedriver/releases/tag/v0.2.0) adapter reuses `scripts/audit/` in a fresh, isolated client directory. It is enabled only by the init script; ordinary client runs, verification and release JARs do not include it. Build `:gradle-plugin:jar` in the MineDriver checkout first, then run from this repository root:

```powershell
.\gradlew.bat -I scripts/minedriver.init.gradle `
  '-Dminedriver.root=D:/Programs/minedriver' `
  '-Dminedriver.target=neoforge-26.3' `
  :versions:neoforge-26.3:mineDriverCheck
```

Select the loader/bridge project name with `minedriver.target` and use its corresponding qualified task. All four main clients and the three bridges use the same scenarios. MineDriver defaults to the sibling `../minedriver` checkout if `minedriver.root` is omitted. Reports and isolated saves live under the selected project's `build/reports/minedriver/` and `build/minedriver/` directories. A valid pass requires a successful Gradle exit and a fresh `result.json` with matching `runId`, `complete=true`, and `status=PASS`; `result.txt` records each gameplay assertion.

For ordinary Fabric world creation with the currently pinned Fabric API, explicitly add `'-Dminedriver.disableGametest=true'`. This excludes its two native GameTest modules from this ordinary-client fixture; the historical dynamic-test initialization failure is recorded in the validation report. It does not disable tests in normal builds or authorize accepting other startup warnings. The original `scripts/run-local-gameplay-audit.ps1` remains available for the standalone probe and read-only schematic fixture matrix.

Java scenarios in `scripts/minedriver/` must schedule short native operations with `ProbeContext.onClient`/`onServer`, and wait from the scenario worker. They must not call blocking driver operations from a game-thread callback. Assert server outcomes independently of local placement prediction or a successfully sent action.

Exact-state gameplay fixtures explicitly set `random_tick_speed=0` in their disposable world, so dirt does not randomly become grass between assertions. This condition is logged in `result.txt`; external state-change tests still inject authoritative conflicting states. `action-trace.txt` keeps at most 128 changed observations with job status, blocked reasons, visible state and the latest server packet state. Inspect it together with `client.log`, `result.json` and the failure screenshot when diagnosing a timeout. Ordinary worlds and source schematic files are not changed by this fixture rule.

`scripts/run-minedriver-audit.ps1` builds the driver and runs all seven targets sequentially, validates fresh report identities, and records a matrix summary. `-Targets` selects a subset; `-MineDriverRoot` selects another checkout. Optional `-SchematicsDirectory` passes read-only fixtures to bridge checks and compares complete before/after manifests, including SHA-256, size, modification time, additions and removals. The default scenario includes gameplay, interruption, survival replenishment, automatic-activation recovery, controlled chunk residency and four-language small-window UI checks.

Use `-Scenario` on the wrapper, or `'-Dminedriver.scenario=UiScenario'` on a direct Gradle command, for a focused run:

| Scenario | Scope |
|---|---|
| `LatticiumScenario` (default) | Full gameplay and UI; bridge fixtures when supplied; Forgematica registration stress when applicable |
| `StartupScenario` | Native title screen and no world; no gameplay claim |
| `UiScenario` | Fresh world and four-language controls, diagnostics and settings |
| `ActivationScenario` | Constant activation and recovery after a rejected START |
| `ResidencyScenario` | Controlled unload/reload packets, pending/expired receipts and deferred manual scan |
| `BridgeConcurrencyScenario` | Forgematica only; concurrent MaFgLib handler registration and cleanup |
| `RevisionScenario` | Native read-set projection, fine/section tracking, intent policies, inventory and dimension build-height boundaries |
| `PlanningScenario` | Native 301-expansion success, retry-cache pressure, final-cap exhaustion, primary/post-break predictor exceptions and fully known halo benchmarks |
| `ModdedScenario` | Registered test-mod axis states and a genuinely throwing block placement predictor |
| `DynamicScenario` | Server-confirmed fixed-position sand feed/mining with settling and finite repetition |
| `TravelScenario` | Actual Overworld/Nether travel; old sources and jobs cannot revive after return |
| `PerformanceScenario` | Separate capture, allocation and pure-scan percentiles at halos 0, 1 and 8 |
| `StreamingScenario` | 1,024 survival placements, candidate cap 16, movement/refill, exact server packet coordinates and material accounting |
| `JarInstallScenario` | Built artifact JAR discovery, main without bridge, disabled automatic profile and optional bridge discovery |
| `DedicatedScenario` | Real remote connection, authoritative RCON assertions, natural unload/reload, refill and real blueprint source on bridge targets |

The residency fixture deliberately sends integrated-server chunk packets. It does not establish natural-travel or dedicated-server behavior. A standalone concurrency run on another target fails rather than silently passing without exercising its dependency.

The full scenario also includes revision, prediction, registered-mod, settling, dimension-travel,
performance and streaming checks. Bridge targets additionally edit an unsaved source container without
advancing metadata, request the source mod's rebuild and verify fresh reads against an immutable old
slice. All fixtures are test-only. `LatticiumScenario`, `ModdedScenario` and `StreamingScenario` opt in
to a separate registered fixture mod; ordinary builds and distributable JARs exclude it.

Run real dedicated acceptance against a disposable loopback vanilla server (the script owns its
world/process and removes its short-lived RCON credential):

```powershell
.\scripts\run-dedicated-audit.ps1 -Version 26.2 -AcceptKnownLogoWarnings `
  -Targets fabric-26.2,neoforge-26.2,litematica-fabric-26.2,forgematica-neoforge-26.2
.\scripts\run-dedicated-audit.ps1 -Version 26.3 -AcceptKnownLogoWarnings `
  -Targets fabric-26.3,neoforge-26.3,litematica-fabric-26.3
.\scripts\run-minedriver-audit.ps1 -Scenario JarInstallScenario -AcceptKnownLogoWarnings
python scripts/verify-artifacts.py
```

`JarInstallScenario` strips repository class/resource directories from the game launch and seeds the
ordinary built JARs into the owned mods directory. It verifies their actual code-source location;
the surrounding game launch is still a development runtime. This is artifact discovery/startup
evidence, not certification of arbitrary production modpacks. Full gameplay is tested separately.

NeoForge 26.2 currently reports Cloth Config 26.2.155's deprecated `logoFile` metadata in development runs. This warning screen blocks title-screen readiness: client ticks, loaded mods and audio startup do not prove that the scenario has started. MineDriver fails promptly with `STARTUP_WARNINGS` and preserves the native issues, UI tree and failure screenshot. After identifying this exact warning, use `-AcceptClothLogoWarning` on the matrix wrapper, or `'-Dminedriver.acceptClothLogoWarning=true'` on a direct command. The opt-in accepts only Cloth Config's exact metadata warning and records it in `startup.warnings`; additional warnings and all errors still fail. It does not modify dependency JARs or suppress the warning in ordinary client runs. The standalone legacy probe refuses loading error screens; prefer MineDriver for diagnosed warning acceptance.

The pinned Forgematica stack additionally reports the same metadata warning for `forgematica`, `litematica`, `fabric_api_base`, `foxified_networking`, `mafglib`, `malilib` and `conditional_mixin`. `-AcceptKnownLogoWarnings` explicitly accepts this fixed list plus Cloth Config on the affected targets. Direct Forgematica commands need both `'-Dminedriver.acceptClothLogoWarning=true'` and `'-Dminedriver.acceptBridgeLogoWarnings=true'`. A new mod ID, a different issue key, or an error remains a blocker. Accepted issues are always retained in the report; no production/unsupported-runtime guard is relaxed.
