# Changelog

Changes intended for a public release are recorded here. Before creating a `vX.Y.Z` tag, move the relevant entries from `Unreleased` into a dated `## [X.Y.Z]` section matching `mod_version` in `gradle.properties`.

## [Unreleased]

### Added

- Continuous, event-driven job refresh by default, an explicit manual refresh mode, and `/latticium refresh`.
- CI for the Java core and seven Minecraft distributables.
- IntelliJ IDEA run configuration generation and a local release bundle script.
- Separate Modrinth publishing for the main mod and optional bridge mod.
