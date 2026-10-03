#!/usr/bin/env python3
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import subprocess
import sys
import tempfile
import time
from pathlib import Path

from make_pack import (
    BINARIES,
    EPOCH,
    FLAVORS,
    HERE,
    NTDLL,
    PACK_ID,
    REVOKED,
    SHA256,
    WINESERVER,
    PackError,
    compress,
    dump_json,
    read_revoked,
    sha256_bytes,
    sha256_file,
    tar_bytes,
    write_atomic,
)

PUBKEY = HERE / "index-key.pub"
SCHEMA = 1
INDEX_KEYS = ("id", "flavor", "rev", "version", "version_line", "stock", "exports", "source_match", "files", "protocol", "source", "patch")


def check_entry(entry, origin: str) -> dict:
    def fail(why: str):
        raise PackError(f"{origin}: {why}")

    if not isinstance(entry, dict):
        fail("is not an object")
    ident = entry.get("id")
    if not isinstance(ident, str) or not PACK_ID.fullmatch(ident):
        fail(f"{ident!r} is not a pack id")
    if entry.get("flavor") not in FLAVORS:
        fail(f"unknown flavor {entry.get('flavor')!r}")
    if type(entry.get("rev")) is not int or entry["rev"] < 1:
        fail("rev is not a positive number")
    version, line = entry.get("version"), entry.get("version_line")
    fields = line.split() if isinstance(line, str) else []
    if not isinstance(version, str) or len(fields) < 2 or fields[1] != version or not EPOCH.fullmatch(fields[0]):
        fail("version and version_line disagree")
    for field in ("stock", "files"):
        value = entry.get(field)
        if not isinstance(value, dict) or set(value) != set(BINARIES) or not all(isinstance(v, str) and SHA256.fullmatch(v) for v in value.values()):
            fail(f"{field} must give the sha256 of {' and '.join(BINARIES)}")
    if not isinstance(entry.get("exports"), str) or not SHA256.fullmatch(entry["exports"]):
        fail("exports is not a sha256")
    if type(entry.get("source_match")) is not bool:
        fail("source_match is not a boolean")
    asset = entry.get("asset")
    if not isinstance(asset, dict) or not isinstance(asset.get("sha256"), str) or not SHA256.fullmatch(asset["sha256"]) \
            or type(asset.get("size")) is not int or asset["size"] <= 0:
        fail("asset needs its sha256 and size")
    clean = {key: entry[key] for key in INDEX_KEYS if key in entry}
    clean["asset"] = {"sha256": asset["sha256"], "size": asset["size"]}
    return clean


def load_entries(directory: Path) -> list[dict]:
    if not directory.is_dir():
        raise PackError(f"{directory} is not a directory")
    entries = []
    for path in sorted(directory.glob("*.json")):
        try:
            data = json.loads(path.read_text())
        except ValueError as e:
            raise PackError(f"{path}: {e}") from e
        entry = check_entry(data, str(path))
        if path.stem != entry["id"]:
            raise PackError(f"{path} holds the entry of {entry['id']}")
        entries.append(entry)
    return entries


def load_previous(path: Path) -> tuple[int, list[dict], list]:
    try:
        data = json.loads(path.read_text())
    except (OSError, ValueError) as e:
        raise PackError(f"cannot read the previous index {path}: {e}") from e
    if not isinstance(data, dict) or data.get("schema") != SCHEMA or type(data.get("generated")) is not int \
            or not isinstance(data.get("packs"), list):
        raise PackError(f"{path} is not a schema {SCHEMA} index")
    packs = []
    for number, pack in enumerate(data["packs"]):
        entry = check_entry(pack, f"{path} pack {number}")
        entry["revoked"] = pack.get("revoked") is True
        packs.append(entry)
    return data["generated"], packs, data["packs"]


def epoch(entry: dict) -> int:
    return int(entry["version_line"].split()[0])


def rank(entry: dict) -> tuple:
    return entry["rev"], epoch(entry), entry["id"]


