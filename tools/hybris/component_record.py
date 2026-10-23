#!/usr/bin/env python3
"""Record a newly built libhybris component; never publish it or change release.json."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
from urllib.parse import urlparse


ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("runtime_fetch", ROOT / "tools/powervr/fetch.py")
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


def recipe_inputs(root):
    recipe = [root / "tools/hybris" / name for name in
              ("build_runtime.sh", "component_record.py", "source.env", "powervr-hal-version.patch")]
    recipe.extend(sorted((root / "tools/hybris/patches").glob("*.patch")))
    return {str(path.relative_to(root)): runtime.sha256(path.read_bytes()) for path in recipe}


def record(data, root, url, commit, run_url):
    if urlparse(url).scheme != "https" or not urlparse(url).netloc:
        raise ValueError("component publication URL must use HTTPS")
    entries = runtime.read_tar(data)
    recipe = recipe_inputs(root)
    if json.loads(runtime.read(entries, "build-recipe.json")) != recipe:
        raise ValueError("component was built with a different recipe or patch stack")
    source = dict(line.split("=", 1) for line in (root / "tools/hybris/source.env").read_text().splitlines()
                  if line and not line.startswith("#"))
    if runtime.read(entries, "source-commit").decode().strip() != source["HYBRIS_REVISION"]:
        raise ValueError("component was built from a different libhybris revision")
    # The producer's ELF hashes precede the packaging RPATH edits. Preserve that
    # manifest, check its source/inventory, and verify packaged bytes separately.
    manifest = json.loads(runtime.read(entries, "build-manifest.json"))
    if (not isinstance(manifest, dict) or manifest.get("source_commit") != source["HYBRIS_REVISION"]
            or not manifest.get("compiler") or manifest["compiler"] == "unknown"
            or not isinstance(manifest.get("build_inputs"), dict) or not manifest["build_inputs"]):
        raise ValueError("component build manifest lacks matching source/compiler provenance")
    inventory = manifest.get("elfs")
    if not isinstance(inventory, list) or not inventory:
        raise ValueError("component build manifest lacks its ELF inventory")
    original = set()
    for item in inventory:
        if (not isinstance(item, dict) or not isinstance(item.get("path"), str)
                or not re.fullmatch(r"[0-9a-f]{64}", item.get("sha256", ""))):
            raise ValueError("invalid original ELF inventory entry")
        if item.get("tree") == "hybris" and not item["path"].startswith("bin/"):
            if item["path"] in original:
                raise ValueError("duplicate original ELF inventory entry")
            original.add(item["path"])
    packaged = {name.removeprefix("lib/") for name in entries if name.startswith("lib/")
                and runtime.read(entries, name).startswith(b"\x7fELF")}
    if not packaged or packaged != original:
        raise ValueError("packaged ELF inventory differs from the original build")
    checksums = {}
    for line in runtime.read(entries, "SHA256SUMS").decode().splitlines():
        digest, separator, name = line.partition("  ")
        if not separator or not re.fullmatch(r"[0-9a-f]{64}", digest) or name in checksums:
            raise ValueError("invalid packaged library checksum inventory")
        checksums[name] = digest
    actual = {name: runtime.sha256(content) for name, (kind, _, content) in entries.items()
              if name.startswith("lib/") and kind == "file"}
    if checksums != actual:
        raise ValueError("packaged library checksum mismatch")
    for name in ("licenses/powervr.patch", "licenses/libhybris-source.tar.zst", "licenses/wayland-android.xml"):
        if not runtime.read(entries, name):
            raise ValueError("empty component source/license file: " + name)
    for name in runtime.REQUIRED_LIBRARIES:
        if not name.startswith("lib/"):
            continue
        content = runtime.read(entries, name)
        if content[:6] != b"\x7fELF\x02\x01" or content[18:20] != b"\xb7\x00":
            raise ValueError("component library is not ELF64 AArch64: " + name)
    runtime.check_placed_manifest(entries)
    return {
        "url": url,
        "sha256": runtime.sha256(data),
        "size": len(data),
        "tree_sha256": runtime.tree_digest(entries),
        "recipe_inputs": recipe,
        "provenance": {
            "producer_commit": commit,
            "component_ci_run": run_url,
            "upstream_source_commit": source["HYBRIS_REVISION"],
            "note": "Candidate pin: publish this exact archive and verify its URL before updating release.json.",
        },
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path, nargs="?")
    parser.add_argument("--url", help="intended HTTPS release-asset URL")
    parser.add_argument("--write-recipe", type=Path, help="embed current recipe inputs while building the component")
    args = parser.parse_args()
    if args.write_recipe:
        if args.archive or args.url:
            parser.error("--write-recipe cannot be combined with an archive or --url")
        args.write_recipe.write_bytes(runtime.json_bytes(recipe_inputs(ROOT)))
        return
    if not args.archive or not args.url:
        parser.error("archive and --url are required when recording a component")
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    server = os.environ.get("GITHUB_SERVER_URL", "https://github.com")
    repository, run_id = os.environ.get("GITHUB_REPOSITORY"), os.environ.get("GITHUB_RUN_ID")
    run_url = f"{server}/{repository}/actions/runs/{run_id}" if repository and run_id else None
    component = record(args.archive.read_bytes(), ROOT, args.url, commit, run_url)
    (args.archive.parent / "hybris-component.json").write_bytes(runtime.json_bytes(component))
    args.archive.with_name(args.archive.name + ".sha256").write_text(
        component["sha256"] + "  " + args.archive.name + "\n")
    print("Component recorded; publication and release pin update are still required.")


if __name__ == "__main__":
    main()
