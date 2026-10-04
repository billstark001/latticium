# Latticium

Latticium is an experimental client construction mod for Minecraft 26.2 and 26.3. Start with the [player and profile guide](docs/getting-started.md) and the complete [DSL and profile reference](docs/dsl-reference.md); optional mod authors can use the [bridge guide](docs/bridge-development.md). The design background is in [discussion/](discussion/), with concrete [next implementation steps](docs/next-implementation-steps.md). The implementation has a Java 21 game-independent core and Java 25 game adapters.

## Build

Use JDK 25 for Gradle. The core modules compile with `--release 21`.

```powershell
.\gradlew.bat formatAll check build
.\gradlew.bat fetchOfficialMinecraft bakeOfficialMinecraft :planning-core:officialTest
```

The first command builds four client mods and three optional bridges. The second verifies registry fixtures generated from the official 26.2 and 26.3 game data. The build downloads exact reference versions of Litematica, MaLiLib, Forgematica, and MaFgLib into `.tmp/reference-mods`; these source mods are not bundled into the bridge JARs. See [validation details](docs/26.2-26.3-validation.md) and [official data validation](docs/official-data-validation.md).

| Game | Loader | Main mod | Optional bridge |
|---|---|---|---|
| 26.2 | Fabric | `versions/fabric-26.2/build/libs/` | `integrations/litematica-fabric-26.2/build/libs/` |
| 26.3 | Fabric | `versions/fabric-26.3/build/libs/` | `integrations/litematica-fabric-26.3/build/libs/` |
| 26.2 | NeoForge | `versions/neoforge-26.2/build/libs/` | `integrations/forgematica-neoforge-26.2/build/libs/` |
| 26.3 | NeoForge | `versions/neoforge-26.3/build/libs/` | None until a compatible schematic source can be tested |

The main mod has no schematic-mod dependency. The bridge JARs require the matching Latticium and source mod versions. Each target JAR uses the game and loader dependencies for its Minecraft version.

## Client use

All operations are opt-in. No profile starts scanning or acting merely because the mod is installed. In a world, set the two corners with `/latticium pos1` and `/latticium pos2`, then save them with `/latticium selection save build`. The commands `/latticium fill <item>`, `/latticium replace <source-block> <item>`, and `/latticium clear` submit finite jobs using that selection. `/latticium status`, `pause`, `resume`, and `cancel` control the current job.

Place a schema-1 `.latticium.json` file in `config/latticium/profiles`, then load it with `/latticium profile load <file>`, inspect it using `/latticium preview <id>`, and start it with `/latticium start <id>`. `/latticium enable <id>` allows its declared `enter` or `while` activation; `disable` removes that permission. `/latticium query <expression>` evaluates a predicate in the saved build selection. Query and preview report a bounded partial scan with an explicit total-section count. The API entry point is `io.github.billstark001.latticium.mc.LatticiumClient`.

Jobs use ordinary player block interaction and break packets. The action gate checks the current session, target, block, inventory, reach, and break policy just before sending. It waits for a server block or section update and the expected world state before confirming a step. Unknown or unsupported placements are reported as blocked; arbitrary modded block behavior is not predicted.

The optional bridges read the source mod's active placement and its in-memory schematic world. They do not parse `.litematic` files. Blueprint air is ignored by default and becomes a clear target only when the profile asks for `include_air`. Missing or changed active placements and overlapping subregions are reported instead of silently interpreted as empty targets.

## Implementation status

The seven JARs compile, core and official-data tests pass, and development clients have reached the title screen with the respective source mods. NeoForge 26.2 has also entered a singleplayer world. The [validation record](docs/26.2-26.3-validation.md) lists remaining in-game checks and known limitations. These builds are experimental until live fill, replace, clear, and blueprint construction are exercised on every target.