def choose(entries: list[dict], previous: list[dict], revoked: set[str]) -> list[dict]:
    known = {entry["id"]: entry for entry in entries}
    for entry in previous:
        known.setdefault(entry["id"], entry)
    active = {}
    withdrawn = []
    for entry in known.values():
        if entry["id"] in revoked:
            withdrawn.append(dict(entry, revoked=True))
            continue
        key = (entry["stock"][NTDLL], entry["stock"][WINESERVER], entry["version"])
        if key not in active or rank(entry) > rank(active[key]):
            active[key] = dict(entry, revoked=False)
    return sorted([*active.values(), *withdrawn], key=lambda entry: entry["id"])


def build_index(packs: list[dict], base_url: str, generated: int) -> dict:
    listed = []
    for pack in packs:
        entry = dict(pack)
        entry["asset"] = {
            "url": f"{base_url}/droiddeck-esync-{pack['flavor']}/{pack['id']}.tzst",
            "sha256": pack["asset"]["sha256"],
            "size": pack["asset"]["size"],
        }
        listed.append(entry)
    return {"schema": SCHEMA, "generated": generated, "packs": listed}


def family(entry: dict) -> tuple[str, str, str]:
    token = entry["version"]
    major = re.search(r"[0-9]+", token)
    return entry["flavor"], re.match(r"[A-Za-z]*", token).group(0).lower(), major.group(0) if major else ""


def bundle_choice(packs: list[dict], available: set[str] | None = None) -> list[dict]:
    newest = {}
    for pack in packs:
        if pack["revoked"] or (available is not None and pack["id"] not in available):
            continue
        key = family(pack)
        if key not in newest or (epoch(pack), pack["rev"], pack["id"]) > (epoch(newest[key]), newest[key]["rev"], newest[key]["id"]):
            newest[key] = pack
    return sorted(newest.values(), key=lambda pack: pack["id"])


def openssl(*args: str) -> subprocess.CompletedProcess:
    try:
        return subprocess.run(["openssl", *args], capture_output=True, text=True, check=False)
    except FileNotFoundError as e:
        raise PackError("the openssl command is not installed") from e


def verified(index: Path, signature: Path, pubkey: Path) -> bool:
    result = openssl("dgst", "-sha256", "-verify", str(pubkey), "-signature", str(signature), str(index))
    return result.returncode == 0 and "Verified OK" in result.stdout


def trusted_previous(path: Path, signature: Path | None, pubkey: Path) -> tuple[int, list[dict], list | None]:
    if signature is not None and not (path.is_file() and signature.is_file() and verified(path, signature, pubkey)):
        print(f"make_index: the previous index {path} does not verify with {signature} and {pubkey}; it is ignored", file=sys.stderr)
        return 0, [], None
    return load_previous(path)


