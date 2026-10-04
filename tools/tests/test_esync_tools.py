import base64
import contextlib
import datetime as dt
import hashlib
import importlib.util
import io
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tarfile
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
ESYNC = ROOT / "tools/droiddeck-esync"
WORKFLOWS = ROOT / ".github/workflows"


def load(name):
    spec = importlib.util.spec_from_file_location(name, ESYNC / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


make_pack = load("make_pack")
make_index = load("make_index")
discover = load("discover")

NTDLL = make_pack.NTDLL
WINESERVER = make_pack.WINESERVER
HAVE_ZSTD = shutil.which("zstd") is not None
HAVE_OPENSSL = shutil.which("openssl") is not None
SPEC_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEFyqZQAMh3g3nsEGLU+kBGVPBEkXrDv5F3kPtuQ+FV1DkTCGmYUQ1LS9wJtHGt0Ht4wToHGGEp9yzDibYZ4/zhg=="
GE_LINE = "1789520806 GE-Proton11-7"
VALVE_LINE = "1789159687 experimental-11.0-20260910b-arm64"
SYMBOLS = (("NtClose", 1, 7), ("__wine_unix_call_funcs", 1, 7), ("wine_weak_export", 2, 7),
           ("static_helper", 0, 7), ("dlopen", 1, 0), ("", 1, 7))
HEADERS = {
    "server_protocol.h": b"#ifndef __WINE_WINE_SERVER_PROTOCOL_H\n#define SERVER_PROTOCOL_VERSION 931\n#endif\n",
    "request_handlers.h": b"static const req_handler req_handlers[REQ_NB_REQUESTS];\n",
    "request_trace.h": b"static const dump_func req_dumpers[REQ_NB_REQUESTS];\n",
}
SOURCE = {"repo": "GloriousEggroll/proton-ge-custom", "ref": "GE-Proton11-7", "asset": "GE-Proton11-7-aarch64.tar.gz",
          "commit": "1" * 40, "wine_commit": "2" * 40, "sdk_image": "registry.gitlab.steamos.cloud/proton/steamrt4/sdk/arm64-llvm:4.0-0"}


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def elf(symbols, payload=b""):
    strings = b"\0"
    table = [bytes(24)]
    for name, bind, shndx in symbols:
        table.append(struct.pack("<IBBHQQ", len(strings) if name else 0, (bind << 4) | 2, 0, shndx, 0x1000, 8))
        if name:
            strings += name.encode() + b"\0"
    dynsym = b"".join(table)
    names = b"\0.dynsym\0.dynstr\0.shstrtab\0"
    dynsym_at = 64
    strings_at = dynsym_at + len(dynsym)
    names_at = strings_at + len(strings)
    body = dynsym + strings + names + payload
    sections_at = (64 + len(body) + 7) // 8 * 8
    sections = [
        bytes(64),
        struct.pack("<IIQQQQIIQQ", 1, 11, 2, 0, dynsym_at, len(dynsym), 2, 1, 8, 24),
        struct.pack("<IIQQQQIIQQ", 9, 3, 2, 0, strings_at, len(strings), 0, 0, 1, 0),
        struct.pack("<IIQQQQIIQQ", 17, 3, 0, 0, names_at, len(names), 0, 0, 1, 0),
    ]
    header = b"\x7fELF" + bytes((2, 1, 1, 0)) + bytes(8) + struct.pack(
        "<HHIQQQIHHHHHH", 3, 183, 1, 0, 0, sections_at, 0, 64, 0, 0, 64, len(sections), 3)
    data = header + body
    return data + bytes(sections_at - len(data)) + b"".join(sections)


def spec_exports(symbols):
    names = sorted({name for name, bind, shndx in symbols if name and shndx and bind in (1, 2)})
    return sha256("\n".join(names).encode())


def tool_tree(base, label, symbols=SYMBOLS, version=None, server=b""):
    (base / "files/lib/wine/aarch64-unix").mkdir(parents=True)
    (base / "files/bin-arm64").mkdir(parents=True)
    (base / NTDLL).write_bytes(elf(symbols, label.encode()))
    (base / WINESERVER).write_bytes(elf((), label.encode() + server))
    for name, data in HEADERS.items():
        (base / name).write_bytes(data)
    if version:
        (base / "version").write_text(version + "\n")
    return base


def members(archive):
    data = subprocess.run(["zstd", "-q", "-d", "-c"], input=archive, stdout=subprocess.PIPE, check=True).stdout
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:") as tar:
        return {info.name: (info, tar.extractfile(info).read() if info.isreg() else None) for info in tar}


def run_main(function, argv, env=None):
    env = env or {}
    stdout, stderr = io.StringIO(), io.StringIO()
    with mock.patch.dict(os.environ, env), contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
        for name in ("GITHUB_OUTPUT", "SYNC_INDEX_KEY"):
            if name not in env:
                os.environ.pop(name, None)
        code = function(argv)
    return code, stdout.getvalue(), stderr.getvalue()


@unittest.skipUnless(HAVE_ZSTD, "zstd is not installed")
class MakePackTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.stock = tool_tree(self.tmp / "stock", "stock", version=GE_LINE)
        self.control = tool_tree(self.tmp / "control", "stock")
        self.patched = tool_tree(self.tmp / "patched", "patched")
        self.patches = self.tmp / "patches"
        self.patches.mkdir()
        (self.patches / "0001-ntdll-esync.patch").write_text("--- a/x\n+++ b/x\n")
        (self.patches / "0002-server-esync.patch").write_text("--- a/y\n+++ b/y\n")

    def make(self, *extra, stock=True, out="out", flavor="ge", rev="2", line=GE_LINE, source=None, series="valve-11"):
        argv = ["--flavor", flavor, "--rev", rev, "--stock-dir", str(self.stock) if stock else "", "--version-line", line,
                "--control", str(self.control), "--patched", str(self.patched), "--source-json", json.dumps(source or SOURCE),
                "--patch-series", series, "--patch-dir", str(self.patches), "--out", str(self.tmp / out), *extra]
        code, stdout, stderr = run_main(make_pack.main, argv)
        return code, stdout.strip(), stderr

    def test_pack_from_stock_binaries(self):
        code, ident, stderr = self.make()
        self.assertEqual(code, 0, stderr)
        stock_ntdll = sha256((self.stock / NTDLL).read_bytes())
        self.assertEqual(ident, f"ge-GE-Proton11-7-1789520806-{stock_ntdll[:12]}-r2")
        self.assertRegex(ident, r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
        out = self.tmp / "out"
        self.assertEqual(sorted(path.name for path in out.iterdir()), [f"{ident}.json", f"{ident}.tzst"])

        archive = (out / f"{ident}.tzst").read_bytes()
        content = members(archive)
        self.assertEqual(list(content), [WINESERVER, NTDLL, "pack.json"])
        for name, (info, data) in content.items():
            self.assertTrue(info.isreg())
            self.assertEqual((info.mtime, info.uid, info.gid, info.uname, info.gname), (0, 0, 0, "", ""))
            self.assertEqual(info.mode, 0o644 if name == "pack.json" else 0o755)
        self.assertEqual(content[NTDLL][1], (self.patched / NTDLL).read_bytes())
        self.assertEqual(content[WINESERVER][1], (self.patched / WINESERVER).read_bytes())

        pack = json.loads(content["pack.json"][1])
        self.assertEqual(pack["format"], 1)
        self.assertEqual(pack["id"], ident)
        self.assertEqual((pack["flavor"], pack["rev"], pack["version"], pack["version_line"]), ("ge", 2, "GE-Proton11-7", GE_LINE))
        self.assertEqual(pack["stock"], {NTDLL: stock_ntdll, WINESERVER: sha256((self.stock / WINESERVER).read_bytes())})
        self.assertEqual(pack["files"], {rel: sha256((self.patched / rel).read_bytes()) for rel in (NTDLL, WINESERVER)})
        self.assertEqual(pack["exports"], spec_exports(SYMBOLS))
        self.assertIs(pack["source_match"], False)
        self.assertEqual(pack["copy"], ["files/bin-arm64/wine", "files/lib/wine/aarch64-unix/wine", "files/lib/wine/aarch64-unix/wine-preloader"])
        self.assertEqual(pack["protocol"], 931)
        self.assertEqual(pack["source"], SOURCE)
        expected_patch = hashlib.sha256()
        for path in sorted(self.patches.glob("*.patch")):
            expected_patch.update(f"{path.name}\0{sha256(path.read_bytes())}\n".encode())
        self.assertEqual(pack["patch"], {"series": "valve-11", "sha256": expected_patch.hexdigest()})

        entry = json.loads((out / f"{ident}.json").read_text())
        self.assertEqual(entry["asset"], {"sha256": sha256(archive), "size": len(archive)})
        self.assertIs(entry["revoked"], False)
        self.assertEqual(set(entry), set(make_pack.ENTRY_KEYS) | {"asset", "revoked"})
        for key in make_pack.ENTRY_KEYS:
            self.assertEqual(entry[key], pack[key])
        self.assertIn("the control build reproduces the stock", stderr)

    def test_control_must_reproduce_the_stock_binaries(self):
        self.control = tool_tree(self.tmp / "control2", "control")
        code, ident, stderr = self.make()
        self.assertEqual(code, 1)
        self.assertEqual(ident, "")
        self.assertIn("does not reproduce the stock", stderr)
        self.assertFalse((self.tmp / "out").exists())
        code, ident, stderr = self.make("--allow-unreproduced")
        self.assertEqual(code, 0, stderr)
        self.assertIn("the control build differs from the stock", stderr)

    def test_guest_accepts_and_matches_the_pack(self):
        code, ident, stderr = self.make()
        self.assertEqual(code, 0, stderr)
        store = self.tmp / "store"
        pack_dir = store / "packs" / ident
        pack_dir.mkdir(parents=True)
        with tarfile.open(fileobj=io.BytesIO(subprocess.run(["zstd", "-q", "-d", "-c"], input=(self.tmp / "out" / f"{ident}.tzst").read_bytes(),
                                                            stdout=subprocess.PIPE, check=True).stdout), mode="r:") as tar:
            tar.extractall(pack_dir, filter="data")
        (pack_dir / ".complete").write_bytes(b"")
        guest = make_pack.guest()
        packs = guest.load_packs(str(store))
        self.assertEqual([pack["id"] for pack in packs], [ident])
        self.assertEqual(guest.find_pack(packs, guest.measure(str(self.stock)))["id"], ident)
        other = tool_tree(self.tmp / "other", "other", version=GE_LINE)
        self.assertIsNone(guest.find_pack(packs, guest.measure(str(other))))

    def test_output_is_deterministic(self):
        code, first, stderr = self.make(out="one")
        self.assertEqual(code, 0, stderr)
        for path in (self.patched / NTDLL, self.patched / WINESERVER, self.patches / "0001-ntdll-esync.patch"):
            os.utime(path, (1, 1))
        code, second, stderr = self.make(out="two")
        self.assertEqual(code, 0, stderr)
        self.assertEqual(first, second)
        for suffix in (".tzst", ".json"):
            self.assertEqual((self.tmp / "one" / f"{first}{suffix}").read_bytes(), (self.tmp / "two" / f"{second}{suffix}").read_bytes())

    def test_version_line_comes_from_stock_and_must_agree(self):
        code, ident, stderr = self.make(line="")
        self.assertEqual(code, 0, stderr)
        self.assertTrue(ident.startswith("ge-GE-Proton11-7-1789520806-"))
        code, _, stderr = self.make(line="1789520806 GE-Proton11-8", out="other")
        self.assertEqual(code, 1)
        self.assertIn("GE-Proton11-8", stderr)
        self.assertFalse((self.tmp / "other").exists())
        (self.stock / "version").unlink()
        for bad in ("GE-Proton11-7", "x GE-Proton11-7"):
            code, _, stderr = self.make(line=bad, out="bad")
            self.assertEqual(code, 1)
            self.assertIn("is not '<build epoch> <version>'", stderr)

    def test_source_match_pack_hashes_the_control_build(self):
        code, ident, stderr = self.make("--source-match", stock=False, flavor="valve", rev="1", line=VALVE_LINE)
        self.assertEqual(code, 0, stderr)
        control_ntdll = sha256((self.control / NTDLL).read_bytes())
        self.assertEqual(ident, f"valve-experimental-11.0-20260910b-arm64-1789159687-{control_ntdll[:12]}-r1")
        entry = json.loads((self.tmp / "out" / f"{ident}.json").read_text())
        self.assertIs(entry["source_match"], True)
        self.assertEqual(entry["stock"], {NTDLL: control_ntdll, WINESERVER: sha256((self.control / WINESERVER).read_bytes())})
        self.assertEqual(entry["version"], "experimental-11.0-20260910b-arm64")

        store = self.tmp / "store"
        pack_dir = store / "packs" / ident
        pack_dir.mkdir(parents=True)
        for name, (_, data) in members((self.tmp / "out" / f"{ident}.tzst").read_bytes()).items():
            (pack_dir / name).parent.mkdir(parents=True, exist_ok=True)
            (pack_dir / name).write_bytes(data)
        (pack_dir / ".complete").write_bytes(b"")
        guest = make_pack.guest()
        packs = guest.load_packs(str(store))
        depot = tool_tree(self.tmp / "depot", "valve depot", version="1789159999 experimental-11.0-20260910b-arm64")
        self.assertEqual(guest.find_pack(packs, guest.measure(str(depot)))["id"], ident)
        newer = tool_tree(self.tmp / "newer", "valve depot", version="1789259999 experimental-11.0-20260917-arm64")
        self.assertIsNone(guest.find_pack(packs, guest.measure(str(newer))))

        code, _, stderr = self.make(stock=False, flavor="valve", line=VALVE_LINE, out="refused")
        self.assertEqual(code, 1)
        self.assertIn("--source-match", stderr)
        code, _, stderr = self.make("--source-match", out="refused")
        self.assertEqual(code, 1)
        self.assertIn("drop --source-match", stderr)
        self.assertFalse((self.tmp / "refused").exists())

    def test_protocol_changes_are_rejected(self):
        (self.patched / "request_trace.h").write_bytes(HEADERS["request_trace.h"] + b"static void dump_esync(void);\n")
        code, _, stderr = self.make()
        self.assertEqual(code, 1)
        self.assertIn("request_trace.h", stderr)
        self.assertIn("protocol", stderr)
        self.assertFalse((self.tmp / "out").exists())
        (self.patched / "request_trace.h").write_bytes(HEADERS["request_trace.h"])
        for tree in (self.control, self.patched):
            (tree / "server_protocol.h").write_bytes(b"#define SERVER_PROTOCOL_VERSION\n")
        code, _, stderr = self.make()
        self.assertEqual(code, 1)
        self.assertIn("SERVER_PROTOCOL_VERSION", stderr)
        (self.control / "server_protocol.h").unlink()
        code, _, stderr = self.make()
        self.assertEqual(code, 1)
        self.assertIn("missing", stderr)

    def test_protocol_stamp_must_agree(self):
        (self.control / "protocol").write_text("930\n")
        code, _, stderr = self.make()
        self.assertEqual(code, 1)
        self.assertIn("disagrees", stderr)
        (self.control / "protocol").write_text("931\n")
        code, _, stderr = self.make()
        self.assertEqual(code, 0, stderr)

    def test_export_changes_are_rejected(self):
        (self.patched / NTDLL).write_bytes(elf(SYMBOLS + (("esync_extra", 1, 7),), b"patched"))
        code, _, stderr = self.make()
        self.assertEqual(code, 1)
        self.assertIn("the patch changes the symbols", stderr)
        (self.patched / NTDLL).write_bytes(elf(SYMBOLS, b"patched"))
        (self.stock / NTDLL).write_bytes(elf(SYMBOLS[:2], b"stock"))
        code, _, stderr = self.make()
        self.assertEqual(code, 1)
        self.assertIn("does not match this release", stderr)

    def test_builtin_esync_needs_no_pack(self):
        (self.stock / WINESERVER).write_bytes(elf((), b"stock esync: up and running\n"))
        code, _, stderr = self.make()
        self.assertEqual(code, 1)
        self.assertIn("has esync built in and needs no pack", stderr)

    def test_unpatched_builds_are_rejected(self):
        shutil.copy(self.control / WINESERVER, self.patched / WINESERVER)
        code, _, stderr = self.make()
        self.assertEqual(code, 1)
        self.assertIn(f"the patch left {WINESERVER} unchanged", stderr)

    def test_bad_inputs_are_rejected(self):
        (self.control / NTDLL).write_bytes(b"not an elf")
        code, _, stderr = self.make()
        self.assertEqual(code, 1)
        self.assertIn("not an ELF file", stderr)
        (self.control / NTDLL).write_bytes(elf(SYMBOLS, b"stock"))
        cases = (
            ({"rev": "0"}, "positive"),
            ({"series": "valve-11/../x"}, "not a patch series name"),
            ({"source": {key: value for key, value in SOURCE.items() if key != "commit"}}, "lacks commit"),
            ({"source": {**SOURCE, "commit": 7}}, "object of strings"),
        )
        for overrides, message in cases:
            code, _, stderr = self.make(**overrides)
            self.assertEqual(code, 1, overrides)
            self.assertIn(message, stderr)
        shutil.rmtree(self.patches)
        self.patches.mkdir()
        code, _, stderr = self.make()
        self.assertEqual(code, 1)
        self.assertIn("no *.patch files", stderr)
        self.assertFalse((self.tmp / "out").exists())

    def test_slug(self):
        self.assertEqual(make_pack.slug("GE-Proton 11/7~x+y"), "GE-Proton-11-7-x-y")
        self.assertEqual(make_pack.slug("experimental-11.0-20260910b-arm64"), "experimental-11.0-20260910b-arm64")


def index_entry(flavor, version, epoch, rev, ntdll, wineserver="b", source_match=False, ref=None):
    payload = f"{flavor} {version} {epoch} r{rev} {ntdll}".encode()
    ident = f"{flavor}-{make_pack.slug(version)}-{epoch}-{(ntdll * 12)[:12]}-r{rev}"
    entry = {
        "id": ident, "flavor": flavor, "rev": rev, "version": version, "version_line": f"{epoch} {version}",
        "stock": {NTDLL: ntdll * 64, WINESERVER: wineserver * 64}, "exports": "e" * 64, "source_match": source_match,
        "files": {NTDLL: "1" * 64, WINESERVER: "2" * 64}, "protocol": 931,
        "source": {"repo": "owner/repo", "ref": ref or version, "asset": "", "commit": "c" * 40, "wine_commit": "d" * 40,
                   "sdk_image": "registry.example/sdk/arm64-llvm:1"},
        "patch": {"series": "valve-11", "sha256": "f" * 64},
        "asset": {"sha256": sha256(payload), "size": len(payload)}, "revoked": False,
    }
    return entry, payload


@unittest.skipUnless(HAVE_OPENSSL and HAVE_ZSTD, "openssl or zstd is not installed")
class MakeIndexTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.keys = Path(tempfile.mkdtemp())
        cls.key = cls.keys / "key.pem"
        cls.pub = cls.keys / "pub.pem"
        subprocess.run(["openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", str(cls.key)], check=True, capture_output=True)
        subprocess.run(["openssl", "ec", "-in", str(cls.key), "-pubout", "-out", str(cls.pub)], check=True, capture_output=True)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.keys)

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.entries = self.tmp / "entries"
        self.packs = self.tmp / "packs"
        self.entries.mkdir()
        self.packs.mkdir()
        self.revoked = self.tmp / "revoked.txt"
        self.revoked.write_text("")
        self.output = self.tmp / "github-output"

    def add(self, *args, **kwargs):
        entry, payload = index_entry(*args, **kwargs)
        (self.entries / f"{entry['id']}.json").write_text(json.dumps(entry))
        (self.packs / f"{entry['id']}.tzst").write_bytes(payload)
        return entry

    def index(self, *extra, out="out", key=True, pubkey=True):
        argv = ["--entries", str(self.entries), "--base-url", "https://github.com/Droid-Deck/DroidDeck/releases/download/",
                "--revoked", str(self.revoked), *extra]
        if out:
            argv += ["--out", str(self.tmp / out)]
        if key:
            argv += ["--key", str(self.key)]
        if pubkey:
            argv += ["--pubkey", str(self.pub)]
        self.output.write_text("")
        return run_main(make_index.main, argv, {"GITHUB_OUTPUT": str(self.output)})

    def outputs(self):
        return dict(line.split("=", 1) for line in self.output.read_text().splitlines())

    def verify(self, out="out"):
        return subprocess.run(["openssl", "dgst", "-sha256", "-verify", str(self.pub), "-signature",
                               str(self.tmp / out / "index.json.sig"), str(self.tmp / out / "index.json")], capture_output=True, text=True)

    def test_highest_rev_per_stock_key_and_revocation(self):
        old = self.add("ge", "GE-Proton11-7", 1789520806, 1, "a")
        new = self.add("ge", "GE-Proton11-7", 1789520806, 2, "a")
        cachy = self.add("cachyos", "cachyos-11.0-20260703-slr", 1782000000, 1, "c", "d")
        withdrawn = self.add("valve", "experimental-11.0-20260910b-arm64", 1789159687, 1, "e", "f", source_match=True)
        self.revoked.write_text(f"{withdrawn['id']} crashes on start\n\n")
        code, stdout, stderr = self.index()
        self.assertEqual(code, 0, stderr)
        raw = (self.tmp / "out/index.json").read_bytes()
        index = json.loads(raw)
        self.assertEqual(raw, make_pack.dump_json(index))
        self.assertTrue(raw.startswith(b'{\n "generated": '))
        self.assertEqual(index["schema"], 1)
        listed = {pack["id"]: pack for pack in index["packs"]}
        self.assertEqual([pack["id"] for pack in index["packs"]], sorted(listed))
        self.assertEqual(set(listed), {new["id"], cachy["id"], withdrawn["id"]})
        self.assertNotIn(old["id"], listed)
        self.assertEqual({ident: pack["revoked"] for ident, pack in listed.items()},
                         {new["id"]: False, cachy["id"]: False, withdrawn["id"]: True})
        self.assertEqual(listed[new["id"]]["asset"], {
            "url": f"https://github.com/Droid-Deck/DroidDeck/releases/download/droiddeck-esync-ge/{new['id']}.tzst",
            "sha256": new["asset"]["sha256"], "size": new["asset"]["size"]})
        for key in ("id", "flavor", "rev", "version", "version_line", "stock", "exports", "source_match", "files"):
            self.assertEqual(listed[cachy["id"]][key], cachy[key])
        self.assertIn("2 pack(s), 1 revoked", stdout)
        self.assertEqual(self.outputs()["changed"], "true")

    def test_signature_round_trip(self):
        self.add("ge", "GE-Proton11-7", 1789520806, 1, "a")
        code, _, stderr = self.index()
        self.assertEqual(code, 0, stderr)
        result = self.verify()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Verified OK", result.stdout)
        self.assertEqual((self.tmp / "out/index.json.sig").read_bytes()[:1], b"\x30")
        self.assertEqual(sorted(path.name for path in (self.tmp / "out").iterdir()), ["index.json", "index.json.sig"])

        (self.tmp / "out/index.json").write_bytes((self.tmp / "out/index.json").read_bytes().replace(b'"rev": 1', b'"rev": 9'))
        self.assertNotEqual(self.verify().returncode, 0)

        argv = ["--entries", str(self.entries), "--base-url", "https://example.invalid/releases/download", "--revoked", str(self.revoked),
                "--out", str(self.tmp / "env"), "--pubkey", str(self.pub)]
        code, _, stderr = run_main(make_index.main, argv, {"SYNC_INDEX_KEY": self.key.read_text().strip(), "GITHUB_OUTPUT": str(self.output)})
        self.assertEqual(code, 0, stderr)
        self.assertEqual(self.verify("env").returncode, 0)

    def test_a_key_the_app_does_not_trust_is_refused(self):
        self.add("ge", "GE-Proton11-7", 1789520806, 1, "a")
        code, _, stderr = self.index(pubkey=False)
        self.assertEqual(code, 1)
        self.assertIn("index-key.pub", stderr)
        self.assertEqual(list((self.tmp / "out").iterdir()), [])
        code, _, stderr = self.index(key=False, out="nokey")
        self.assertEqual(code, 1)
        self.assertIn("no signing key", stderr)

    def test_generated_is_monotonic_and_unchanged_indexes_are_reported(self):
        self.add("ge", "GE-Proton11-7", 1789520806, 1, "a")
        before = int(time.time())
        code, _, stderr = self.index(out="first")
        self.assertEqual(code, 0, stderr)
        first = json.loads((self.tmp / "first/index.json").read_text())
        self.assertGreaterEqual(first["generated"], before)
        future = dict(first, generated=first["generated"] + 10 ** 6)
        (self.tmp / "future.json").write_text(json.dumps(future))
        code, _, stderr = self.index("--previous", str(self.tmp / "future.json"), out="second")
        self.assertEqual(code, 0, stderr)
        second = json.loads((self.tmp / "second/index.json").read_text())
        self.assertEqual(second["generated"], future["generated"] + 1)
        self.assertEqual(second["packs"], first["packs"])
        self.assertEqual(self.outputs()["changed"], "false")
        self.revoked.write_text(first["packs"][0]["id"] + "\n")
        code, _, stderr = self.index("--previous", str(self.tmp / "second/index.json"), out="third")
        self.assertEqual(code, 0, stderr)
        third = json.loads((self.tmp / "third/index.json").read_text())
        self.assertGreater(third["generated"], second["generated"])
        self.assertIs(third["packs"][0]["revoked"], True)
        self.assertEqual(self.outputs()["changed"], "true")

    def test_previous_packs_are_kept_until_revoked(self):
        kept = self.add("ge", "GE-Proton11-6", 1789000000, 1, "a")
        code, _, stderr = self.index(out="first")
        self.assertEqual(code, 0, stderr)
        (self.entries / f"{kept['id']}.json").unlink()
        newer = self.add("ge", "GE-Proton11-7", 1789520806, 1, "b")
        code, _, stderr = self.index("--previous", str(self.tmp / "first/index.json"), out="second")
        self.assertEqual(code, 0, stderr)
        listed = {pack["id"]: pack["revoked"] for pack in json.loads((self.tmp / "second/index.json").read_text())["packs"]}
        self.assertEqual(listed, {kept["id"]: False, newer["id"]: False})
        self.revoked.write_text(kept["id"] + "\n")
        code, _, stderr = self.index("--previous", str(self.tmp / "second/index.json"), out="third")
        self.assertEqual(code, 0, stderr)
        listed = {pack["id"]: pack["revoked"] for pack in json.loads((self.tmp / "third/index.json").read_text())["packs"]}
        self.assertEqual(listed, {kept["id"]: True, newer["id"]: False})

    def test_bundle_takes_the_newest_pack_of_each_family(self):
        ge_old = self.add("ge", "GE-Proton11-6", 1789000000, 1, "a")
        ge_new = self.add("ge", "GE-Proton11-7", 1789520806, 1, "b")
        ge_ten = self.add("ge", "GE-Proton10-30", 1790000000, 1, "c")
        exp_old = self.add("valve", "experimental-11.0-20260903c-arm64", 1788600000, 1, "d", source_match=True)
        exp_new = self.add("valve", "experimental-11.0-20260910b-arm64", 1789159687, 1, "e", source_match=True)
        stable = self.add("valve", "proton-11.0-2c-arm64", 1787000000, 1, "f", source_match=True)
        cachy_old = self.add("cachyos", "cachyos-11.0-20260702-slr", 1782000000, 1, "0")
        cachy_new = self.add("cachyos", "cachyos-11.0-20260703-slr", 1782100000, 1, "1")
        self.revoked.write_text(exp_new["id"] + "\n")
        (self.packs / f"{cachy_new['id']}.tzst").unlink()

        code, stdout, stderr = self.index("--list-bundle", out="", key=False, pubkey=False)
        self.assertEqual(code, 0, stderr)
        expected = sorted([ge_new, ge_ten, exp_old, stable, cachy_new], key=lambda entry: entry["id"])
        self.assertEqual(stdout.splitlines(), [f"{entry['flavor']}\t{entry['id']}" for entry in expected])

        code, stdout, stderr = self.index("--bundle-packs-dir", str(self.packs))
        self.assertEqual(code, 0, stderr)
        index = json.loads((self.tmp / "out/index.json").read_text())
        name = f"bundle-{dt.datetime.fromtimestamp(index['generated'], dt.timezone.utc):%Y%m%d-%H%M%S}.tzst"
        archive = (self.tmp / "out" / name).read_bytes()
        self.assertEqual(self.outputs()["bundle"], name)
        self.assertEqual(self.outputs()["bundle_sha256"], sha256(archive))
        self.assertIn(f"{name} {sha256(archive)}", stdout)
        content = members(archive)
        chosen = sorted([ge_new, ge_ten, exp_old, stable, cachy_old], key=lambda entry: entry["id"])
        self.assertEqual(sorted(content), sorted(["index.json", "index.json.sig", *(f"packs/{entry['id']}.tzst" for entry in chosen)]))
        self.assertEqual(content["index.json"][1], (self.tmp / "out/index.json").read_bytes())
        self.assertEqual(content["index.json.sig"][1], (self.tmp / "out/index.json.sig").read_bytes())
        self.assertEqual(content[f"packs/{ge_new['id']}.tzst"][1], (self.packs / f"{ge_new['id']}.tzst").read_bytes())
        self.assertNotIn(f"packs/{ge_old['id']}.tzst", content)
        for info, _ in content.values():
            self.assertEqual((info.mtime, info.uid, info.gid, info.mode), (0, 0, 0, 0o644))

    def test_every_index_gets_its_own_bundle_name(self):
        self.assertEqual(make_index.bundle_name(1791234567), "bundle-20261005-210927.tzst")
        self.assertEqual(make_index.bundle_name(1791234568), "bundle-20261005-210928.tzst")
        self.add("ge", "GE-Proton11-7", 1789520806, 1, "a")
        code, _, stderr = self.index("--bundle-packs-dir", str(self.packs), out="first")
        self.assertEqual(code, 0, stderr)
        first = self.outputs()
        self.add("ge", "GE-Proton11-8", 1789600000, 1, "b")
        code, _, stderr = self.index("--bundle-packs-dir", str(self.packs), "--previous", str(self.tmp / "first/index.json"), out="second")
        self.assertEqual(code, 0, stderr)
        second = self.outputs()
        self.assertEqual(second["changed"], "true")
        self.assertGreater(int(second["generated"]), int(first["generated"]))
        self.assertNotEqual(first["bundle"], second["bundle"])
        self.assertEqual(second["bundle"], make_index.bundle_name(int(second["generated"])))
        self.assertIn(f"packs/ge-GE-Proton11-8-1789600000-{'b' * 12}-r1.tzst", members((self.tmp / "second" / second["bundle"]).read_bytes()))

    def test_an_empty_first_index_is_not_published(self):
        code, stdout, stderr = self.index()
        self.assertEqual(code, 0, stderr)
        self.assertEqual(json.loads((self.tmp / "out/index.json").read_text())["packs"], [])
        self.assertEqual(self.outputs()["changed"], "false")
        self.assertIn("unchanged", stdout)

    def test_builds_that_share_the_stock_binaries_all_stay_listed(self):
        stable_b = self.add("valve", "proton-11.0-2b-arm64", 1787000000, 1, "a", source_match=True, ref="proton-11.0-2b")
        stable_c = self.add("valve", "proton-11.0-2c-arm64", 1787100000, 1, "a", source_match=True, ref="proton-11.0-2c")
        bumped_c = self.add("valve", "proton-11.0-2c-arm64", 1787100000, 2, "a", source_match=True, ref="proton-11.0-2c")
        cachy_old = self.add("cachyos", "cachyos-11.0-20260702-slr", 1782000000, 1, "c", "d")
        cachy_new = self.add("cachyos", "cachyos-11.0-20260703-slr", 1782100000, 1, "c", "d")
        code, _, stderr = self.index()
        self.assertEqual(code, 0, stderr)
        index = json.loads((self.tmp / "out/index.json").read_text())
        self.assertEqual({pack["id"] for pack in index["packs"]}, {stable_b["id"], bumped_c["id"], cachy_old["id"], cachy_new["id"]})
        self.assertNotIn(stable_c["id"], {pack["id"] for pack in index["packs"]})
        self.assertEqual(discover.built(index), {
            ("valve", "proton-11.0-2b", ""): 1, ("valve", "proton-11.0-2c", ""): 2,
            ("cachyos", "cachyos-11.0-20260702-slr", ""): 1, ("cachyos", "cachyos-11.0-20260703-slr", ""): 1})

    def test_the_previous_index_counts_only_when_its_signature_verifies(self):
        kept = self.add("ge", "GE-Proton11-6", 1789000000, 1, "a")
        code, _, stderr = self.index(out="first")
        self.assertEqual(code, 0, stderr)
        first = self.tmp / "first"
        (self.entries / f"{kept['id']}.json").unlink()
        newer = self.add("ge", "GE-Proton11-7", 1789520806, 1, "b")
        code, _, stderr = self.index("--previous", str(first / "index.json"), "--previous-sig", str(first / "index.json.sig"), out="second")
        self.assertEqual(code, 0, stderr)
        self.assertNotIn("does not verify", stderr)
        second = json.loads((self.tmp / "second/index.json").read_text())
        self.assertEqual({pack["id"] for pack in second["packs"]}, {kept["id"], newer["id"]})

        forged = json.loads((first / "index.json").read_text())
        forged["generated"] += 10 ** 6
        (self.tmp / "forged.json").write_text(json.dumps(forged))
        code, _, stderr = self.index("--previous", str(self.tmp / "forged.json"), "--previous-sig", str(first / "index.json.sig"), out="third")
        self.assertEqual(code, 0, stderr)
        self.assertIn("does not verify", stderr)
        third = json.loads((self.tmp / "third/index.json").read_text())
        self.assertEqual([pack["id"] for pack in third["packs"]], [newer["id"]])
        self.assertLess(third["generated"], forged["generated"])
        self.assertEqual(self.outputs()["changed"], "true")

        code, _, stderr = self.index("--previous", str(first / "index.json"), "--previous-sig", str(self.tmp / "missing.sig"), out="fourth")
        self.assertEqual(code, 0, stderr)
        self.assertIn("does not verify", stderr)
        code, _, stderr = self.index("--previous-sig", str(first / "index.json.sig"), out="fifth")
        self.assertEqual(code, 1)
        self.assertIn("--previous-sig needs --previous", stderr)

    def test_bundle_refuses_an_asset_that_differs_from_the_index(self):
        entry = self.add("ge", "GE-Proton11-7", 1789520806, 1, "a")
        (self.packs / f"{entry['id']}.tzst").write_bytes(b"tampered")
        code, _, stderr = self.index("--bundle-packs-dir", str(self.packs))
        self.assertEqual(code, 1)
        self.assertIn("is not the asset the index lists", stderr)

    def test_entries_are_validated(self):
        entry = self.add("ge", "GE-Proton11-7", 1789520806, 1, "a")
        source = self.entries / f"{entry['id']}.json"
        cases = (
            (dict(entry, flavor="proton"), "unknown flavor"),
            (dict(entry, rev=True), "rev"),
            (dict(entry, version="GE-Proton11-8"), "disagree"),
            (dict(entry, stock={NTDLL: "a" * 64}), "stock"),
            (dict(entry, files={NTDLL: "A" * 64, WINESERVER: "2" * 64}), "files"),
            (dict(entry, asset={"sha256": "0" * 64, "size": 0}), "asset"),
            (dict(entry, source_match="no"), "source_match"),
            (dict(entry, id="ge-other"), "holds the entry of ge-other"),
        )
        for broken, message in cases:
            source.write_text(json.dumps(broken))
            code, _, stderr = self.index()
            self.assertEqual(code, 1, broken)
            self.assertIn(message, stderr)
        source.write_text(json.dumps(entry))
        (self.tmp / "previous.json").write_text(json.dumps({"schema": 2, "generated": 1, "packs": []}))
        code, _, stderr = self.index("--previous", str(self.tmp / "previous.json"))
        self.assertEqual(code, 1)
        self.assertIn("not a schema 1 index", stderr)
        self.revoked.write_text("../escape\n")
        code, _, stderr = self.index()
        self.assertEqual(code, 1)
        self.assertIn("is not a pack id", stderr)
        code, _, stderr = run_main(make_index.main, ["--entries", str(self.entries), "--base-url", "http://insecure", "--out", str(self.tmp / "x")])
        self.assertEqual(code, 1)
        self.assertIn("https", stderr)

    def test_families(self):
        cases = {
            ("valve", "experimental-11.0-20260910b-arm64"): ("valve", "experimental", "11"),
            ("valve", "proton-11.0-2c-arm64"): ("valve", "proton", "11"),
            ("ge", "GE-Proton11-7"): ("ge", "ge", "11"),
            ("ge", "GE-Proton10-30"): ("ge", "ge", "10"),
            ("cachyos", "cachyos-11.0-20260703-slr"): ("cachyos", "cachyos", "11"),
        }
        for (flavor, version), family in cases.items():
            self.assertEqual(make_index.family({"flavor": flavor, "version": version}), family)


def completed(command, code=0, stdout="", stderr=""):
    return subprocess.CompletedProcess(command, code, stdout, stderr)


def ref(name):
    return {"ref": f"refs/tags/{name}", "object": {"sha": "0" * 40, "type": "commit"}}


def release(tag, published, *assets, draft=False):
    return {"tag_name": tag, "draft": draft, "published_at": published, "assets": [{"id": number, "name": name} for number, name in enumerate(assets)]}


class DiscoverTest(unittest.TestCase):
    FLAVORS = {
        "format": 1,
        "sdk_image": {"file": "Makefile.in", "variable": "STEAMRT_IMAGE", "match": "arm64-llvm"},
        "flavors": {
            "valve": {
                "repo": "ValveSoftware/Proton", "list": "tags",
                "families": {
                    "experimental": {"prefix": "experimental-11.0-", "tag": "^experimental-11\\.0-\\d{8}[a-z]?$", "depot_app": "4427310"},
                    "proton": {"prefix": "proton-11.0-", "tag": "^proton-11\\.0-\\d+[a-z]?$", "depot_app": "4628740"},
                },
                "version_token": "{tag}-arm64", "keep": 2, "rev": 1, "series": "valve-11", "profile": "valve", "source_match": True,
                "build_dir": "/builds/proton/proton/build-dir", "wine": {"submodules": ["wine"], "clone": "shallow", "prep": ""},
            },
            "ge": {
                "repo": "GloriousEggroll/proton-ge-custom", "list": "releases", "tag": "^GE-Proton11-\\d+$",
                "assets": ["aarch64\\.tar\\.(gz|xz)$", "arm64\\.tar\\.(gz|xz)$"],
                "keep": 2, "rev": 1, "series": "valve-11", "profile": "valve", "source_match": False, "build_dir": "/home/tcrider/build",
                "wine": {"submodules": ["wine", "wine-staging"], "clone": "treeless", "prep": "patches/protonprep-valve-staging.sh"},
            },
        },
    }
    EXPERIMENTAL = [ref("experimental-11.0-20260901"), ref("experimental-11.0-20260910"), ref("experimental-11.0-20260924-cache")]
    EXPERIMENTAL_MORE = [ref("experimental-11.0-20260910b"), ref("experimental-11.0-20260917b")]
    PROTON = [ref("proton-11.0-1"), ref("proton-11.0-2"), ref("proton-11.0-2b"), ref("proton-11.0-10"), ref("proton-11.0-1-beta5")]
    RELEASES = [
        release("GE-Proton11-8", "2026-09-30T00:00:00Z", "GE-Proton11-8-aarch64.tar.gz", draft=True),
        release("GE-Proton11-7", "2026-09-20T00:00:00Z", "GE-Proton11-7-aarch64.sha512sum", "GE-Proton11-7-aarch64.tar.gz",
                "GE-Proton11-7-aarch64.tar.xz", "GE-Proton11-7-x86_64.tar.gz"),
        release("GE-Proton10-30", "2026-09-15T00:00:00Z", "GE-Proton10-30-aarch64.tar.gz"),
        release("GE-Proton11-6", "2026-09-10T00:00:00Z", "GE-Proton11-6-aarch64.tar.gz"),
        release("GE-Proton11-5", "2026-09-01T00:00:00Z", "GE-Proton11-5-x86_64.tar.gz"),
        release("GE-Proton11-4", "2026-08-01T00:00:00Z", "GE-Proton11-4-aarch64.tar.gz"),
    ]
    INDEX = {"schema": 1, "generated": 1, "packs": [
        {"id": "ge-GE-Proton11-6-1-aaaaaaaaaaaa-r1", "flavor": "ge", "rev": 1,
         "source": {"ref": "GE-Proton11-6", "asset": "GE-Proton11-6-aarch64.tar.gz"}},
        {"id": "valve-experimental-11.0-20260917b-arm64-1-bbbbbbbbbbbb-r1", "flavor": "valve", "rev": 1,
         "source": {"ref": "experimental-11.0-20260917b", "asset": ""}},
    ]}
    REVOKED = "valve-proton-11.0-10-arm64-1789000000-0123456789ab-r1\n"

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.flavors_file = self.tmp / "flavors.json"
        self.flavors_file.write_text(json.dumps(self.FLAVORS))
        self.index_file = self.tmp / "index.json"
        self.index_file.write_text(json.dumps(self.INDEX))
        self.revoked_file = self.tmp / "revoked.txt"
        self.revoked_file.write_text(self.REVOKED)
        self.output = self.tmp / "github-output"
        self.output.write_text("")
        self.calls = []

    def gh(self, command, **kwargs):
        self.calls.append(command)
        self.assertEqual(command[:2], ["gh", "api"])
        self.assertEqual(kwargs.get("capture_output"), True)
        path = command[-1]
        if path == "repos/ValveSoftware/Proton/git/matching-refs/tags/experimental-11.0-":
            self.assertIn("--paginate", command)
            return completed(command, stdout=json.dumps(self.EXPERIMENTAL) + "\n" + json.dumps(self.EXPERIMENTAL_MORE))
        if path == "repos/ValveSoftware/Proton/git/matching-refs/tags/proton-11.0-":
            return completed(command, stdout=json.dumps(self.PROTON))
        if path == "repos/GloriousEggroll/proton-ge-custom/releases?per_page=100":
            return completed(command, stdout=json.dumps(self.RELEASES))
        if path.startswith("repos/GloriousEggroll/proton-ge-custom/releases/tags/"):
            tag = path.rsplit("/", 1)[1]
            for item in self.RELEASES:
                if item["tag_name"] == tag:
                    return completed(command, stdout=json.dumps(item))
        return completed(command, 1, stderr="gh: Not Found (HTTP 404)")

    def discover(self, *extra, flavor="all", ref=""):
        argv = ["--flavors", str(self.flavors_file), "matrix", "--flavor", flavor, "--ref", ref, "--index", str(self.index_file),
                "--revoked", str(self.revoked_file), *extra]
        with mock.patch.object(discover.subprocess, "run", side_effect=self.gh):
            return run_main(discover.main, argv, {"GITHUB_OUTPUT": str(self.output)})

    def test_matrix_skips_built_and_revoked_builds(self):
        code, stdout, stderr = self.discover()
        self.assertEqual(code, 0, stderr)
        matrix = json.loads(stdout)
        rows = {row["key"]: row for row in matrix["include"]}
        self.assertEqual(sorted(rows), ["ge-GE-Proton11-7-aarch64", "valve-experimental-11.0-20260910b", "valve-proton-11.0-2b"])
        self.assertEqual(rows["ge-GE-Proton11-7-aarch64"], {
            "key": "ge-GE-Proton11-7-aarch64", "flavor": "ge", "repo": "GloriousEggroll/proton-ge-custom", "tag": "GE-Proton11-7",
            "asset": "GE-Proton11-7-aarch64.tar.xz", "token": "", "depot_app": "", "rev": 1, "series": "valve-11", "profile": "valve",
            "source_match": False, "submodules": "wine wine-staging", "clone": "treeless", "prep": "patches/protonprep-valve-staging.sh",
            "build_dir": "/home/tcrider/build"})
        experimental = rows["valve-experimental-11.0-20260910b"]
        self.assertEqual((experimental["token"], experimental["depot_app"], experimental["asset"], experimental["source_match"]),
                         ("experimental-11.0-20260910b-arm64", "4427310", "", True))
        self.assertEqual(rows["valve-proton-11.0-2b"]["depot_app"], "4628740")
        self.assertIn("ge GE-Proton11-6 GE-Proton11-6-aarch64.tar.gz: in the index at rev 1", stderr)
        self.assertIn("valve experimental-11.0-20260917b: in the index at rev 1", stderr)
        self.assertIn("valve proton-11.0-10: revoked at rev 1", stderr)
        self.assertNotIn("GE-Proton10-30", stdout)
        self.assertNotIn("GE-Proton11-4", stdout)
        lines = self.output.read_text().splitlines()
        self.assertEqual(lines, [f"matrix={json.dumps(matrix, sort_keys=True, separators=(',', ':'))}", "count=3", "unindexed=0", "revoke=0"])

    def test_a_new_patch_rev_rebuilds(self):
        bumped = json.loads(json.dumps(self.FLAVORS))
        for cfg in bumped["flavors"].values():
            cfg["rev"] = 2
        self.flavors_file.write_text(json.dumps(bumped))
        code, stdout, stderr = self.discover()
        self.assertEqual(code, 0, stderr)
        keys = sorted(row["key"] for row in json.loads(stdout)["include"])
        self.assertEqual(keys, ["ge-GE-Proton11-6-aarch64", "ge-GE-Proton11-7-aarch64", "valve-experimental-11.0-20260910b",
                                "valve-experimental-11.0-20260917b", "valve-proton-11.0-10", "valve-proton-11.0-2b"])

    def test_one_ref(self):
        code, stdout, stderr = self.discover(ref="GE-Proton11-4")
        self.assertEqual(code, 0, stderr)
        self.assertEqual([row["asset"] for row in json.loads(stdout)["include"]], ["GE-Proton11-4-aarch64.tar.gz"])
        self.assertFalse(any("matching-refs" in call[-1] for call in self.calls))
        code, stdout, stderr = self.discover(flavor="valve", ref="experimental-11.0-20260901")
        self.assertEqual(code, 0, stderr)
        self.assertEqual([row["tag"] for row in json.loads(stdout)["include"]], ["experimental-11.0-20260901"])
        code, stdout, stderr = self.discover(ref="GE-Proton11-6")
        self.assertEqual(code, 0, stderr)
        self.assertEqual(json.loads(stdout)["include"], [])
        self.assertTrue(self.output.read_text().endswith("count=0\nunindexed=0\nrevoke=0\n"))
        code, _, stderr = self.discover(ref="GE-Proton11-5")
        self.assertEqual(code, 1)
        self.assertIn("no build is tagged GE-Proton11-5", stderr)

    def test_index_from_the_release(self):
        responses = {
            "repos/Droid-Deck/DroidDeck/releases/tags/droiddeck-esync-index": completed([], stdout=json.dumps({"assets": [
                {"id": 5, "name": "index.json.sig"}, {"id": 6, "name": "index.json"}]})),
            "repos/Droid-Deck/DroidDeck/releases/assets/6": completed([], stdout=json.dumps(self.INDEX)),
        }

        def run(command, **kwargs):
            if command[-1] in responses:
                if command[-1].endswith("/6"):
                    self.assertIn("Accept: application/octet-stream", command)
                return responses[command[-1]]
            return completed(command, 1, stderr="gh: Not Found (HTTP 404)")

        with mock.patch.object(discover.subprocess, "run", side_effect=run):
            self.assertEqual(discover.release_index("Droid-Deck/DroidDeck", "droiddeck-esync-index"), self.INDEX)
            self.assertEqual(discover.release_index("Droid-Deck/Missing", "droiddeck-esync-index"), {})
        with mock.patch.object(discover.subprocess, "run", return_value=completed([], 1, stderr="HTTP 502: Bad Gateway")):
            with self.assertRaises(discover.PackError):
                discover.release_index("Droid-Deck/DroidDeck", "droiddeck-esync-index")

    def test_api_failures_stop_discovery(self):
        with mock.patch.object(discover.subprocess, "run", return_value=completed([], 1, stderr="HTTP 403: rate limit exceeded")):
            code, stdout, stderr = run_main(discover.main, ["--flavors", str(self.flavors_file), "matrix", "--index", str(self.index_file)],
                                            {"GITHUB_OUTPUT": str(self.output)})
        self.assertEqual(code, 1)
        self.assertIn("rate limit", stderr)
        self.assertEqual(self.output.read_text(), "")

    def test_published_entries_count_as_built(self):
        published = {"id": "ge-GE-Proton11-7-1789520806-aaaaaaaaaaaa-r1", "flavor": "ge", "rev": 1,
                     "source": {"ref": "GE-Proton11-7", "asset": "GE-Proton11-7-aarch64.tar.xz"}}
        stray = {"id": "valve-other-r1", "flavor": "valve", "rev": 1, "source": {"ref": "experimental-11.0-20260910b", "asset": ""}}
        base = "repos/Droid-Deck/DroidDeck/releases"
        responses = {
            f"{base}/tags/droiddeck-esync-index": completed([], stdout=json.dumps({"id": 1, "assets": [{"id": 6, "name": "index.json"}]})),
            f"{base}/assets/6": completed([], stdout=json.dumps(self.INDEX)),
            f"{base}/tags/droiddeck-esync-ge": completed([], stdout=json.dumps({"id": 20, "assets": []})),
            f"{base}/20/assets?per_page=100": completed([], stdout=json.dumps([
                {"id": 21, "name": f"{published['id']}.tzst"}, {"id": 22, "name": f"{published['id']}.json"},
                {"id": 23, "name": "ge-GE-Proton11-6-1-aaaaaaaaaaaa-r1.json"}, {"id": 24, "name": "ge-GE-Proton11-5-1-aaaaaaaaaaaa-r0.json"},
                {"id": 25, "name": "ge-broken-r1.json"}, {"id": 26, "name": "ge-stray-r1.json"}]) + "\n" + json.dumps([])),
            f"{base}/assets/22": completed([], stdout=json.dumps(published)),
            f"{base}/assets/25": completed([], stdout="not json"),
            f"{base}/assets/26": completed([], stdout=json.dumps(dict(stray, id="ge-stray-r1"))),
        }

        def run(command, **kwargs):
            if command[-1] in responses:
                self.calls.append(command)
                return responses[command[-1]]
            return self.gh(command, **kwargs)

        argv = ["--flavors", str(self.flavors_file), "matrix", "--index-release", "droiddeck-esync-index", "--repo", "Droid-Deck/DroidDeck",
                "--revoked", str(self.revoked_file)]
        with mock.patch.object(discover.subprocess, "run", side_effect=run):
            code, stdout, stderr = run_main(discover.main, argv, {"GITHUB_OUTPUT": str(self.output)})
        self.assertEqual(code, 0, stderr)
        self.assertEqual(sorted(row["key"] for row in json.loads(stdout)["include"]), ["valve-experimental-11.0-20260910b", "valve-proton-11.0-2b"])
        self.assertIn("ge GE-Proton11-7 GE-Proton11-7-aarch64.tar.xz: in the index at rev 1", stderr)
        self.assertIn(f"droiddeck-esync-ge {published['id']}.json: published, not in the index yet", stderr)
        self.assertIn("droiddeck-esync-ge ge-broken-r1.json is not a pack entry", stderr)
        self.assertIn("droiddeck-esync-ge ge-stray-r1.json is not a pack entry", stderr)
        downloaded = {call[-1] for call in self.calls if "/assets/" in call[-1] and "?" not in call[-1]}
        self.assertEqual(downloaded, {f"{base}/assets/6", f"{base}/assets/22", f"{base}/assets/25", f"{base}/assets/26"})
        self.assertIn("unindexed=1\nrevoke=0\n", self.output.read_text())
        self.revoked_file.write_text(self.REVOKED + self.INDEX["packs"][0]["id"] + "\n")
        self.output.write_text("")
        with mock.patch.object(discover.subprocess, "run", side_effect=run):
            code, stdout, stderr = run_main(discover.main, argv, {"GITHUB_OUTPUT": str(self.output)})
        self.assertEqual(code, 0, stderr)
        self.assertIn("revoke=1\n", self.output.read_text())
        self.assertIn(f"discover: {self.INDEX['packs'][0]['id']}: revoked.txt and the published index disagree", stderr)
        self.revoked_file.write_text(self.REVOKED + "ge-GE-Proton11-5-1-aaaaaaaaaaaa-r0\n")
        self.output.write_text("")
        with mock.patch.object(discover.subprocess, "run", side_effect=run):
            code, stdout, stderr = run_main(discover.main, argv, {"GITHUB_OUTPUT": str(self.output)})
        self.assertEqual(code, 0, stderr)
        self.assertIn("revoke=1\n", self.output.read_text())
        self.assertIn("discover: ge-GE-Proton11-5-1-aaaaaaaaaaaa-r0: revoked.txt and the published index disagree", stderr)

    def test_revocations_the_index_does_not_show_yet(self):
        first, second = (dict(pack) for pack in self.INDEX["packs"])
        revoked = discover.read_revoked(self.revoked_file)
        self.assertEqual(discover.pending_revocations([first, second], revoked), [])
        self.assertEqual(discover.pending_revocations([first, dict(second, revoked=True)], revoked), [second["id"]])
        self.assertEqual(discover.pending_revocations([first, second], revoked | {first["id"]}), [first["id"]])
        self.assertEqual(discover.pending_revocations([dict(first, revoked=True), "junk", {"id": 3}], revoked | {first["id"]}), [])
        superseded = "ge-GE-Proton11-6-1-aaaaaaaaaaaa-r0"
        self.assertEqual(discover.pending_revocations([first, second], revoked | {superseded}, {first["id"], superseded}), [superseded])
        self.assertEqual(discover.pending_revocations([first, dict(second, id=superseded, revoked=True)], revoked | {superseded}, {superseded}), [])
        self.assertEqual(discover.pending_revocations([first, second], revoked | {superseded}, {first["id"]}), [])

    def test_build_artifacts_are_checked_against_the_matrix(self):
        entry, payload = index_entry("ge", "GE-Proton11-7", 1789520806, 1, "a")
        entry["source"]["asset"] = "GE-Proton11-7-aarch64.tar.xz"
        row = {"key": "ge-GE-Proton11-7-aarch64", "flavor": "ge", "tag": "GE-Proton11-7", "asset": "GE-Proton11-7-aarch64.tar.xz", "rev": 1}
        matrix = {"include": [row]}
        artifacts = self.tmp / "artifacts"
        folder = artifacts / "droiddeck-esync-pack-ge-GE-Proton11-7-aarch64"
        folder.mkdir(parents=True)
        (folder / f"{entry['id']}.json").write_text(json.dumps(entry))
        (folder / f"{entry['id']}.tzst").write_bytes(payload)
        self.assertEqual(discover.artifact_packs(matrix, artifacts), [("ge", folder, entry["id"])])
        self.assertEqual(discover.artifact_packs(matrix, self.tmp / "none"), [])
        code, stdout, stderr = run_main(discover.main, ["--flavors", str(self.flavors_file), "packs", "--matrix", json.dumps(matrix), str(artifacts)])
        self.assertEqual(code, 0, stderr)
        self.assertEqual(stdout, f"ge\t{folder}\t{entry['id']}\n")

        cases = (
            ({"include": [dict(row, tag="GE-Proton11-8")]}, None, "is not the pack of ge GE-Proton11-8"),
            ({"include": [dict(row, flavor="cachyos")]}, None, "is not the pack of cachyos"),
            ({"include": [dict(row, rev=2)]}, None, "at rev 2"),
            ({"include": [dict(row, key="ge-GE-Proton11-8-aarch64")]}, None, "is not the artifact of a build in this run"),
            (matrix, lambda: (folder / f"{entry['id']}.tzst").write_bytes(b"tampered"), "is not the asset its entry describes"),
            (matrix, lambda: (folder / "extra.sh").write_text("exit 0\n"), "must hold exactly"),
            (matrix, lambda: (folder / f"{entry['id']}.json").write_text(json.dumps(dict(entry, rev=0))), "rev"),
            (matrix, lambda: (artifacts / "droiddeck-esync-logs-ge-GE-Proton11-7-aarch64").mkdir(), "is not the artifact of a build in this run"),
            ({}, None, "no include list"),
        )
        for case_matrix, change, message in cases:
            shutil.rmtree(artifacts)
            folder.mkdir(parents=True)
            (folder / f"{entry['id']}.json").write_text(json.dumps(entry))
            (folder / f"{entry['id']}.tzst").write_bytes(payload)
            if change:
                change()
            with self.assertRaises(discover.PackError) as raised:
                discover.artifact_packs(case_matrix, artifacts)
            self.assertIn(message, str(raised.exception))

    def candidates(self, failed, ref=""):
        flavors = discover.load_flavors(self.flavors_file)
        stderr = io.StringIO()
        with mock.patch.object(discover.subprocess, "run", side_effect=self.gh), contextlib.redirect_stderr(stderr):
            rows = discover.discover(flavors, "all", ref, self.INDEX, discover.read_revoked(self.revoked_file), failed)
        return [row["key"] for row in rows], stderr.getvalue()

    def test_a_failed_build_waits_for_a_new_rev_or_series(self):
        ge = {"key": "ge-GE-Proton11-7-aarch64", "rev": 1, "series": "valve-11"}
        marker = discover.failure_marker(ge)
        self.assertRegex(marker, r"^ge-GE-Proton11-7-aarch64-r1-[0-9a-f]{12}\.failed$")
        self.assertNotEqual(discover.failure_marker(dict(ge, rev=2)), marker)
        patches = self.tmp / "patches"
        (patches / "valve-11").mkdir(parents=True)
        (patches / "valve-11" / "0001-fallback.patch").write_text("one\n")
        with mock.patch.object(discover, "PATCHES", patches):
            before = discover.failure_marker(ge)
            (patches / "valve-11" / "0001-fallback.patch").write_text("two\n")
            self.assertNotEqual(discover.failure_marker(ge), before)
        with self.assertRaises(discover.PackError):
            discover.failure_marker(dict(ge, series="../valve-11"))

        keys, stderr = self.candidates(frozenset({marker}))
        self.assertEqual(sorted(keys), ["valve-experimental-11.0-20260910b", "valve-proton-11.0-2b"])
        self.assertIn("ge GE-Proton11-7 GE-Proton11-7-aarch64.tar.xz: failed at rev 1 with this patch series", stderr)
        keys, _ = self.candidates(frozenset({discover.failure_marker(dict(ge, rev=2))}))
        self.assertIn("ge-GE-Proton11-7-aarch64", keys)
        keys, _ = self.candidates(frozenset({marker}), ref="GE-Proton11-7")
        self.assertEqual(keys, ["ge-GE-Proton11-7-aarch64"])

    def test_only_recent_failure_markers_count(self):
        now = dt.datetime(2026, 10, 2, 12, 0, tzinfo=dt.timezone.utc)
        base = "repos/Droid-Deck/DroidDeck/releases"
        responses = {
            f"{base}/tags/droiddeck-esync-ge": completed([], stdout=json.dumps({"id": 20, "assets": []})),
            f"{base}/20/assets?per_page=100": completed([], stdout=json.dumps([
                {"id": 1, "name": "ge-a-r1-000000000000.failed", "updated_at": "2026-10-02T01:00:00Z"},
                {"id": 2, "name": "ge-b-r1-000000000000.failed", "updated_at": "2026-09-20T12:00:00Z"},
                {"id": 3, "name": "ge-c-r1-000000000000.failed", "created_at": "2026-10-01T18:00:00Z"},
                {"id": 6, "name": "ge-e-r1-000000000000.failed", "updated_at": "2026-10-01T11:00:00Z"},
                {"id": 4, "name": "ge-d-r1-000000000000.failed", "updated_at": "not a time"},
                {"id": 5, "name": "ge-GE-Proton11-7-1-aaaaaaaaaaaa-r1.json", "updated_at": "2026-10-01T12:00:00Z"}])),
        }

        def run(command, **kwargs):
            if command[-1] in responses:
                return responses[command[-1]]
            return completed(command, 1, stderr="gh: Not Found (HTTP 404)")

        flavors = discover.load_flavors(self.flavors_file)
        with mock.patch.object(discover.subprocess, "run", side_effect=run):
            found = discover.recent_failures("Droid-Deck/DroidDeck", flavors, "all", now)
        self.assertEqual(found, frozenset({"ge-a-r1-000000000000.failed", "ge-c-r1-000000000000.failed"}))

    def test_builds_without_an_artifact_are_reported_as_failed(self):
        rows = [{"key": "ge-GE-Proton11-7-aarch64", "flavor": "ge", "rev": 1, "series": "valve-11"},
                {"key": "valve-proton-11.0-2b", "flavor": "valve", "rev": 1, "series": "valve-11"}]
        artifacts = self.tmp / "artifacts"
        (artifacts / "droiddeck-esync-pack-ge-GE-Proton11-7-aarch64").mkdir(parents=True)
        self.assertEqual(discover.failed_builds({"include": rows}, artifacts), [("valve", discover.failure_marker(rows[1]))])
        self.assertEqual([flavor for flavor, _ in discover.failed_builds({"include": rows}, self.tmp / "none")], ["ge", "valve"])
        code, stdout, stderr = run_main(discover.main, ["--flavors", str(self.flavors_file), "failures", "--matrix",
                                                        json.dumps({"include": rows}), str(artifacts)])
        self.assertEqual(code, 0, stderr)
        self.assertEqual(stdout, f"valve\t{discover.failure_marker(rows[1])}\n")
        with self.assertRaises(discover.PackError):
            discover.failed_builds({}, artifacts)

    STEAM = (
        "Connecting anonymously to Steam Public...\x1b[0mOK\n"
        "\x1b[0mAppID : 4427310, change number : 1/1, last change : Fri Oct  2 22:56:30 2026 \n"
        '"4427310"\n{\n\t"common"\n\t{\n\t\t"name"\t\t"Proton Experimental (ARM64)"\n\t}\n\t"depots"\n\t{\n'
        '\t\t"4427311"\n\t\t{\n\t\t\t"manifests"\n\t\t\t{\n\t\t\t\t"public"\n\t\t\t\t{\n\t\t\t\t\t"gid"\t\t"{gid}"\n'
        '\t\t\t\t}\n\t\t\t}\n\t\t}\n\t\t"branches"\n\t\t{\n\t\t\t"public"\n\t\t\t{\n\t\t\t\t"buildid"\t\t"{build}"\n'
        '\t\t\t\t"timebuildupdated"\t\t"1790839953"\n\t\t\t}\n\t\t\t"beta"\n\t\t\t{\n\t\t\t\t"buildid"\t\t"99"\n\t\t\t}\n'
        '\t\t}\n\t}\n}\n'
        "AppID : 4628740, change number : 2/2, last change : Fri Sep  4 14:18:22 2026 \n"
        '"4628740"\n{\n\t"depots"\n\t{\n\t\t"branches"\n\t\t{\n\t\t\t"public"\n\t\t\t{\n\t\t\t\t"buildid"\t\t"25118360"\n'
        '\t\t\t}\n\t\t}\n\t}\n}\n'
    )

    def steam_text(self, build="25646942", gid="1476754387216214045"):
        return self.STEAM.replace("{build}", build).replace("{gid}", gid)

    def watch(self, *extra):
        argv = ["--flavors", str(self.flavors_file), "watch", "--out", str(self.tmp / "state.json"), *extra]
        with mock.patch.object(discover.subprocess, "run", side_effect=self.gh):
            return run_main(discover.main, argv, {"GITHUB_OUTPUT": str(self.output)})

    def test_steam_app_info(self):
        builds = discover.steam_builds(self.steam_text(), ["4427310", "4628740"])
        self.assertEqual(builds, {"4427310": {"buildid": "25646942", "built": "1790839953", "manifests": ["1476754387216214045"]},
                                  "4628740": {"buildid": "25118360", "built": "", "manifests": []}})
        self.assertEqual(discover.steam_builds(self.steam_text(), ["4628740"]).keys(), {"4628740"})
        self.assertEqual(discover.steam_builds("Connecting anonymously...\nERROR! timed out\n", ["4427310"]), {})
        with self.assertRaises(discover.PackError):
            discover.vdf('"a"\n{\n"b" "c"\n')
        self.assertEqual(discover.steam_apps(discover.load_flavors(self.flavors_file)), ["4427310", "4628740"])

    def test_watch_reports_only_what_changed(self):
        info = self.tmp / "steam.txt"
        info.write_text(self.steam_text())
        code, stdout, stderr = self.watch("--steam-info", str(info))
        self.assertEqual(code, 0, stderr)
        self.assertEqual(stdout.split(), ["ge", "valve"])
        self.assertTrue(self.output.read_text().endswith("changed=ge valve\n"))
        state = json.loads((self.tmp / "state.json").read_text())
        self.assertEqual(state["format"], 1)
        self.assertEqual(state["flavors"]["valve"]["builds"], ["experimental-11.0-20260910b", "experimental-11.0-20260917b",
                                                              "proton-11.0-10", "proton-11.0-2b"])
        self.assertEqual(state["flavors"]["valve"]["steam"]["4427310"]["buildid"], "25646942")
        self.assertEqual(state["flavors"]["ge"]["builds"], ["GE-Proton11-6 GE-Proton11-6-aarch64.tar.gz",
                                                           "GE-Proton11-7 GE-Proton11-7-aarch64.tar.xz"])
        previous = self.tmp / "previous.json"
        shutil.copy(self.tmp / "state.json", previous)
        self.output.write_text("")
        code, stdout, stderr = self.watch("--state", str(previous), "--steam-info", str(info))
        self.assertEqual((code, stdout.split(), self.output.read_text()), (0, [], "changed=\n"), stderr)
        code, stdout, stderr = self.watch("--state", str(previous))
        self.assertEqual((code, stdout.split()), (0, []), stderr)
        self.assertEqual(json.loads((self.tmp / "state.json").read_text())["flavors"]["valve"]["steam"],
                         json.loads(previous.read_text())["flavors"]["valve"]["steam"])
        code, stdout, stderr = self.watch("--state", str(previous), "--steam-info", str(self.tmp / "missing.txt"))
        self.assertEqual((code, stdout.split()), (0, []), stderr)
        self.assertIn("watching the source tags only", stderr)
        info.write_text(self.steam_text(build="25700000", gid="1"))
        code, stdout, stderr = self.watch("--state", str(previous), "--steam-info", str(info))
        self.assertEqual((code, stdout.split()), (0, ["valve"]), stderr)
        self.assertIn("Steam app 4427310 moved from build 25646942 to 25700000", stderr)
        self.RELEASES = [release("GE-Proton11-8", "2026-10-01T00:00:00Z", "GE-Proton11-8-aarch64.tar.gz"), *self.RELEASES]
        code, stdout, stderr = self.watch("--state", str(previous))
        self.assertEqual((code, stdout.split()), (0, ["ge"]), stderr)
        self.assertIn("ge: GE-Proton11-8 GE-Proton11-8-aarch64.tar.gz", stderr)
        previous.write_text("not json")
        code, stdout, stderr = self.watch("--state", str(previous))
        self.assertEqual((code, stdout.split()), (0, ["ge", "valve"]), stderr)

    def test_watch_notices_a_patch_change(self):
        code, _, stderr = self.watch()
        self.assertEqual(code, 0, stderr)
        previous = self.tmp / "previous.json"
        shutil.copy(self.tmp / "state.json", previous)
        with mock.patch.object(discover, "patch_digest", return_value="f" * 64):
            code, stdout, stderr = self.watch("--state", str(previous))
        self.assertEqual((code, stdout.split()), (0, ["ge", "valve"]), stderr)
        self.assertIn("the patch series or rev changed", stderr)

    def test_json_stream(self):
        self.assertEqual(discover.json_values('[1, 2]\n[3]\n'), [[1, 2], [3]])
        self.assertEqual(discover.json_values(' {"a": 1} '), [{"a": 1}])
        self.assertEqual(discover.json_values(""), [])

    def test_natural_order(self):
        tags = ["experimental-11.0-20260910", "experimental-11.0-20260910b", "experimental-11.0-20260903c", "experimental-11.0-20260917"]
        self.assertEqual(sorted(tags, key=discover.natural_key, reverse=True),
                         ["experimental-11.0-20260917", "experimental-11.0-20260910b", "experimental-11.0-20260910", "experimental-11.0-20260903c"])
        self.assertEqual(sorted(["proton-11.0-10", "proton-11.0-2c", "proton-11.0-2"], key=discover.natural_key),
                         ["proton-11.0-2", "proton-11.0-2c", "proton-11.0-10"])

    def test_sdk_image(self):
        makefile = (
            "#   STEAMRT_IMAGE   - Name of the docker image to use for building\n"
            "TARGET_ARCH ?= x86_64\n"
            "REGISTRY := registry.gitlab.steamos.cloud/proton\n"
            "ifeq ($(TARGET_ARCH),x86_64)\n"
            "\tSTEAMRT_IMAGE ?= $(REGISTRY)/steamrt4/sdk/x86_64:4.0.20260331.220802-0\n"
            "else ifeq ($(TARGET_ARCH),arm64)\n"
            "\tSTEAMRT_IMAGE ?= $(REGISTRY)/steamrt4/sdk/arm64-llvm:4.0.20260331.220802-2 # pinned\n"
            "endif\n"
            "\t\t$(DOCKER_OPTS) $(STEAMRT_IMAGE)\n"
        )
        self.assertEqual(discover.sdk_image(makefile), "registry.gitlab.steamos.cloud/proton/steamrt4/sdk/arm64-llvm:4.0.20260331.220802-2")
        for broken in ("STEAMRT_IMAGE ?= $(UNSET)/sdk/arm64-llvm:1\n", "STEAMRT_IMAGE ?= $(shell cat image)-arm64-llvm\n",
                       "STEAMRT_IMAGE ?= registry.example/sdk/x86_64:1\n"):
            with self.assertRaises(discover.PackError):
                discover.sdk_image(broken)
        path = self.tmp / "Makefile.in"
        path.write_text(makefile)
        code, stdout, stderr = run_main(discover.main, ["--flavors", str(self.flavors_file), "sdk-image", str(path)])
        self.assertEqual(code, 0, stderr)
        self.assertEqual(stdout.strip(), "registry.gitlab.steamos.cloud/proton/steamrt4/sdk/arm64-llvm:4.0.20260331.220802-2")

    def test_flavor_files_are_validated(self):
        for change, message in (
            (lambda data: data["flavors"]["ge"].update(keep=0), "keep"),
            (lambda data: data["flavors"]["ge"]["wine"].update(submodules=["wine-staging"]), "wine among them"),
            (lambda data: data["flavors"]["ge"]["wine"].update(clone="deep"), "wine.clone"),
            (lambda data: data["flavors"]["ge"]["wine"].update(prep="../outside.sh"), "wine.prep"),
            (lambda data: data["flavors"]["ge"].update(tag="(unclosed"), "not a regular expression"),
            (lambda data: data["flavors"]["valve"].update(version_token="fixed"), "version_token"),
            (lambda data: data["flavors"]["ge"].pop("build_dir"), "build_dir"),
            (lambda data: data["flavors"]["ge"].update(build_dir="home/build"), "build_dir"),
            (lambda data: data["flavors"]["ge"].update(build_dir="/home/../build"), "build_dir"),
            (lambda data: data["flavors"].update(wine={}), "unknown flavor"),
        ):
            data = json.loads(json.dumps(self.FLAVORS))
            change(data)
            self.flavors_file.write_text(json.dumps(data))
            with self.assertRaises(discover.PackError) as raised:
                discover.load_flavors(self.flavors_file)
            self.assertIn(message, str(raised.exception))


FAKE_GH = """#!/usr/bin/env python3
import os, shutil, sys
state, args = os.environ["FAKE_GH_STATE"], sys.argv[1:]
with open(os.path.join(state, "calls"), "a") as log:
    log.write(" ".join(args) + "\\n")
release = os.path.join(state, args[2]) if len(args) > 2 else ""
if args[:2] == ["release", "view"]:
    if not os.path.isdir(release):
        sys.exit(1)
    if "--json" in args:
        print("\\n".join(sorted(os.listdir(release))))
elif args[:2] == ["release", "create"]:
    os.makedirs(release)
elif args[:2] == ["release", "download"]:
    source = os.path.join(release, args[args.index("-p") + 1])
    if not os.path.isfile(source):
        sys.exit("no assets match the file pattern")
    shutil.copyfile(source, args[args.index("-O") + 1])
elif args[:2] == ["release", "upload"]:
    target = os.path.join(release, os.path.basename(args[5]))
    if os.path.exists(target) and "--clobber" not in args:
        sys.exit("asset under the same name already exists")
    shutil.copyfile(args[5], target)
"""


class PublishStepTest(unittest.TestCase):
    def setUp(self):
        try:
            import yaml
        except ImportError:
            self.skipTest("PyYAML is not installed")
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        workflow = yaml.safe_load((WORKFLOWS / "build-droiddeck-esync-packs.yml").read_text())
        self.script = next(step["run"] for job in workflow["jobs"].values() for step in job.get("steps", [])
                           if step.get("name") == "Upload the packs to their releases")
        (self.tmp / "tools").symlink_to(ROOT / "tools")
        bin_dir = self.tmp / "bin"
        bin_dir.mkdir()
        (bin_dir / "gh").write_text(FAKE_GH)
        (bin_dir / "gh").chmod(0o755)
        self.state = self.tmp / "state"
        self.state.mkdir()
        self.release = self.state / "droiddeck-esync-ge"
        self.entry, self.payload = index_entry("ge", "GE-Proton11-7", 1789520806, 1, "a")
        self.entry["source"]["asset"] = "GE-Proton11-7-aarch64.tar.xz"
        row = {"key": "ge-GE-Proton11-7-aarch64", "flavor": "ge", "tag": "GE-Proton11-7", "asset": "GE-Proton11-7-aarch64.tar.xz", "rev": 1}
        folder = self.tmp / "packs" / f"droiddeck-esync-pack-{row['key']}"
        folder.mkdir(parents=True)
        (folder / f"{self.entry['id']}.json").write_text(json.dumps(self.entry))
        (folder / f"{self.entry['id']}.tzst").write_bytes(self.payload)
        self.env = dict(os.environ, PATH=f"{bin_dir}:{os.environ['PATH']}", FAKE_GH_STATE=str(self.state),
                        MATRIX=json.dumps({"include": [row]}), GH_TOKEN="x", GITHUB_REPOSITORY="Droid-Deck/DroidDeck",
                        GITHUB_STEP_SUMMARY=str(self.tmp / "summary"))

    def publish(self):
        (self.state / "calls").write_text("")
        result = subprocess.run(["bash", "-c", self.script], cwd=self.tmp, env=self.env, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout, (self.state / "calls").read_text().splitlines()

    def asset(self, suffix):
        return (self.release / f"{self.entry['id']}{suffix}").read_bytes()

    def test_a_published_pack_is_never_overwritten_but_an_orphan_is_replaced(self):
        out, calls = self.publish()
        self.assertEqual(self.asset(".tzst"), self.payload)
        self.assertEqual(json.loads(self.asset(".json")), self.entry)
        self.assertTrue(any(call.startswith("release create droiddeck-esync-ge") for call in calls))

        out, calls = self.publish()
        self.assertFalse([call for call in calls if call.startswith("release upload") and call.endswith(".tzst")])
        self.assertEqual(self.asset(".tzst"), self.payload)

        (self.release / f"{self.entry['id']}.tzst").write_bytes(b"published earlier")
        (self.release / f"{self.entry['id']}.json").write_text("{}")
        out, calls = self.publish()
        self.assertIn("is already published with other bytes", out)
        self.assertEqual(self.asset(".tzst"), b"published earlier")
        self.assertEqual(self.asset(".json"), b"{}")
        self.assertFalse([call for call in calls if call.startswith("release upload")])

        (self.release / f"{self.entry['id']}.json").unlink()
        out, calls = self.publish()
        self.assertIn("was published without its entry", out)
        self.assertEqual(self.asset(".tzst"), self.payload)
        self.assertEqual(json.loads(self.asset(".json")), self.entry)


class ShippedFilesTest(unittest.TestCase):
    def test_shipped_flavors(self):
        flavors = discover.load_flavors(ESYNC / "flavors.json")
        self.assertEqual(sorted(flavors["flavors"]), ["cachyos", "ge", "valve"])
        valve = flavors["flavors"]["valve"]
        experimental = valve["families"]["experimental"]["tag"]
        proton = valve["families"]["proton"]["tag"]
        for tag, pattern, wanted in (
            ("experimental-11.0-20260910b", experimental, True), ("experimental-11.0-20260924", experimental, True),
            ("experimental-11.0-20260924-cache", experimental, False), ("experimental-bleeding-edge-11.0-1-2", experimental, False),
            ("proton-11.0-2c", proton, True), ("proton-11.0-10", proton, True), ("proton-11.0-1-beta5", proton, False),
        ):
            self.assertEqual(bool(re.search(pattern, tag)), wanted, tag)
        self.assertEqual({name: cfg["series"] for name, cfg in flavors["flavors"].items()},
                         {"valve": "valve-11", "ge": "valve-11", "cachyos": "cachyos-11"})
        self.assertEqual({name: cfg["source_match"] for name, cfg in flavors["flavors"].items()},
                         {"valve": True, "ge": False, "cachyos": False})
        self.assertEqual({name: cfg["build_dir"] for name, cfg in flavors["flavors"].items()},
                         {"valve": "/builds/proton/proton/build-dir", "ge": "/home/tcrider/build",
                          "cachyos": "/home/runner/work/proton-cachyos/proton-cachyos/build"})
        self.assertEqual({name: family["depot_app"] for name, family in valve["families"].items()},
                         {"experimental": "4427310", "proton": "4628740"})

    def test_index_key_is_the_one_the_app_trusts(self):
        lines = (ESYNC / "index-key.pub").read_text().splitlines()
        self.assertEqual((lines[0], lines[-1]), ("-----BEGIN PUBLIC KEY-----", "-----END PUBLIC KEY-----"))
        self.assertEqual(base64.b64encode(base64.b64decode("".join(lines[1:-1]))).decode(), SPEC_KEY)

    def test_revocation_list_parses(self):
        self.assertIsInstance(make_pack.read_revoked(ESYNC / "revoked.txt"), set)

    def test_workflows(self):
        try:
            import yaml
        except ImportError:
            self.skipTest("PyYAML is not installed")
        packs = yaml.safe_load((WORKFLOWS / "build-droiddeck-esync-packs.yml").read_text())
        triggers = packs.get("on", packs.get(True))
        self.assertEqual(triggers["schedule"], [{"cron": "17 5 * * *"}])
        self.assertEqual(triggers["push"]["branches"], ["main"])
        self.assertEqual(set(triggers["workflow_dispatch"]["inputs"]), {"flavor", "ref", "publish"})
        self.assertEqual(packs["permissions"], {"contents": "write"})
        self.assertEqual(packs["concurrency"]["cancel-in-progress"], False)
        group = packs["concurrency"]["group"]
        self.assertTrue(group.startswith("droiddeck-esync-packs-${{"), group)
        self.assertIn("&& 'publish' || format('build-{0}', github.run_id)", group)
        self.assertIn("github.ref == 'refs/heads/main'", group)
        self.assertEqual(packs["env"]["SYNC_BASE_URL"], "https://github.com/Droid-Deck/DroidDeck/releases/download")
        jobs = packs["jobs"]
        self.assertIn("github.repository == 'Droid-Deck/DroidDeck'", jobs["discover"]["if"])
        publish = jobs["discover"]["steps"][-1]["env"]["PUBLISH"]
        self.assertIn("github.repository == 'Droid-Deck/DroidDeck'", publish)
        self.assertIn("github.ref == 'refs/heads/main'", publish)
        self.assertEqual(jobs["build"]["runs-on"], "ubuntu-24.04-arm")
        self.assertEqual(jobs["build"]["timeout-minutes"], 360)
        self.assertEqual((jobs["build"]["strategy"]["fail-fast"], jobs["build"]["strategy"]["max-parallel"]), (False, 2))
        for name in ("discover", "build"):
            self.assertEqual(jobs[name]["permissions"], {"contents": "read"})
        for name in ("build", "publish"):
            checkout = next(step for step in jobs[name]["steps"] if str(step.get("uses", "")).startswith("actions/checkout@"))
            self.assertIs(checkout["with"]["persist-credentials"], False)
        build_text = json.dumps(jobs["build"])
        self.assertNotIn("gh release upload", build_text)
        self.assertNotIn("gh release create", build_text)
        self.assertIn("DEPOT_DOWNLOADER_SHA256", build_text)
        self.assertIn("sha256sum -c -", build_text)
        self.assertEqual(jobs["publish"]["needs"], ["discover", "build"])
        self.assertNotIn("permissions", jobs["publish"])
        self.assertIn("discover.py packs", json.dumps(jobs["publish"]))
        self.assertEqual(jobs["index"]["environment"], "droiddeck-esync-signing")
        self.assertEqual(jobs["index"]["needs"], ["discover", "build", "publish"])
        index_text = json.dumps(jobs["index"])
        self.assertIn("--previous-sig", index_text)
        self.assertNotIn("GITHUB_SERVER_URL", index_text)
        self.assertNotIn("grep -qxF", index_text)
        self.assertIn("needs.discover.outputs.unindexed != '0'", jobs["index"]["if"])
        self.assertIn("needs.discover.outputs.revoke != '0'", jobs["index"]["if"])
        self.assertEqual(jobs["discover"]["outputs"]["revoke"], "${{ steps.discover.outputs.revoke }}")
        self.assertIn("needs.discover.outputs.count != '0'", jobs["index"]["if"])
        steps = {step.get("name"): step for step in jobs["build"]["steps"]}
        smoke = steps["Smoke-test the pack on the release it was made for"]
        self.assertEqual(smoke["if"], "matrix.asset != ''")
        self.assertIn("tools/droiddeck-esync/smoke-test.sh", smoke["run"])
        self.assertIn("3) echo \"::warning::", smoke["run"])
        watch = yaml.safe_load((WORKFLOWS / "watch-proton-releases.yml").read_text())
        triggers = watch.get("on", watch.get(True))
        self.assertEqual(triggers["schedule"], [{"cron": "*/20 * * * *"}])
        self.assertIn("workflow_dispatch", triggers)
        self.assertEqual(watch["permissions"], {"contents": "write", "actions": "write"})
        self.assertEqual(watch["concurrency"], {"group": "watch-proton-releases", "cancel-in-progress": False})
        job = watch["jobs"]["watch"]
        self.assertIn("github.repository == 'Droid-Deck/DroidDeck'", job["if"])
        steps = {step.get("name"): step for step in job["steps"]}
        self.assertIn("+login anonymous", steps["Read the Proton builds Steam ships"]["run"])
        self.assertIn("discover.py steam-apps", steps["Read the Proton builds Steam ships"]["run"])
        self.assertIn("discover.py watch", steps["Compare with the last run"]["run"])
        start = steps["Start the pack builds"]
        self.assertEqual(start["if"], "steps.watch.outputs.changed != ''")
        self.assertIn("gh workflow run build-droiddeck-esync-packs.yml", start["run"])
        self.assertIn("-f publish=true", start["run"])
        self.assertIn("-f flavor=all", start["run"])
        names = [step.get("name") for step in job["steps"]]
        self.assertLess(names.index("Start the pack builds"), names.index("Save the watch state"))
        self.assertIn("watch-state.json --clobber", steps["Save the watch state"]["run"])
        apk = yaml.safe_load((WORKFLOWS / "build.yml").read_text())
        steps = {step.get("name"): step for step in apk["jobs"]["build"]["steps"]}
        bundle = steps["Bundle the droiddeck-esync packs"]
        self.assertNotIn("if", bundle)
        self.assertIn("tools/droiddeck-esync/release.env", bundle["run"])
        self.assertIn("-R Droid-Deck/DroidDeck", bundle["run"])
        self.assertIn("sha256sum -c -", bundle["run"])
        stage = next(step for step in apk["jobs"]["build"]["steps"] if "overlay/usr/local/bin/droiddeck-*" in step.get("run", ""))
        self.assertTrue((ROOT / "tools/linuxfs/overlay/usr/local/bin/droiddeck-esync").is_file())
        self.assertIn("tools/linuxfs/overlay/usr/local/bin/droiddeck-* ", stage["run"])
        self.assertIn("tools/linuxfs/overlay/usr/local/bin/droiddeck-* ", (ROOT / "tools/build_local.sh").read_text())


if __name__ == "__main__":
    unittest.main()
