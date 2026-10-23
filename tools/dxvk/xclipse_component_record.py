#!/usr/bin/env python3
"""Validate a source-built Xclipse DXVK archive and emit a candidate release pin."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import struct
import tarfile
from urllib.parse import urlparse


ROOT = Path(__file__).resolve().parents[2]
DLL_NAMES = ("d3d8.dll", "d3d9.dll", "d3d10core.dll", "d3d11.dll", "dxgi.dll")
ARCHITECTURES = {"x32": (0x014C, 0x10B), "x64": (0x8664, 0x20B)}


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def json_bytes(value):
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode()


def source_values(root):
    return dict(line.split("=", 1) for line in
                (root / "tools/dxvk/xclipse-source.env").read_text().splitlines()
                if line and not line.startswith("#"))


def recipe_inputs(root):
    paths = [root / "tools/dxvk" / name for name in
             ("build_xclipse.sh", "xclipse_component_record.py", "xclipse-source.env")]
    paths.extend(sorted((root / "tools/dxvk/xclipse-patches").glob("*.patch")))
    paths.append(root / "tools/tests/dxvk_xclipse_toolchain_test.cpp")
    return {str(path.relative_to(root)): sha256(path.read_bytes()) for path in paths}


def read_archive(data):
    entries = {}
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
        for member in archive:
            path = PurePosixPath(member.name)
            if member.isdir():
                continue
            if (not member.isfile() or path.is_absolute() or ".." in path.parts
                    or "\\" in member.name or member.name in entries):
                raise ValueError("unsafe or duplicate component archive entry: " + member.name)
            entries[member.name] = (0o755 if member.mode & 0o111 else 0o644,
                                    archive.extractfile(member).read())
    return entries


def pe_identity(data, name):
    if len(data) < 0x40 or data[:2] != b"MZ":
        raise ValueError("component DLL is not PE: " + name)
    pe, = struct.unpack_from("<I", data, 0x3C)
    if pe < 0x40 or pe + 26 > len(data) or data[pe:pe + 4] != b"PE\0\0":
        raise ValueError("component DLL has no PE header: " + name)
    return struct.unpack_from("<H", data, pe + 4)[0], struct.unpack_from("<H", data, pe + 24)[0]


def record(data, root, url, run_url):
    parsed = urlparse(url)
    if parsed.scheme != "https" or not parsed.netloc:
        raise ValueError("component publication URL must use HTTPS")
    source = source_values(root)
    version = source["DXVK_XCLIPSE_VERSION"]
    prefix = f"dxvk-{version}"
    entries = read_archive(data)
    dlls = {f"{prefix}/{architecture}/{name}": (architecture, machine, magic)
            for architecture, (machine, magic) in ARCHITECTURES.items() for name in DLL_NAMES}
    required_metadata = {
        f"{prefix}/LICENSE",
        f"{prefix}/source-commit",
        f"{prefix}/patches/0001-check-direct3d-bc-formats.patch",
        f"{prefix}/provenance/SHA256SUMS",
        f"{prefix}/provenance/build-manifest.json",
        f"{prefix}/provenance/build-recipe.json",
        f"{prefix}/provenance/compiler.txt",
        f"{prefix}/provenance/downstream.patch",
        f"{prefix}/provenance/pe-inventory.json",
        f"{prefix}/provenance/sourcearchive.tar.zst",
        f"{prefix}/provenance/submodules.txt",
        f"{prefix}/provenance/tool-versions.txt",
    }
    expected = set(dlls) | required_metadata
    if set(entries) != expected:
        missing = sorted(expected - set(entries))
        extra = sorted(set(entries) - expected)
        raise ValueError(f"component member set differs: missing={missing}, extra={extra}")
    for name in required_metadata:
        if not entries[name][1]:
            raise ValueError("empty component provenance file: " + name)
    if entries[f"{prefix}/source-commit"][1].decode().strip() != source["DXVK_XCLIPSE_REVISION"]:
        raise ValueError("component was built from a different DXVK revision")
    recipe = json.loads(entries[f"{prefix}/provenance/build-recipe.json"][1])
    if recipe != recipe_inputs(root):
        raise ValueError("component was built with a different recipe or patch stack")
    checksums = {}
    for line in entries[f"{prefix}/provenance/SHA256SUMS"][1].decode().splitlines():
        digest, separator, name = line.partition("  ")
        if not separator or not re.fullmatch(r"[0-9a-f]{64}", digest) or name in checksums:
            raise ValueError("invalid component checksum inventory")
        checksums[name] = digest
    actual = {name: sha256(content) for name, (_, content) in entries.items()
              if name != f"{prefix}/provenance/SHA256SUMS"}
    if checksums != actual:
        raise ValueError("component checksum inventory differs")
    files = []
    for name, (architecture, machine, magic) in sorted(dlls.items()):
        mode, content = entries[name]
        if mode != 0o755:
            raise ValueError("component DLL is not executable: " + name)
        actual_machine, actual_magic = pe_identity(content, name)
        if (actual_machine, actual_magic) != (machine, magic):
            raise ValueError("component DLL has wrong PE architecture: " + name)
        files.append({"mode": mode, "name": name, "path": name,
                      "sha256": sha256(content), "size": len(content)})
    manifest = json.loads(entries[f"{prefix}/provenance/build-manifest.json"][1])
    if (manifest.get("source_commit") != source["DXVK_XCLIPSE_REVISION"]
            or manifest.get("source_version") != version
            or manifest.get("container_image") != source["DXVK_XCLIPSE_CONTAINER_IMAGE"]
            or not re.fullmatch(r"[0-9a-f]{40}", manifest.get("producer_commit", ""))):
        raise ValueError("component build manifest lacks matching producer provenance")
    inventory = json.loads(entries[f"{prefix}/provenance/pe-inventory.json"][1])
    inventory_hashes = {item.get("file"): item.get("sha256") for item in inventory}
    if inventory_hashes != {name: sha256(entries[name][1]) for name in dlls}:
        raise ValueError("component PE inventory differs")
    return {
        "files": files,
        "format": "tar.gz",
        "id": "dxvk-xclipse",
        "ignored": sorted(required_metadata),
        "provenance": {
            "component_ci_run": run_url,
            "producer_commit": manifest["producer_commit"],
            "upstream_source_commit": source["DXVK_XCLIPSE_REVISION"],
            "upstream_release": f"https://github.com/doitsujin/dxvk/releases/tag/v{version}",
            "note": "Source-built DXVK with exact Direct3D BC format validation; provenance members are excluded from APK assembly.",
        },
        "recipe_inputs": recipe,
        "sha256": sha256(data),
        "size": len(data),
        "url": url,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path, nargs="?")
    parser.add_argument("--url")
    parser.add_argument("--write-recipe", type=Path)
    args = parser.parse_args()
    if args.write_recipe:
        if args.archive or args.url:
            parser.error("--write-recipe cannot be combined with archive or --url")
        args.write_recipe.write_bytes(json_bytes(recipe_inputs(ROOT)))
        return
    if not args.archive or not args.url:
        parser.error("archive and --url are required")
    server = os.environ.get("GITHUB_SERVER_URL", "https://github.com")
    repository = os.environ.get("GITHUB_REPOSITORY")
    run_id = os.environ.get("GITHUB_RUN_ID")
    run_url = f"{server}/{repository}/actions/runs/{run_id}" if repository and run_id else None
    component = record(args.archive.read_bytes(), ROOT, args.url, run_url)
    (args.archive.parent / "dxvk-xclipse-component.json").write_bytes(json_bytes(component))
    args.archive.with_name(args.archive.name + ".sha256").write_text(
        component["sha256"] + "  " + args.archive.name + "\n")
    print("Xclipse DXVK component recorded; publication and release pin update are still required.")


if __name__ == "__main__":
    main()
