#!/usr/bin/env python3
"""Run Mojang's bundled data generator and bake vanilla registry test catalogs.

Input and output remain in .tmp/minecraft-official by default. The baked catalog
is a test fixture for pure JVM code, not a distributable Minecraft data file.
"""

import argparse
import hashlib
import io
import json
from pathlib import Path
import shutil
import subprocess
import zipfile

from fetch_official_minecraft import ROOT


KINDS = {
    "block": ("minecraft:block", "tags/block/"),
    "item": ("minecraft:item", "tags/item/"),
    "fluid": ("minecraft:fluid", "tags/fluid/"),
    "biome": (None, "tags/worldgen/biome/"),
}


def digest(path: Path) -> str:
    return hashlib.sha1(path.read_bytes()).hexdigest()


def generate(version_dir: Path, java: str) -> None:
    reports = version_dir / "generated" / "reports"
    server_sha1 = digest(version_dir / "server.jar")
    receipt = json.loads((version_dir / "receipt.json").read_text())
    if server_sha1 != receipt["downloads"]["server"]["sha1"]:
        raise ValueError(f"Server JAR SHA-1 does not match Mojang metadata: {version_dir.name}")
    stamp = version_dir / "generated" / "server.sha1"
    if (reports / "blocks.json").exists() and (reports / "registries.json").exists() and stamp.exists() and stamp.read_text().strip() == server_sha1:
        print(f"cached reports {version_dir.name}")
        return
    shutil.rmtree(version_dir / "generated", ignore_errors=True)
    command = [
        java,
        "-DbundlerMainClass=net.minecraft.data.Main",
        f"-DbundlerRepoDir={version_dir / 'bundler'}",
        "-jar", str(version_dir / "server.jar"),
        "--reports", "--output", str(version_dir / "generated"),
    ]
    log_path = version_dir / "data-generator.log"
    with log_path.open("w") as log:
        result = subprocess.run(command, cwd=version_dir, stdout=log, stderr=subprocess.STDOUT, check=False)
    if result.returncode:
        tail = "\n".join(log_path.read_text().splitlines()[-25:])
        raise RuntimeError(f"Mojang data generator failed for {version_dir.name}:\n{tail}")
    print(f"generated reports {version_dir.name}; log: {log_path}")
    if not (reports / "blocks.json").is_file() or not (reports / "registries.json").is_file():
        raise RuntimeError(f"Mojang data generator did not produce reports for {version_dir.name}")
    stamp.write_text(server_sha1 + "\n")


def bake(version_dir: Path) -> dict:
    reports = version_dir / "generated" / "reports"
    blocks_file = reports / "blocks.json"
    registries_file = reports / "registries.json"
    blocks_report = json.loads(blocks_file.read_text())
    registries_report = json.loads(registries_file.read_text())
    with zipfile.ZipFile(version_dir / "server.jar") as outer:
        inner_name = next(name for name in outer.namelist()
                          if name.startswith("META-INF/versions/") and name.endswith(".jar"))
        with zipfile.ZipFile(io.BytesIO(outer.read(inner_name))) as inner:
            names = inner.namelist()
            biomes = {"minecraft:" + name.removeprefix("data/minecraft/worldgen/biome/").removesuffix(".json")
                      for name in names if name.startswith("data/minecraft/worldgen/biome/") and name.endswith(".json")}
            universes = {kind: sorted(biomes if kind == "biome" else registries_report[registry]["entries"])
                         for kind, (registry, _) in KINDS.items()}
            # The block report is the authoritative source for legal state combinations.
            if set(blocks_report) != set(universes["block"]):
                raise ValueError("Block report and registry dump disagree")
            tags = {}
            for kind, (_, prefix) in KINDS.items():
                full_prefix = "data/minecraft/" + prefix
                definitions = {
                    "minecraft:" + name.removeprefix(full_prefix).removesuffix(".json"): json.loads(inner.read(name))
                    for name in names if name.startswith(full_prefix) and name.endswith(".json")
                }
                universe = set(universes[kind])
                resolved = {}
                active = set()

                def expand(tag):
                    if tag in resolved:
                        return resolved[tag]
                    if tag in active:
                        raise ValueError(f"Cyclic {kind} tag: {tag}")
                    if tag not in definitions:
                        raise KeyError(f"Missing {kind} tag: {tag}")
                    active.add(tag)
                    values = set()
                    for member in definitions[tag]["values"]:
                        required = member.get("required", True) if isinstance(member, dict) else True
                        identifier = member["id"] if isinstance(member, dict) else member
                        if identifier.startswith("#"):
                            reference = identifier[1:]
                            if reference in definitions:
                                values.update(expand(reference))
                            elif required:
                                raise KeyError(f"Missing required {kind} tag: {reference}")
                        elif identifier in universe:
                            values.add(identifier)
                        elif required:
                            raise KeyError(f"Missing required {kind} ID: {identifier}")
                    active.remove(tag)
                    resolved[tag] = sorted(values)
                    return resolved[tag]

                for tag in definitions:
                    expand(tag)
                tags[kind] = resolved
    blocks = {
        identifier: {
            "properties": definition.get("properties", {}),
            "states": [state.get("properties", {}) for state in definition["states"]],
        }
        for identifier, definition in blocks_report.items()
    }
    catalog = {
        "schema": 1,
        "version": version_dir.name,
        "sourceSha1": {
            "serverJar": digest(version_dir / "server.jar"),
            "blocksReport": digest(blocks_file),
            "registriesReport": digest(registries_file),
        },
        "universes": universes,
        "blocks": blocks,
        "tags": tags,
    }
    output = version_dir / "catalog.json"
    output.write_text(json.dumps(catalog, sort_keys=True, separators=(",", ":")) + "\n")
    summary = {
        "version": version_dir.name,
        "blocks": len(universes["block"]),
        "states": sum(len(block["states"]) for block in blocks.values()),
        "items": len(universes["item"]),
        "fluids": len(universes["fluid"]),
        "biomes": len(universes["biome"]),
        "tags": {kind: len(group) for kind, group in tags.items()},
        "catalogSha1": digest(output),
    }
    (version_dir / "catalog-summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    return summary


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("versions", nargs="*", default=["26.2", "26.3"])
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--java", default="java", help="Java 25 executable for Mojang's data generator")
    args = parser.parse_args()
    for version in args.versions:
        directory = args.root.resolve() / version
        if not (directory / "server.jar").is_file():
            raise FileNotFoundError(f"Run fetch_official_minecraft.py first: {directory / 'server.jar'}")
        generate(directory, args.java)
        print(json.dumps(bake(directory), indent=2))


if __name__ == "__main__":
    main()
