#!/usr/bin/env python3
from __future__ import annotations

import argparse
import functools
import hashlib
import importlib.machinery
import importlib.util
import io
import json
import os
import re
import subprocess
import sys
import tarfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
GUEST = ROOT / "tools/linuxfs/overlay/usr/local/bin/droiddeck-esync"
PATCHES = HERE / "patches"
REVOKED = HERE / "revoked.txt"
NTDLL = "files/lib/wine/aarch64-unix/ntdll.so"
WINESERVER = "files/bin-arm64/wineserver"
BINARIES = (NTDLL, WINESERVER)
COPY = ("files/bin-arm64/wine", "files/lib/wine/aarch64-unix/wine", "files/lib/wine/aarch64-unix/wine-preloader")
HEADERS = ("server_protocol.h", "request_handlers.h", "request_trace.h")
FLAVORS = ("valve", "ge", "cachyos")
SOURCE_KEYS = ("repo", "ref", "commit", "wine_commit", "sdk_image")
ENTRY_KEYS = ("id", "flavor", "rev", "version", "version_line", "stock", "exports", "source_match", "files", "protocol", "source", "patch")
PACK_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
SERIES = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")
SHA256 = re.compile(r"[0-9a-f]{64}")
EPOCH = re.compile(r"[0-9]{1,12}")
PROTOCOL = re.compile(rb"^#define SERVER_PROTOCOL_VERSION ([0-9]+)[ \t]*$", re.M)
BUILTIN_ESYNC = b"esync: up and running"
ELF_MAGIC = b"\x7fELF"
GUEST_MODULE = "droiddeck_esync_tools"


class PackError(RuntimeError):
    pass


@functools.lru_cache(maxsize=None)
def guest():
    loader = importlib.machinery.SourceFileLoader(GUEST_MODULE, str(GUEST))
    module = importlib.util.module_from_spec(importlib.util.spec_from_loader(GUEST_MODULE, loader))
    sys.modules[GUEST_MODULE] = module
    write_bytecode = sys.dont_write_bytecode
    sys.dont_write_bytecode = True
    try:
        loader.exec_module(module)
    except Exception as e:
        sys.modules.pop(GUEST_MODULE, None)
        raise PackError(f"cannot load {GUEST}: {e}") from e
    finally:
        sys.dont_write_bytecode = write_bytecode
    return module


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def slug(text: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]", "-", text)


def dump_json(value) -> bytes:
    return (json.dumps(value, indent=1, sort_keys=True, ensure_ascii=True) + "\n").encode()


