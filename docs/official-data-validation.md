# Official Minecraft data validation

The offline core can be tested against Mojang's [version manifest](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json) for Java Edition 26.2 and 26.3. The fetcher follows only official `piston-meta.mojang.com` and `piston-data.mojang.com` links from that manifest. It verifies each version JSON, asset index and JAR against the SHA-1 supplied by its parent metadata.

From the repository root:

```sh
python3 scripts/fetch_official_minecraft.py
python3 scripts/bake_official_minecraft.py --java /path/to/jdk25/bin/java
JAVA_HOME=/path/to/jdk21 ./gradlew test :planning-core:officialTest
```

The bake step invokes Mojang's `net.minecraft.data.Main --reports` from each official server bundle. It reads the resulting block and registry reports plus vanilla tags and biome definitions in the bundle, resolves nested tags, and writes `catalog.json` for each version. The reports, JARs, catalogs, logs and receipts all live under `.tmp/minecraft-official/`, which Git ignores. The scripts and Java catalog reader are reusable; the downloaded data is disposable.

The official test suite verifies:

- Every reported block has legal states and each state's values belong to its reported property schema.
- Block, item, fluid and biome IDs and nested tags resolve in their own version.
- Version-only block IDs fail to bind in 26.2 and bind in 26.3.
- Real state literals, invalid properties, state tag membership, the basalt lava profile and exact rule states bind as expected.

For the downloaded manifests used in this run, 26.2 generated 1,196 blocks, 32,366 states, 1,537 items and 66 biomes; 26.3 generated 1,286 blocks, 35,723 states, 1,658 items and 67 biomes. The detailed hashes are in each temporary `receipt.json` and `catalog-summary.json`.

This validates offline registry and rule logic against vanilla data. It does not test client world capture, placement prediction, loader integration or runtime behavior of installed mods.
