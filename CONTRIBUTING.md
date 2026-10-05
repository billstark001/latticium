# Contributing

Use JDK 25 for the Gradle build. The pure Java core is compiled with `--release 21`.

```powershell
.\gradlew.bat formatAll check buildAll
```

For changes to game adapters or bridges, launch the affected clients and record the Minecraft and loader versions in the test report. The [validation record](docs/26.2-26.3-validation.md) lists live interaction checks that are still open. A title screen launch alone does not establish gameplay compatibility.

Import the repository root as a Gradle project in IntelliJ IDEA with JDK 25 as the Gradle JVM, then reload Gradle. Loom generates Fabric main and bridge client configurations from the Gradle projects; they delegate to Gradle's `runClient` task and its Java 25 launcher. Run or debug NeoForge clients through the corresponding `:versions:*:runClient` Gradle task. For optional bridges, use `:integrations:*:runClient`. Avoid IDEA's gutter action on `net.neoforged.devlaunch.Main.main()`, which creates an unrelated Java run configuration. Each target keeps its own `run` directory. Do not commit generated IDEA configurations, worlds, logs or build output.

On Windows, `./scripts/run-local-neoforge-smoke.ps1` starts the two main NeoForge clients and the Forgematica bridge client one at a time, checks that the expected mods and sound engine load, then closes each client. It is a startup smoke check, not an in-world interaction test.

`CHANGELOG.md` keeps changes under `Unreleased` until a release is prepared. A release tag must match `mod_version` and a dated changelog section. See [release instructions](docs/releasing.md).