def write_atomic(path: Path, data: bytes) -> None:
    temp = path.with_name(f".{path.name}.tmp-{os.getpid()}")
    try:
        with temp.open("wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp, path)
    except BaseException:
        temp.unlink(missing_ok=True)
        raise


def read_revoked(path: Path | None) -> set[str]:
    if path is None:
        return set()
    try:
        text = path.read_text()
    except OSError as e:
        raise PackError(f"cannot read the revocation list {path}: {e}") from e
    revoked = set()
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        ident = line.split()[0]
        if not PACK_ID.fullmatch(ident):
            raise PackError(f"{path}: {ident!r} is not a pack id")
        revoked.add(ident)
    return revoked


def tar_bytes(members: dict[str, tuple[bytes, int]]) -> bytes:
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w", format=tarfile.USTAR_FORMAT) as tar:
        for name in sorted(members):
            data, mode = members[name]
            info = tarfile.TarInfo(name)
            info.size = len(data)
            info.mode = mode
            info.mtime = 0
            info.uid = 0
            info.gid = 0
            info.uname = ""
            info.gname = ""
            tar.addfile(info, io.BytesIO(data))
    return buffer.getvalue()


def zstd(data: bytes, *args: str) -> bytes:
    try:
        result = subprocess.run(["zstd", "-q", *args], input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    except FileNotFoundError as e:
        raise PackError("the zstd command is not installed") from e
    if result.returncode != 0:
        raise PackError(f"zstd {' '.join(args)} failed: {result.stderr.decode(errors='replace').strip()}")
    return result.stdout


def compress(data: bytes) -> bytes:
    return zstd(data, "-19", "-T1", "-c")


def unpack(archive: bytes) -> dict[str, tuple[bytes, int]]:
    members = {}
    try:
        with tarfile.open(fileobj=io.BytesIO(zstd(archive, "-d", "-c")), mode="r:") as tar:
            for info in tar:
                if not info.isreg() or info.name in members:
                    raise PackError(f"unexpected entry {info.name!r} in the archive")
                members[info.name] = (tar.extractfile(info).read(), info.mode)
    except tarfile.TarError as e:
        raise PackError(f"the archive does not read back: {e}") from e
    return members


def binary(base: Path, rel: str) -> Path:
    path = base / rel
    if path.is_symlink() or not path.is_file():
        raise PackError(f"{path} is not a regular file")
    with path.open("rb") as stream:
        if stream.read(len(ELF_MAGIC)) != ELF_MAGIC:
            raise PackError(f"{path} is not an ELF file")
    return path


def exports_hash(path: Path) -> str:
    try:
        value = guest().exports_hash(path)
    except PackError:
        raise
    except Exception as e:
        raise PackError(f"{path}: cannot read its exported symbols: {e}") from e
    if not isinstance(value, str) or not SHA256.fullmatch(value):
        raise PackError(f"{path}: has no exported symbols")
    return value


def parse_version_line(line: str) -> tuple[int, str]:
    fields = line.split()
    if "\n" in line or len(fields) < 2 or not EPOCH.fullmatch(fields[0]):
        raise PackError(f"version line {line!r} is not '<build epoch> <version>'")
    token = guest().version_token(line)
    if not isinstance(token, str) or not token:
        raise PackError(f"version line {line!r} has no version token")
    return int(fields[0]), token


def parse_source(text: str) -> dict[str, str]:
    try:
        source = json.loads(text)
    except ValueError as e:
        raise PackError(f"--source-json is not JSON: {e}") from e
    if not isinstance(source, dict) or not all(isinstance(key, str) and isinstance(value, str) for key, value in source.items()):
        raise PackError("--source-json must be an object of strings")
    missing = [key for key in SOURCE_KEYS if not source.get(key)]
    if missing:
        raise PackError(f"--source-json lacks {', '.join(missing)}")
    return source


def patch_digest(directory: Path) -> str:
    patches = sorted(path for path in directory.glob("*.patch") if path.is_file())
    if not patches:
        raise PackError(f"{directory} holds no *.patch files")
    digest = hashlib.sha256()
    for path in patches:
        digest.update(f"{path.name}\0{sha256_file(path)}\n".encode())
    return digest.hexdigest()


def protocol_version(control: Path, patched: Path) -> int:
    for name in HEADERS:
        before, after = control / name, patched / name
        if not before.is_file() or not after.is_file():
            raise PackError(f"{name} is missing from the control or the patched build")
        if before.read_bytes() != after.read_bytes():
            raise PackError(f"the patch changes {name}: the droiddeck-esync patch must not change the wineserver protocol")
    found = PROTOCOL.findall((control / HEADERS[0]).read_bytes())
    if len(found) != 1:
        raise PackError("server_protocol.h does not define SERVER_PROTOCOL_VERSION once")
    version = int(found[0])
    stamp = control / "protocol"
    if stamp.is_file() and stamp.read_text().strip() != str(version):
        raise PackError(f"{stamp} disagrees with server_protocol.h ({version})")
    return version


def make_pack(
    flavor: str,
    rev: int,
    stock_dir: Path | None,
    version_line: str,
    control: Path,
    patched: Path,
    source: dict[str, str],
    series: str,
    out: Path,
    source_match: bool,
    patch_dir: Path | None = None,
    allow_unreproduced: bool = False,
) -> dict:
    if flavor not in FLAVORS:
        raise PackError(f"unknown flavor {flavor!r}")
    if type(rev) is not int or rev < 1:
        raise PackError("--rev must be a positive number")
    if not SERIES.fullmatch(series):
        raise PackError(f"{series!r} is not a patch series name")
    if stock_dir is None and not source_match:
        raise PackError("without --stock-dir a pack can only match by source: pass --source-match")
    if stock_dir is not None and source_match:
        raise PackError("a pack made against stock binaries matches them exactly: drop --source-match")
    patch_dir = patch_dir or PATCHES / series
    if not patch_dir.is_dir():
        raise PackError(f"the patch series {patch_dir} does not exist")
    version_line = version_line.strip()
    if stock_dir is not None:
        stock_line = guest().version_line(stock_dir)
        if stock_line and version_line and stock_line != version_line:
            raise PackError(f"{stock_dir}/version says {stock_line!r}, not {version_line!r}")
        version_line = version_line or stock_line
    epoch, token = parse_version_line(version_line)

    stock_paths = {rel: binary(stock_dir or control, rel) for rel in BINARIES}
    control_paths = {rel: binary(control, rel) for rel in BINARIES}
    patched_paths = {rel: binary(patched, rel) for rel in BINARIES}
    if BUILTIN_ESYNC in stock_paths[WINESERVER].read_bytes():
        raise PackError(f"{stock_paths[WINESERVER]} has esync built in and needs no pack")
    stock = {rel: sha256_file(path) for rel, path in stock_paths.items()}
    built = {rel: sha256_file(path) for rel, path in control_paths.items()}
    files = {rel: sha256_file(path) for rel, path in patched_paths.items()}
    for rel in BINARIES:
        if files[rel] == built[rel]:
            raise PackError(f"the patch left {rel} unchanged")

    exports = exports_hash(stock_paths[NTDLL])
    control_exports = exports_hash(control_paths[NTDLL])
    if exports_hash(patched_paths[NTDLL]) != control_exports:
        raise PackError("the patch changes the symbols ntdll.so exports")
    if exports != control_exports:
        raise PackError("the control build of ntdll.so exports other symbols than the stock one: the wine source does not match this release")
    protocol = protocol_version(control, patched)
    if stock_dir is not None:
        for rel in BINARIES:
            state = "reproduces" if stock[rel] == built[rel] else "differs from"
            print(f"make_pack: the control build {state} the stock {rel}", file=sys.stderr)
        unreproduced = [rel for rel in BINARIES if stock[rel] != built[rel]]
        if unreproduced and not allow_unreproduced:
            raise PackError(f"the control build does not reproduce the stock {', '.join(unreproduced)}: the patched files would differ "
                            "from the release in more than the patch (check the build directory and the SDK image, or pass --allow-unreproduced)")

    pack_id = f"{flavor}-{slug(token)}-{epoch}-{stock[NTDLL][:12]}-r{rev}"
    if not PACK_ID.fullmatch(pack_id):
        raise PackError(f"{pack_id!r} is not a valid pack id")
    pack = {
        "format": 1,
        "id": pack_id,
        "flavor": flavor,
        "rev": rev,
        "version": token,
        "version_line": version_line,
        "stock": stock,
        "exports": exports,
        "source_match": source_match,
        "files": files,
        "copy": list(COPY),
        "protocol": protocol,
        "source": dict(source),
        "patch": {"series": series, "sha256": patch_digest(patch_dir)},
    }
    members = {
        "pack.json": (dump_json(pack), 0o644),
        NTDLL: (patched_paths[NTDLL].read_bytes(), 0o755),
        WINESERVER: (patched_paths[WINESERVER].read_bytes(), 0o755),
    }
    archive = compress(tar_bytes(members))
    if unpack(archive) != members:
        raise PackError("the archive does not read back as written")
    entry = {key: pack[key] for key in ENTRY_KEYS}
    entry["asset"] = {"sha256": sha256_bytes(archive), "size": len(archive)}
    entry["revoked"] = False
    out.mkdir(parents=True, exist_ok=True)
    write_atomic(out / f"{pack_id}.tzst", archive)
    write_atomic(out / f"{pack_id}.json", dump_json(entry))
    return entry


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Make a droiddeck-esync pack from a control and a patched wine build of one Proton release.")
    parser.add_argument("--flavor", required=True, choices=FLAVORS)
    parser.add_argument("--rev", required=True, type=int)
    parser.add_argument("--stock-dir", default="")
    parser.add_argument("--version-line", default="")
    parser.add_argument("--control", required=True)
    parser.add_argument("--patched", required=True)
    parser.add_argument("--source-json", required=True)
    parser.add_argument("--patch-series", required=True)
    parser.add_argument("--patch-dir", default="")
    parser.add_argument("--out", required=True)
    parser.add_argument("--source-match", action="store_true")
    parser.add_argument("--allow-unreproduced", action="store_true")
    args = parser.parse_args(argv)
    try:
        entry = make_pack(
            flavor=args.flavor,
            rev=args.rev,
            stock_dir=Path(args.stock_dir) if args.stock_dir else None,
            version_line=args.version_line,
            control=Path(args.control),
            patched=Path(args.patched),
            source=parse_source(args.source_json),
            series=args.patch_series,
            out=Path(args.out),
            source_match=args.source_match,
            patch_dir=Path(args.patch_dir) if args.patch_dir else None,
            allow_unreproduced=args.allow_unreproduced,
        )
    except (PackError, OSError) as e:
        print(f"make_pack: {e}", file=sys.stderr)
        return 1
    print(entry["id"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
