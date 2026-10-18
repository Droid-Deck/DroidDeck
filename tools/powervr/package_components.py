#!/usr/bin/env python3
"""Package pinned bridge, WSI, layers, and vendor DXVK into downloadable components."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import posixpath
import re
import subprocess
import tarfile

import fetch as runtime


ROOT = Path(__file__).resolve().parents[2]
VARIANTS = {
    "powervr": ("wsi", "dxvk"),
    "xclipse": ("wsi", "dxvk-xclipse"),
    "mali": ("wsi", "dxvk-mali"),
}
DXVK_DLLS = ("d3d8.dll", "d3d9.dll", "d3d10core.dll", "d3d11.dll", "dxgi.dll")
COMMON_REQUIRED = runtime.REQUIRED_LIBRARIES + (
    runtime.PLACED_MANIFEST,
    "run-proton.py",
    "wsi/VkLayer_window_system_integration.json",
)


def json_bytes(value):
    return (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode()


def normalized_inventory(entries):
    rows = []
    for name, (kind, mode, data) in sorted(entries.items()):
        if kind != "file":
            raise ValueError("published components may contain regular files only: " + name)
        rows.append({
            "path": name,
            "type": "file",
            "size": len(data),
            "sha256": runtime.sha256(data),
            "mode": 0o755 if mode & 0o111 else 0o644,
        })
    return rows


def tree_digest(rows):
    normalized = "".join(
        f"{row['path']}\0file\0{row['size']}\0{row['sha256']}\0{row['mode']}\n"
        for row in sorted(rows, key=lambda item: item["path"])
    ).encode()
    return hashlib.sha256(normalized).hexdigest()


def regularize(entries):
    result = {}
    for name, (kind, mode, data) in entries.items():
        if kind == "file":
            result[name] = (kind, 0o755 if mode & 0o111 else 0o644, data)
            continue
        target = runtime.safe_name(posixpath.normpath(posixpath.join(posixpath.dirname(name), data)))
        seen = {name}
        target_kind, target_mode, _ = entries[target]
        while target_kind == "symlink":
            if target in seen:
                raise ValueError("runtime symlink cycle: " + name)
            seen.add(target)
            target = runtime.safe_name(posixpath.normpath(posixpath.join(posixpath.dirname(target), entries[target][2])))
            target_kind, target_mode, _ = entries[target]
        result[name] = ("file", 0o755 if target_mode & 0o111 else 0o644, runtime.read(entries, name))
    runtime.check_tree(result)
    return result


def selected_entries(root, cache, variant):
    release = runtime.load_release(root)
    entries = runtime.read_tar(runtime.fetch(release["hybris"], cache))
    if runtime.tree_digest(entries) != release["hybris"]["tree_sha256"]:
        raise ValueError("bridge tree differs from its release pin")
    wanted = set(VARIANTS[variant])
    found = set()
    for component in release["archives"]:
        component_id = component.get("id")
        if component_id not in wanted:
            continue
        additions = runtime.read_component(runtime.fetch(component, cache), component)
        overlap = set(entries) & set(additions)
        if overlap:
            raise ValueError("component overwrites bridge files: " + ", ".join(sorted(overlap)))
        entries.update(additions)
        found.add(component_id)
    if found != wanted:
        raise ValueError("release pins do not provide " + ", ".join(sorted(wanted - found)))
    for source, target in runtime.BUNDLED_LICENSES.items():
        entries[target] = ("file", 0o644, (root / source).read_bytes())
    entries["run-proton.py"] = ("file", 0o755, (root / "tools/hybris/run-proton.py").read_bytes())
    entries["assembly-inputs.json"] = ("file", 0o644, runtime.json_bytes(runtime.assembly_inputs(root)))
    entries = regularize(entries)
    validate_variant_payload(entries, variant)
    required = set(COMMON_REQUIRED)
    architectures = {
        "powervr": ("dxvk", ("x32", "x64")),
        "xclipse": ("dxvk-2.6.2", ("x32", "x64")),
        "mali": ("dxvk-mali", ("i386-windows", "x86_64-windows", "aarch64-windows")),
    }
    dxvk_root, archs = architectures[variant]
    required.update(f"{dxvk_root}/{arch}/{name}" for arch in archs for name in DXVK_DLLS)
    missing = sorted(required - set(entries))
    if missing:
        raise ValueError("component is missing required files: " + ", ".join(missing))
    for name in runtime.REQUIRED_LIBRARIES:
        content = entries[name][2]
        if content[:6] != b"\x7fELF\x02\x01" or content[18:20] != b"\xb7\x00":
            raise ValueError("runtime library is not ELF64 AArch64: " + name)
    runtime.check_placed_manifest(entries)
    return entries


def validate_variant_payload(entries, variant):
    expected_root = {"powervr": "dxvk", "xclipse": "dxvk-2.6.2", "mali": "dxvk-mali"}[variant]
    payload_roots = {name.split("/", 1)[0] for name in entries if re.fullmatch(r"dxvk[^/]*/.*", name)}
    if payload_roots != {expected_root}:
        raise ValueError(
            f"{variant} component has wrong DXVK payload roots: " + ", ".join(sorted(payload_roots))
        )
    evidence = {
        "powervr": {
            "licenses/dxvk-LICENSE", "provenance/dxvk-source-commit", "provenance/dxvk-SHA256SUMS",
        },
        "xclipse": {
            "licenses/dxvk-xclipse/LICENSE", "provenance/dxvk-xclipse/sourcearchive.tar.zst",
            "provenance/dxvk-xclipse/build-manifest.json", "provenance/dxvk-xclipse/source-commit",
        },
        "mali": {
            "licenses/dxvk-mali/LICENSE", "provenance/dxvk-mali/source-commit",
            "provenance/dxvk-mali/build-version", "provenance/dxvk-mali/SHA256SUMS",
        },
    }[variant]
    missing = evidence - set(entries)
    if missing:
        raise ValueError(f"{variant} component lacks DXVK license/provenance: " + ", ".join(sorted(missing)))


def pack(component, entries):
    stream = io.BytesIO()
    directories = {str(parent) for name in entries for parent in PurePosixPath(name).parents if str(parent) != "."}
    with tarfile.open(fileobj=stream, mode="w", format=tarfile.USTAR_FORMAT) as archive:
        manifest = tarfile.TarInfo("component.json")
        manifest.mode = 0o644
        manifest.size = len(component)
        manifest.mtime = manifest.uid = manifest.gid = 0
        archive.addfile(manifest, io.BytesIO(component))
        for name in sorted(directories):
            info = tarfile.TarInfo(name)
            info.type, info.mode = tarfile.DIRTYPE, 0o755
            info.mtime = info.uid = info.gid = 0
            archive.addfile(info)
        for name, (_, mode, data) in sorted(entries.items()):
            info = tarfile.TarInfo(name)
            info.mode, info.size = mode, len(data)
            info.mtime = info.uid = info.gid = 0
            archive.addfile(info, io.BytesIO(data))
    return subprocess.run(
        ["zstd", "-q", "-10", "-T1", "-c"], input=stream.getvalue(), capture_output=True, check=True,
    ).stdout


def build_variant(args, variant):
    entries = selected_entries(ROOT, args.cache_dir, variant)
    inventory = normalized_inventory(entries)
    tree = tree_digest(inventory)
    provenance = {"repository": args.repository.lower(), "commit": args.commit.lower(), "buildUrl": args.build_url}
    requires = {"componentApi": 1, "abi": "arm64-v8a", "minSdk": 26}
    component = {
        "schema": 1,
        "component": "system-vulkan",
        "variant": variant,
        "version": args.version,
        "requires": requires,
        "treeSha256": tree,
        "provenance": provenance,
        "files": inventory,
        "licenses": sorted(name for name in entries if name.startswith("licenses/")),
    }
    archive = pack(json_bytes(component), entries)
    archive_name = f"system-vulkan-{variant}.tar.zst"
    archive_path = args.output / archive_name
    archive_path.write_bytes(archive)
    sidecar = {
        "schema": 1,
        "component": "system-vulkan",
        "variant": variant,
        "version": args.version,
        "requires": requires,
        "artifact": {
            "name": archive_name,
            "size": len(archive),
            "sha256": runtime.sha256(archive),
            "treeSha256": tree,
        },
        "provenance": provenance,
    }
    sidecar_path = args.output / f"droiddeck-component-system-vulkan-{variant}.json"
    sidecar_path.write_bytes(json_bytes(sidecar))
    return sidecar_path, runtime.sha256(sidecar_path.read_bytes())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--repository-id", type=int, required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--build-url", required=True)
    parser.add_argument("--release-tag", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--cache-dir", type=Path, default=Path(os.environ.get(
        "DROIDDECK_BUILD_CACHE", str(Path.home() / ".cache/droiddeck-build"))))
    args = parser.parse_args()
    if args.repository_id <= 0 or not args.repository.count("/") == 1:
        parser.error("repository identity is invalid")
    if not (len(args.commit) in (40, 64) and all(c in "0123456789abcdefABCDEF" for c in args.commit)):
        parser.error("commit must be a full Git object ID")
    if not args.build_url.startswith("https://github.com/"):
        parser.error("build URL must be a GitHub HTTPS URL")
    args.output.mkdir(parents=True, exist_ok=True)
    pins = {
        "schema": 1,
        "repository": args.repository.lower(),
        "repositoryId": args.repository_id,
        "profiles": {},
    }
    for variant in VARIANTS:
        _, digest = build_variant(args, variant)
        pins["profiles"][variant] = {"tag": args.release_tag, "manifestSha256": digest}
    (args.output / "component-bootstrap.json").write_bytes(json_bytes(pins))
    print(json.dumps(pins, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
