#!/usr/bin/env python3
"""Fail closed on unreviewed, checked-in binary components.

This is a tracked-binary inventory and checksum gate, NOT a source-build,
reproducibility, or licensing attestation.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
MANIFEST = "tools/provenance/prebuilt.json"
BINARY_SUFFIXES = {
    ".so", ".a", ".o", ".dll", ".exe", ".jar", ".aar", ".apk",
    ".tzst", ".zst", ".task", ".wasm", ".bin", ".zip", ".7z",
}
MAGICS = (
    b"\x7fELF", b"MZ", b"\x00asm",  # ELF, PE, WebAssembly
    b"\xcf\xfa\xed\xfe", b"\xfe\xed\xfa\xcf",  # Mach-O
    b"\xca\xfe\xba\xbe", b"\x28\xb5\x2f\xfd",  # universal, zstd
    b"PK\x03\x04", b"\x1f\x8b", b"\xfd7zXZ", b"7z\xbc\xaf\x27\x1c",  # compressed archives
)
SHA256_RE = re.compile(r"^[a-f0-9]{64}$")
VALID_STATES = {"prebuilt-exception", "binary-data", "build-tool"}


def tracked_paths(root: Path) -> list[str]:
    data = subprocess.check_output(
        ["git", "ls-files", "--cached", "--stage", "-z"], cwd=root
    )
    paths = []
    for item in data.split(b"\0"):
        if not item:
            continue
        meta, path = item.split(b"\t", 1)
        # Gitlinks contain an object ID, not a file blob. They are checked
        # separately by the pinned submodule source/build process.
        if meta.split()[0] == b"160000":
            continue
        paths.append((os.fsdecode(path), meta.split()[0]))
    return sorted(paths)


def is_binary_payload(relpath: str, prefix: bytes) -> bool:
    name = Path(relpath).name.lower()
    return (
        name.endswith(tuple(BINARY_SUFFIXES))
        or ".so." in name
        or any(prefix.startswith(magic) for magic in MAGICS)
    )


def hash_content(rel: str, handle) -> str | None:
    prefix = handle.read(8)
    if not is_binary_payload(rel, prefix):
        return None
    digest = hashlib.sha256()
    digest.update(prefix)
    for chunk in iter(lambda: handle.read(1024 * 1024), b""):
        digest.update(chunk)
    return digest.hexdigest()


def discover(root: Path) -> dict[str, str]:
    """Hash tracked binary payloads, including paths omitted by sparse checkout.

    Git's index still holds blob IDs for sparse entries, so read those entries
    from Git rather than silently skipping them. A missing blob fails the gate.
    """
    result: dict[str, str] = {}
    for rel, mode in tracked_paths(root):
        file = root / rel
        # A Git symlink blob stores the link target string, not target bytes.
        # Always hash that blob to match sparse and full checkouts.
        if mode != b"120000" and file.is_file():
            with file.open("rb") as handle:
                digest = hash_content(rel, handle)
        else:
            # The source CI checkout deliberately omits docs/releases/media.
            # Read the index version so an ELF added there cannot evade the gate.
            with subprocess.Popen(
                ["git", "show", f":{rel}"], cwd=root,
                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            ) as proc:
                assert proc.stdout is not None
                digest = hash_content(rel, proc.stdout)
                # Drain even non-binary files before checking the exit status.
                for _ in iter(lambda: proc.stdout.read(1024 * 1024), b""):
                    pass
                error = proc.stderr.read() if proc.stderr else b""
                if proc.wait() != 0:
                    raise RuntimeError(
                        f"cannot read tracked Git blob for {rel}: {error.decode(errors='replace')}"
                    )
        if digest is not None:
            result[rel] = digest
    return result


def validate(root: Path, manifest: Path) -> list[str]:
    try:
        data = json.loads(manifest.read_text(encoding="utf-8"))
        if not isinstance(data, dict) or data.get("schema") != 1:
            return ["manifest must be a schema: 1 object"]
        records = data["artifacts"]
        if not isinstance(records, list):
            return ["artifacts must be a list"]
    except (OSError, ValueError, KeyError) as exc:
        return [f"invalid manifest: {exc}"]

    errors: list[str] = []
    expected: dict[str, str] = {}
    for index, item in enumerate(records):
        if not isinstance(item, dict):
            errors.append(f"artifacts[{index}] must be an object")
            continue
        rel = item.get("path")
        if not isinstance(rel, str) or not rel or rel.startswith("/") or ".." in Path(rel).parts:
            errors.append(f"artifacts[{index}]: invalid repository-relative path")
            continue
        if rel in expected:
            errors.append(f"duplicate manifest entry: {rel}")
        digest = item.get("sha256")
        if not isinstance(digest, str) or not SHA256_RE.fullmatch(digest):
            errors.append(f"{rel}: invalid sha256")
        else:
            expected[rel] = digest
        if item.get("status") not in VALID_STATES:
            errors.append(f"{rel}: status must be one of {sorted(VALID_STATES)}")
        if not isinstance(item.get("origin"), str) or not item["origin"].strip():
            errors.append(f"{rel}: origin must describe the source project")
        if not isinstance(item.get("source_revision"), str) or not item["source_revision"].strip():
            errors.append(f"{rel}: source_revision required (use 'unverified' if unknown)")
        if not isinstance(item.get("note"), str) or len(item["note"].strip()) < 12:
            errors.append(f"{rel}: note must explain this exception")

    found = discover(root)
    for rel in sorted(set(found) - set(expected)):
        errors.append(f"UNREVIEWED binary: {rel} (sha256 {found[rel]})")
    for rel in sorted(set(expected) - set(found)):
        errors.append(f"manifest lists missing/untracked binary: {rel}")
    for rel in sorted(set(found) & set(expected)):
        if found[rel] != expected[rel]:
            errors.append(f"CHANGED binary: {rel} (expected {expected[rel]}, got {found[rel]})")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=REPO)
    parser.add_argument("--manifest", type=Path, default=None)
    parser.add_argument("--list", action="store_true", help="print discovered paths and hashes")
    args = parser.parse_args()
    root = args.root.resolve()
    if args.list:
        for rel, digest in discover(root).items():
            print(f"{digest}  {rel}")
        return 0
    manifest = args.manifest or root / MANIFEST
    errors = validate(root, manifest)
    for error in errors:
        print(f"::error::{error}", file=sys.stderr)
    if errors:
        print(f"Prebuilt inventory failed: {len(errors)} problem(s)", file=sys.stderr)
        return 1
    print(f"Prebuilt inventory verified: {len(discover(root))} tracked binary payloads")
    return 0


if __name__ == "__main__":
    sys.exit(main())