def sign(index: Path, signature: Path, key: Path | None, pubkey: Path) -> None:
    with tempfile.TemporaryDirectory() as tmp:
        if key is None:
            pem = os.environ.get("SYNC_INDEX_KEY", "")
            if not pem.strip():
                raise PackError("no signing key: pass --key or set SYNC_INDEX_KEY")
            key = Path(tmp) / "key.pem"
            with os.fdopen(os.open(key, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
                stream.write(pem if pem.endswith("\n") else pem + "\n")
        result = openssl("dgst", "-sha256", "-sign", str(key), "-out", str(signature), str(index))
        if result.returncode != 0:
            raise PackError(f"openssl could not sign the index: {result.stderr.strip()}")
    if not verified(index, signature, pubkey):
        raise PackError(f"the signature does not verify with {pubkey}: the signing key is not the one the app trusts")


def bundle_name(generated: int) -> str:
    return f"bundle-{dt.datetime.fromtimestamp(generated, dt.timezone.utc):%Y%m%d-%H%M%S}.tzst"


def bundle(out: Path, packs: list[dict], index: bytes, signature: bytes, packs_dir: Path, generated: int) -> tuple[str, str]:
    if not packs_dir.is_dir():
        raise PackError(f"{packs_dir} is not a directory")
    available = {path.name[:-len(".tzst")] for path in packs_dir.glob("*.tzst") if path.is_file()}
    members = {"index.json": (index, 0o644), "index.json.sig": (signature, 0o644)}
    for pack in bundle_choice(packs, available):
        path = packs_dir / f"{pack['id']}.tzst"
        if path.stat().st_size != pack["asset"]["size"] or sha256_file(path) != pack["asset"]["sha256"]:
            raise PackError(f"{path} is not the asset the index lists for {pack['id']}")
        members[f"packs/{pack['id']}.tzst"] = (path.read_bytes(), 0o644)
    name = bundle_name(generated)
    archive = compress(tar_bytes(members))
    write_atomic(out / name, archive)
    return name, sha256_bytes(archive)


def report(values: dict[str, str]) -> None:
    path = os.environ.get("GITHUB_OUTPUT")
    if path:
        with open(path, "a") as stream:
            for key, value in values.items():
                stream.write(f"{key}={value}\n")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Make, sign and bundle the droiddeck-esync pack index from the published pack entries.")
    parser.add_argument("--entries", required=True)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--key", default="")
    parser.add_argument("--pubkey", default=str(PUBKEY))
    parser.add_argument("--out", default="")
    parser.add_argument("--bundle-packs-dir", default="")
    parser.add_argument("--revoked", default=str(REVOKED))
    parser.add_argument("--previous", default="")
    parser.add_argument("--previous-sig", default="")
    parser.add_argument("--list-bundle", action="store_true")
    args = parser.parse_args(argv)
    try:
        base_url = args.base_url.rstrip("/")
        if not base_url.startswith("https://"):
            raise PackError("--base-url must be an https URL")
        revoked = read_revoked(Path(args.revoked)) if args.revoked else set()
        if args.previous_sig and not args.previous:
            raise PackError("--previous-sig needs --previous")
        previous_signature = Path(args.previous_sig) if args.previous_sig else None
        previous_generated, previous, previous_raw = trusted_previous(Path(args.previous), previous_signature, Path(args.pubkey)) \
            if args.previous else (0, [], None)
        packs = choose(load_entries(Path(args.entries)), previous, revoked)
        if args.list_bundle:
            for pack in bundle_choice(packs):
                print(f"{pack['flavor']}\t{pack['id']}")
            return 0
        if not args.out:
            raise PackError("--out is required")
        out = Path(args.out)
        out.mkdir(parents=True, exist_ok=True)
        generated = max(int(time.time()), previous_generated + 1)
        index = build_index(packs, base_url, generated)
        data = dump_json(index)
        changed = bool(packs) if previous_raw is None else previous_raw != index["packs"]
        temp_index = out / f".index.json.tmp-{os.getpid()}"
        temp_signature = out / f".index.json.sig.tmp-{os.getpid()}"
        try:
            temp_index.write_bytes(data)
            sign(temp_index, temp_signature, Path(args.key) if args.key else None, Path(args.pubkey))
            signature = temp_signature.read_bytes()
            os.replace(temp_index, out / "index.json")
            os.replace(temp_signature, out / "index.json.sig")
        finally:
            temp_index.unlink(missing_ok=True)
            temp_signature.unlink(missing_ok=True)
        outputs = {"changed": "true" if changed else "false", "generated": str(generated), "index_sha256": sha256_bytes(data)}
        live = sum(1 for pack in packs if not pack["revoked"])
        print(f"index.json {outputs['index_sha256']} {live} pack(s), {len(packs) - live} revoked, generated {generated}, {'changed' if changed else 'unchanged'}")
        if args.bundle_packs_dir:
            name, digest = bundle(out, packs, data, signature, Path(args.bundle_packs_dir), generated)
            outputs.update(bundle=name, bundle_sha256=digest)
            print(f"{name} {digest}")
        report(outputs)
    except (PackError, OSError) as e:
        print(f"make_index: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
