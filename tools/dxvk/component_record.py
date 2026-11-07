#!/usr/bin/env python3
"""Record a source-built baseline DXVK archive; never publish it or alter release pins."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import struct
import subprocess
from urllib.parse import urlparse
from zipfile import ZIP_DEFLATED, ZIP_STORED, ZipFile


ROOT = Path(__file__).resolve().parents[2]
DLLS = ("d3d8.dll", "d3d9.dll", "d3d10core.dll", "d3d11.dll", "dxgi.dll")
ARCHES = ("x32", "x64")
METADATA = ("LICENSE", "source-commit", "submodules.txt", "downstream.patch", "source.tar.zst", "SHA256SUMS", "build-recipe.json")
MAX_ARCHIVE = 64 * 1024 * 1024
MAX_MEMBER = 128 * 1024 * 1024
MAX_UNPACKED = 256 * 1024 * 1024
MAX_MEMBERS = 32


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def json_bytes(value):
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode()


def source_revision(root):
    for line in (root / "tools/dxvk/source.env").read_text().splitlines():
        if line.startswith("DXVK_REVISION="):
            return line.split("=", 1)[1].strip()
    raise ValueError("DXVK_REVISION is missing")


def recipe_inputs(root):
    paths = [root / "tools/dxvk" / name for name in
             ("build.sh", "component_record.py", "check_present_sync.py", "source.env")]
    paths.extend(sorted((root / "tools/dxvk/patches").glob("*.patch")))
    paths.append(root / "tools/tests/test_dxvk_build.py")
    return {str(path.relative_to(root)): sha256(path.read_bytes()) for path in paths}


def read_archive(data):
    entries = {}
    path = Path(data)
    if not path.is_file() or path.stat().st_size > MAX_ARCHIVE:
        raise ValueError("DXVK archive exceeds size limit")
    with ZipFile(path) as archive:
        total = 0
        for member in archive.infolist():
            name = member.filename
            path = PurePosixPath(name)
            if member.is_dir():
                raise ValueError("DXVK archive directory entry is not allowed: " + name)
            mode = member.external_attr >> 16
            kind = mode & 0o170000
            total += member.file_size
            if (len(entries) >= MAX_MEMBERS or member.flag_bits & 1
                    or member.compress_type not in (ZIP_STORED, ZIP_DEFLATED)
                    or member.file_size > MAX_MEMBER or total > MAX_UNPACKED
                    or kind not in (0, 0o100000)
                    or path.is_absolute() or ".." in path.parts or "\\" in name or name in entries):
                raise ValueError("unsafe or duplicate DXVK archive entry: " + name)
            entries[name] = archive.read(member)
    return entries


def checksum_inventory(data):
    inventory = {}
    for line in data.decode().splitlines():
        digest, separator, name = line.partition("  ")
        if not separator or not re.fullmatch(r"[0-9a-f]{64}", digest) or name in inventory:
            raise ValueError("invalid DXVK checksum inventory")
        inventory[name] = digest
    return inventory


def pe_identity(data, name):
    if len(data) < 0x5A or data[:2] != b"MZ":
        raise ValueError("DXVK DLL is not PE: " + name)
    offset, = struct.unpack_from("<I", data, 0x3C)
    if offset < 0x40 or offset + 26 > len(data) or data[offset:offset + 4] != b"PE\0\0":
        raise ValueError("DXVK DLL has no PE header: " + name)
    return struct.unpack_from("<H", data, offset + 4)[0], struct.unpack_from("<H", data, offset + 24)[0]


def publication_url(url, repository, tag, archive_name):
    parsed = urlparse(url)
    expected = f"/{repository}/releases/download/{tag}/{archive_name}"
    if (parsed.scheme != "https" or parsed.hostname != "github.com" or parsed.port is not None
            or parsed.username is not None or parsed.password is not None
            or parsed.query or parsed.fragment or parsed.path != expected):
        raise ValueError("component publication URL must be the exact GitHub release asset")


def checkout_commit(root):
    return subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=root, text=True,
    ).strip().lower()


def record(archive, root, url, repository, tag, producer_commit, producer_run):
    if not re.fullmatch(r"[A-Za-z0-9-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("component publication repository is invalid")
    if not re.fullmatch(r"[A-Za-z0-9._-]+", tag):
        raise ValueError("component publication tag is invalid")
    if not re.fullmatch(r"[0-9a-f]{40}", producer_commit):
        raise ValueError("component producer commit is invalid")
    if producer_commit != checkout_commit(root):
        raise ValueError("component producer commit differs from the clean checkout")
    run_prefix = "https://github.com/" + repository + "/actions/runs/"
    if not re.fullmatch(re.escape(run_prefix) + r"[1-9][0-9]*", producer_run):
        raise ValueError("component producer run is invalid")
    publication_url(url, repository, tag, archive.name)
    entries = read_archive(archive)
    dll_names = {f"{arch}/{name}" for arch in ARCHES for name in DLLS}
    expected = dll_names | set(METADATA)
    if set(entries) != expected:
        raise ValueError("DXVK archive members differ: missing=%s, extra=%s" %
                         (sorted(expected - set(entries)), sorted(set(entries) - expected)))
    for name in METADATA:
        if not entries[name]:
            raise ValueError("empty DXVK provenance file: " + name)
    if entries["source-commit"].decode().strip() != source_revision(root):
        raise ValueError("DXVK archive was built from a different source revision")
    recipe = json.loads(entries["build-recipe.json"])
    if recipe != recipe_inputs(root):
        raise ValueError("DXVK archive was built with a different recipe or patch stack")
    actual = {name: sha256(entries[name]) for name in dll_names}
    if checksum_inventory(entries["SHA256SUMS"]) != actual:
        raise ValueError("DXVK checksum inventory differs")
    expected_architecture = {"x32": (0x014C, 0x10B), "x64": (0x8664, 0x20B)}
    for name in dll_names:
        actual_identity = pe_identity(entries[name], name)
        expected_identity = expected_architecture[name.split("/", 1)[0]]
        if actual_identity != expected_identity:
            raise ValueError("DXVK DLL has wrong PE architecture: " + name)
    files = []
    destinations = {
        "LICENSE": "licenses/dxvk-LICENSE",
        "source-commit": "provenance/dxvk-source-commit",
        "submodules.txt": "provenance/dxvk-submodules.txt",
        "downstream.patch": "licenses/dxvk-downstream.patch",
        "source.tar.zst": "licenses/dxvk-source.tar.zst",
        "SHA256SUMS": "provenance/dxvk-SHA256SUMS",
        "build-recipe.json": "provenance/dxvk-build-recipe.json",
    }
    for name in sorted(expected):
        files.append({"name": name, "path": destinations.get(name, "dxvk/" + name),
                      "mode": 0o755 if name in dll_names else 0o644,
                      "size": len(entries[name]), "sha256": sha256(entries[name])})
    data = archive.read_bytes()
    return {"id": "dxvk", "format": "zip", "url": url, "size": len(data),
            "sha256": sha256(data), "files": files, "ignored": [], "recipe_inputs": recipe,
            "provenance": {"producer_commit": producer_commit, "component_ci_run": producer_run,
                           "upstream_source_commit": source_revision(root),
                           "note": "Candidate pin: publish this exact archive and verify its URL before updating release.json."}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path, nargs="?")
    parser.add_argument("--url")
    parser.add_argument("--repository")
    parser.add_argument("--tag")
    parser.add_argument("--producer-commit")
    parser.add_argument("--producer-run")
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--write-recipe", type=Path)
    args = parser.parse_args()
    if args.write_recipe:
        if args.archive or args.url:
            parser.error("--write-recipe cannot be combined with archive or --url")
        args.write_recipe.write_bytes(json_bytes(recipe_inputs(args.root)))
        return
    if not all((args.archive, args.url, args.repository, args.tag, args.producer_commit, args.producer_run)):
        parser.error("archive, URL, repository, tag, producer commit, and producer run are required")
    component = record(args.archive, args.root, args.url, args.repository, args.tag,
                       args.producer_commit, args.producer_run)
    output = args.archive.parent / "dxvk-component.json"
    output.write_bytes(json_bytes(component))
    args.archive.with_name(args.archive.name + ".sha256").write_text(
        component["sha256"] + "  " + args.archive.name + "\n")
    print("Baseline DXVK component recorded; publication and release pin update are still required.")


if __name__ == "__main__":
    main()
