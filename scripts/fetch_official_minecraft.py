#!/usr/bin/env python3
"""Fetch Mojang launcher artifacts into an ignored, disposable local directory.

Only URLs supplied by the official version manifest are used, and every nested
artifact is verified against the SHA-1 in its parent manifest before use.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
from urllib.parse import urlparse
from urllib.request import urlopen


MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
ALLOWED_HOSTS = {"piston-meta.mojang.com", "piston-data.mojang.com"}
ROOT = Path(__file__).resolve().parents[1] / ".tmp" / "minecraft-official"


def fetch(url: str, path: Path, expected_sha1: str | None = None) -> None:
    parsed = urlparse(url)
    if parsed.scheme != "https" or parsed.hostname not in ALLOWED_HOSTS:
        raise ValueError(f"Nonofficial download URL: {url}")
    if path.exists() and (expected_sha1 is None or sha1(path) == expected_sha1):
        print(f"cached {path}")
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    part = path.with_name(path.name + ".part")
    digest = hashlib.sha1()
    try:
        with urlopen(url, timeout=60) as response, part.open("wb") as out:
            while chunk := response.read(1024 * 1024):
                out.write(chunk)
                digest.update(chunk)
        if expected_sha1 and digest.hexdigest() != expected_sha1:
            raise ValueError(f"SHA-1 mismatch for {path}: {digest.hexdigest()}")
        os.replace(part, path)
        print(f"fetched {path} ({path.stat().st_size} bytes)")
    finally:
        part.unlink(missing_ok=True)


def sha1(path: Path) -> str:
    digest = hashlib.sha1()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("versions", nargs="*", default=["26.2", "26.3"])
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--client-only", action="store_true")
    parser.add_argument("--refresh-manifest", action="store_true")
    args = parser.parse_args()
    root = args.root.resolve()
    if args.refresh_manifest:
        (root / "version_manifest_v2.json").unlink(missing_ok=True)
    fetch(MANIFEST, root / "version_manifest_v2.json")
    manifest = json.loads((root / "version_manifest_v2.json").read_text())
    available = {entry["id"]: entry for entry in manifest["versions"]}
    for version in args.versions:
        if version not in available:
            raise ValueError(f"Version absent from Mojang manifest: {version}")
        directory = root / version
        entry = available[version]
        fetch(entry["url"], directory / "version.json", entry["sha1"])
        metadata = json.loads((directory / "version.json").read_text())
        index = metadata["assetIndex"]
        fetch(index["url"], directory / "asset-index.json", index["sha1"])
        for name in (["client"] if args.client_only else ["client", "server"]):
            download = metadata["downloads"][name]
            fetch(download["url"], directory / f"{name}.jar", download["sha1"])
        receipt = {
            "version": version,
            "javaMajor": metadata["javaVersion"]["majorVersion"],
            "metadataSha1": entry["sha1"],
            "assetIndexSha1": index["sha1"],
            "downloads": {key: {"sha1": value["sha1"], "size": value["size"]}
                          for key, value in metadata["downloads"].items()
                          if key in ("client", "server")},
        }
        (directory / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
        print(f"verified {version}: Java {receipt['javaMajor']}")


if __name__ == "__main__":
    main()
