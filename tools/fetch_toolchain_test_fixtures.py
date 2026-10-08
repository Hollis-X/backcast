#!/usr/bin/env python3
"""Explicitly fetch pinned release archives for the payload audit regression tests.

This command uses the public GitHub Release, never runs from the test suite, and
never places large archives in APK assets. Existing verified files are reused.
"""
import argparse
import hashlib
import json
import pathlib
import urllib.request


def verify(path, artifact):
    if not path.is_file() or path.stat().st_size != artifact["bytes"]:
        return False
    whole = hashlib.sha256()
    with path.open("rb") as stream:
        first = stream.read(artifact["prefix_bytes"])
        if hashlib.sha256(first).hexdigest() != artifact["prefix_sha256"]:
            return False
        stream.seek(0)
        for expected in artifact["chunk_sha256"]:
            block = stream.read(artifact["chunk_bytes"])
            if not block or hashlib.sha256(block).hexdigest() != expected:
                return False
            whole.update(block)
        if stream.read(1):
            return False
    return whole.hexdigest() == artifact["sha256"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=pathlib.Path, help="Default: ignored app/build/toolchain-release")
    parser.add_argument("--verify-only", action="store_true", help="Check existing archives without using the network")
    args = parser.parse_args()
    repo = pathlib.Path(__file__).resolve().parents[1]
    output = args.output or repo / "app/build/toolchain-release"
    data = json.loads((repo / "app/src/main/assets/toolchain/manifest.json").read_text())
    if output.is_symlink():
        raise ValueError("Refusing an aliased fixture directory")
    output.mkdir(parents=True, exist_ok=True)
    for artifact in data["artifacts"]:
        name = artifact["file"]
        if pathlib.PurePath(name).name != name:
            raise ValueError("Invalid release asset name")
        expected_url = "https://github.com/Hollis-X/backcast/releases/download/toolchain-" + data["version"] + "/" + name
        if artifact["url"] != expected_url:
            raise ValueError("Release URL differs from the pinned repository and version")
        destination = output / name
        if destination.is_symlink():
            raise ValueError("Refusing an aliased fixture file")
        if verify(destination, artifact):
            print("Verified " + name)
            continue
        if args.verify_only:
            raise ValueError("Missing or damaged fixture: " + name)
        partial = output / (name + ".download")
        if partial.is_symlink():
            raise ValueError("Refusing an aliased download file")
        try:
            request = urllib.request.Request(expected_url, headers={"Accept-Encoding": "identity", "User-Agent": "Backcast-Toolchain-Tests"})
            with urllib.request.urlopen(request, timeout=30) as response, partial.open("wb") as sink:
                if response.status != 200:
                    raise ValueError("Unexpected fixture HTTP status")
                size = 0
                while block := response.read(64 * 1024):
                    size += len(block)
                    if size > artifact["bytes"]:
                        raise ValueError("Fixture download exceeded its pinned size")
                    sink.write(block)
            if not verify(partial, artifact):
                raise ValueError("Fixture size, prefix, block, or whole-file SHA-256 failed")
            partial.replace(destination)
            print("Downloaded and verified " + name)
        finally:
            partial.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
