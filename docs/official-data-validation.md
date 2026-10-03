# Official Minecraft data validation

The offline core can be tested against Mojang's [version manifest](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json) for Java Edition 26.2 and 26.3. The fetcher follows only official `piston-meta.mojang.com` and `piston-data.mojang.com` links from that manifest. It verifies each version JSON, asset index and JAR against the SHA-1 supplied by its parent metadata.

From the repository root:

```sh
./gradlew fetchOfficialMinecraft
./gradlew bakeOfficialMinecraft -PofficialJava=/path/to/jdk25/bin/java
./gradlew check :planning-core:officialTest
```

`fetchOfficialMinecraft` and `bakeOfficialMinecraft` are implemented in [`scripts/official-data.gradle.kts`](../scripts/official-data.gradle.kts). They default to both versions. Use `-PofficialVersions=26.2` to select one, `-PofficialClientOnly=true` for a client-only fetch, or `-PrefreshOfficialManifest=true` to refresh the cached version manifest. `bakeOfficialMinecraft` requires the server JAR and Java 25. `testOfficialDataHelpers` checks URL and tag resolution edge cases without downloading data.

Gradle itself uses the project's JDK 21 toolchain. The `officialJava` property selects a separate Java 25 executable only for Mojang's data generator. Set `JAVA_HOME` to JDK 21 when Gradle cannot discover it automatically.

The bake step invokes Mojang's `net.minecraft.data.Main --reports` from each official server bundle. It reads the resulting block and registry reports plus vanilla tags and biome definitions in the bundle, resolves nested tags, and writes `catalog.json` for each version. The reports, JARs, catalogs, logs and receipts all live under `.tmp/minecraft-official/`, which Git ignores. Downloads and redirects are restricted to Mojang's official metadata and data hosts. The Kotlin tasks and Java catalog reader are reusable; the downloaded data is disposable.

The official test suite verifies:

- Every reported block has legal states and each state's values belong to its reported property schema.
- Block, item, fluid and biome IDs and nested tags resolve in their own version.
- Version-only block IDs fail to bind in 26.2 and bind in 26.3.
- Real state literals, invalid properties, state tag membership, the basalt lava profile and exact rule states bind as expected.

For the downloaded manifests used in this run, 26.2 generated 1,196 blocks, 32,366 states, 1,537 items and 66 biomes; 26.3 generated 1,286 blocks, 35,723 states, 1,658 items and 67 biomes. The detailed hashes are in each temporary `receipt.json` and `catalog-summary.json`.

This validates offline registry and rule logic against vanilla data. It does not test client world capture, placement prediction, loader integration or runtime behavior of installed mods.
