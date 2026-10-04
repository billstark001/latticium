# Building and releasing

The four `versions/*` JARs are Latticium itself. The three `integrations/*` JARs are optional bridges. The 26.3 NeoForge bridge is absent because a compatible schematic source has not been validated.

Run `./scripts/build-release.ps1` in PowerShell 7 (or `pwsh ./scripts/build-release.ps1` on Linux). The script builds all seven targets, checks the exact versioned JAR names, and writes `build/release/` with the JARs and SHA-256 checksums. `-SkipBuild` only collects existing builds. Run `./gradlew check` as well before preparing a release; live game checks remain manual.

To release, update `mod_version` in `gradle.properties`, move release notes into a dated `## [X.Y.Z]` section in `CHANGELOG.md`, and create a matching `vX.Y.Z` tag. The GitHub workflow checks the tag, version, and changelog section before building. It publishes the seven files together in a GitHub Release, with distinct filenames for the main mod and bridges.

Modrinth uses **two projects**: set repository variables `MODRINTH_MAIN_PROJECT_ID` and `MODRINTH_BRIDGE_PROJECT_ID`, plus the secret `MODRINTH_TOKEN`. The main project receives four versions. After those uploads complete, the bridge project receives three versions with required dependencies on the matching Latticium version and Litematica or Forgematica. No bridge JAR is uploaded to the main project. If these settings are absent, the workflow still creates the GitHub Release and skips Modrinth. A manual workflow run against an existing tag has a `publish_modrinth` switch, off by default.
