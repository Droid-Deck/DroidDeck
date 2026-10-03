import contextlib
import hashlib
import io
import json
import multiprocessing
import os
from pathlib import Path
import runpy
import shutil
import stat
import struct
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
SYNC = runpy.run_path(str(BIN / "droiddeck-esync"))
COMPAT = runpy.run_path(str(BIN / "steam-compatibility"))
GLOBALS = SYNC["select"].__globals__
NTDLL = SYNC["NTDLL"]
WINESERVER = SYNC["WINESERVER"]
COPY = list(SYNC["DEFAULT_COPY"])
REAL_DIRS = SYNC["REAL_DIRS"]
RECORD = SYNC["RECORD"]
VALVE_LINE = "1789159687 experimental-11.0-20260910b-arm64"
GE_LINE = "1789520806 GE-Proton11-7"
EXPORTS = ("NtClose", "NtCreateEvent", "wine_server_call")
WATCHED = ("WINESERVER", "BL_SYNC_PACK", "WINEESYNC", "PROTON_NO_ESYNC", "BL_SYNC", "CUSTOM")
PROTON = ("#!/usr/bin/python3\nimport json, os, sys\n"
          "print(json.dumps([sys.argv[0], sys.argv[1:], {k: os.environ.get(k) for k in %r}]))\n" % (WATCHED,))
MANIFEST = '"manifest" { "commandline" "/proton %verb%" }'


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def elf(exported=(), weak=(), local=(), undefined=(), salt=b""):
    strings = b"\0"
    offsets = {}
    for name in list(exported) + list(weak) + list(local) + list(undefined):
        if name not in offsets:
            offsets[name] = len(strings)
            strings += name.encode() + b"\0"
    symbols = [bytes(24)]
    for group, binding, shndx in ((exported, 1, 7), (weak, 2, 7), (local, 0, 7), (undefined, 1, 0)):
        symbols += [struct.pack("<IBBHQQ", offsets[name], binding << 4 | 2, 0, shndx, 0, 0) for name in group]
    dynsym = b"".join(symbols)
    body = dynsym + strings
    body += bytes(-len(body) % 8)
    header = b"\x7fELF" + bytes((2, 1, 1)) + bytes(9) + struct.pack(
        "<HHIQQQIHHHHHH", 3, 183, 1, 0, 0, 64 + len(body), 0, 64, 0, 0, 64, 3, 0)
    sections = (bytes(64) + struct.pack("<IIQQQQIIQQ", 0, 11, 2, 0, 64, len(dynsym), 2, 1, 8, 24)
                + struct.pack("<IIQQQQIIQQ", 0, 3, 2, 0, 64 + len(dynsym), len(strings), 0, 0, 1, 0))
    return header + body + sections + salt


def make_tool(parent, name, line=VALVE_LINE, salt=b"", builtin=False, ge=False, decorated=False, fsync=False, ntsync=True):
    tool = Path(parent) / name
    for folder in ("files/lib/wine/aarch64-unix", "files/lib/wine/aarch64-windows", "files/bin-arm64", "files/share/wine"):
        (tool / folder).mkdir(parents=True, exist_ok=True)
    (tool / NTDLL).write_bytes(elf(EXPORTS, salt=salt))
    (tool / WINESERVER).write_bytes(b"wineserver " + salt + (b" esync: up and running" if builtin else b"")
                                    + (b" fsync: up and running" if fsync else b"") + (b" /dev/ntsync" if ntsync else b""))
    for rel in COPY:
        (tool / rel).write_bytes(b"stock " + rel.encode() + salt)
    (tool / "files/lib/wine/aarch64-unix/winevulkan.so").write_bytes(b"vulkan")
    (tool / "files/lib/wine/aarch64-windows/ntdll.dll").write_bytes(b"pe")
    os.symlink("wine", tool / "files/bin-arm64/msidb")
    if ge:
        os.symlink("bin-arm64", tool / "files/bin")
    (tool / "version").write_text(line + "\n")
    (tool / "proton").write_text(PROTON)
    (tool / "proton").chmod(0o755)
    if decorated:
        (tool / "toolmanifest.vdf").write_text(MANIFEST)
        (tool / "toolmanifest.vdf.bannerlator-orig").write_text(MANIFEST)
        (tool / "compatibilitytool.vdf").write_text("compat")
        (tool / "bannerlator-proton-wrap").write_text("wrap")
    return tool


def make_pack(root, tool, pack_id, rev=1, source_match=False, stock=None, version=None, exports=None, complete=True, **extra):
    tool = Path(tool)
    pack_dir = Path(root) / "packs" / pack_id
    (pack_dir / "files/lib/wine/aarch64-unix").mkdir(parents=True)
    (pack_dir / "files/bin-arm64").mkdir(parents=True)
    (pack_dir / NTDLL).write_bytes(b"patched ntdll " + pack_id.encode())
    (pack_dir / WINESERVER).write_bytes(b"patched wineserver " + pack_id.encode())
    line = (tool / "version").read_text().strip()
    data = {
        "format": 1, "id": pack_id, "flavor": "valve", "rev": rev, "version": version or line.split()[1],
        "version_line": line, "stock": stock or {NTDLL: sha(tool / NTDLL), WINESERVER: sha(tool / WINESERVER)},
        "exports": exports or SYNC["exports_hash"](tool / NTDLL), "source_match": source_match,
        "files": {NTDLL: sha(pack_dir / NTDLL), WINESERVER: sha(pack_dir / WINESERVER)}, "copy": COPY,
        "protocol": 931, "source": {"repo": "r", "ref": "t", "commit": "c", "wine_commit": "w", "sdk_image": "i"},
        "patch": {"series": "valve-11", "sha256": "0" * 64},
    }
    data.update(extra)
    (pack_dir / "pack.json").write_text(json.dumps(data))
    if complete:
        (pack_dir / ".complete").write_text("")
    return data


def files_under(path):
    return sorted(str(Path(folder, name).relative_to(path)) for folder, dirs, names in os.walk(path) for name in dirs + names)


def concurrent_worker(barrier, queue, root, tool, pack, steam):
    barrier.wait()
    try:
        with contextlib.redirect_stderr(io.StringIO()):
            path = SYNC["ensure_dist"](root, tool, pack)
            SYNC["reconcile"](steam, {"HOME": str(Path(root).parents[2]), "BL_SYNC_FALLBACK": "1"})
        queue.put(path)
    except Exception as error:
        queue.put("error: %r" % error)


class SyncTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.home = self.tmp / "home"
        self.home.mkdir()
        self.root = self.home / ".local/share/droiddeck-esync"
        self.tools = self.tmp / "tools"
        self.tools.mkdir()

    def store(self):
        self.root.mkdir(parents=True, exist_ok=True)
        return self.root

    def env(self, **extra):
        env = {"HOME": str(self.home), "STEAM_COMPAT_DATA_PATH": "/compatdata/42", "BL_SYNC_FALLBACK": "1", "BL_SYNC_MIN_NOFILE": "1"}
        env.update(extra)
        return {name: value for name, value in env.items() if value is not None}

    def select(self, tool, env, verb="waitforexitandrun"):
        command = [str(Path(tool) / "proton"), verb, "game.exe"]
        output = io.StringIO()
        with contextlib.redirect_stderr(output):
            chosen, chosen_env = SYNC["select"](command, env)
        return chosen, chosen_env, output.getvalue()

    def dist_of(self, pack_id, tool):
        return self.root / "dist" / SYNC["dist_name"](pack_id, os.path.realpath(tool))

    def quiet(self, function, *args):
        with contextlib.redirect_stderr(io.StringIO()):
            return function(*args)


class ExportsHashTest(SyncTestCase):
    def test_keeps_defined_global_and_weak_names(self):
        path = self.tmp / "lib.so"
        path.write_bytes(elf(("beta", "alpha", "beta"), weak=("weak",), local=("hidden",), undefined=("imported",), salt=b"x"))
        self.assertEqual(SYNC["exports_hash"](path), hashlib.sha256(b"alpha\nbeta\nweak").hexdigest())
        other = self.tmp / "other.so"
        other.write_bytes(elf(("alpha", "beta"), weak=("weak",), salt=b"different"))
        self.assertEqual(SYNC["exports_hash"](other), SYNC["exports_hash"](path))
        for data in (b"not an elf", elf(("alpha",))[:80], b"\x7fELF\x01\x01" + bytes(60)):
            path.write_bytes(data)
            with self.assertRaises(ValueError):
                SYNC["exports_hash"](path)

    @unittest.skipUnless(shutil.which("cc") and shutil.which("nm"), "needs cc and nm")
    def test_matches_nm_on_a_compiled_library(self):
        source = self.tmp / "lib.c"
        source.write_text("int alpha(void) { return 1; }\n__attribute__((weak)) int beta(void) { return 2; }\n"
                          "static int gamma_(void) { return 3; }\nint delta = 4;\nint use(void) { return gamma_(); }\n"
                          "extern int outside(void);\nint call(void) { return outside(); }\n")
        library = self.tmp / "lib.so"
        if subprocess.run(["cc", "-shared", "-fPIC", "-o", str(library), str(source)], capture_output=True).returncode:
            self.skipTest("cc cannot build a shared library here")
        listing = subprocess.run(["nm", "-D", "--defined-only", str(library)], capture_output=True, text=True, check=True).stdout
        names = {fields[2].split("@")[0] for fields in (line.split() for line in listing.splitlines())
                 if len(fields) == 3 and fields[1].isupper()}
        self.assertIn("alpha", names)
        self.assertNotIn("outside", names)
        self.assertEqual(SYNC["exports_hash"](library), hashlib.sha256("\n".join(sorted(names)).encode()).hexdigest())


class ShadowTest(SyncTestCase):
    def test_layout_links_and_real_files(self):
        root = self.store()
        tool = make_tool(self.tools, "GE-Proton11-7", GE_LINE, ge=True, decorated=True)
        os.symlink(self.tools, self.tmp / "launch")
        launched = self.tmp / "launch" / "GE-Proton11-7"
        pack = make_pack(root, tool, "ge-GE-Proton11-7-1789520806-abcdefabcdef-r1")
        dist = Path(SYNC["ensure_dist"](root, launched, pack))
        self.assertEqual(dist, self.dist_of(pack["id"], tool))
        self.assertEqual(dist.name, "%s~%s" % (pack["id"], hashlib.sha256(str(tool.resolve()).encode()).hexdigest()[:8]))
        for folder in REAL_DIRS:
            self.assertTrue((dist / folder).is_dir() and not (dist / folder).is_symlink(), folder)
        real_files = {NTDLL, WINESERVER, *COPY, RECORD}
        for rel in files_under(dist):
            if rel not in REAL_DIRS and rel not in real_files:
                self.assertTrue((dist / rel).is_symlink(), rel)
        self.assertEqual(os.readlink(dist / "files/bin"), "bin-arm64")
        self.assertEqual(os.readlink(dist / "files/bin-arm64/msidb"), "wine")
        for rel in ("proton", "version", "files/share", "files/lib/wine/aarch64-windows", "files/lib/wine/aarch64-unix/winevulkan.so"):
            self.assertEqual(os.readlink(dist / rel), str(launched / rel))
        for rel in ("dist.lock", "files/steampipe_fixups_mtime"):
            self.assertEqual(os.readlink(dist / rel), str(launched / rel))
            self.assertFalse((tool / rel).exists())
        for name in ("toolmanifest.vdf", "compatibilitytool.vdf", "bannerlator-proton-wrap", "toolmanifest.vdf.bannerlator-orig"):
            self.assertFalse(os.path.lexists(dist / name), name)
        pack_dir = root / "packs" / pack["id"]
        for rel in (NTDLL, WINESERVER):
            self.assertEqual((dist / rel).read_bytes(), (pack_dir / rel).read_bytes())
        for rel in COPY:
            self.assertEqual((dist / rel).read_bytes(), (tool / rel).read_bytes())
        for rel in (NTDLL, WINESERVER, *COPY):
            info = os.lstat(dist / rel)
            self.assertTrue(stat.S_ISREG(info.st_mode), rel)
            self.assertEqual(stat.S_IMODE(info.st_mode), 0o755, rel)
            self.assertEqual(info.st_nlink, 1, rel)
        self.assertEqual((dist / "files/bin/wineserver").read_bytes(), (pack_dir / WINESERVER).read_bytes())
        self.assertEqual((dist / "files/bin-arm64/msidb").resolve(), (dist / "files/bin-arm64/wine").resolve())
        record = json.loads((dist / RECORD).read_text())
        self.assertEqual(record["pack"], pack["id"])
        self.assertEqual(record["stock"], str(tool.resolve()))
        self.assertEqual(record["tool"], str(launched))
        self.assertEqual(record["version"], GE_LINE)
        self.assertEqual(sorted(record["copies"]), sorted(COPY))
        for rel in COPY:
            info = os.stat(tool / rel)
            self.assertEqual(record["copies"][rel], [info.st_size, info.st_mtime_ns, sha(tool / rel)])
        inodes = {rel: os.lstat(dist / rel).st_ino for rel in (NTDLL, WINESERVER, *COPY)}
        self.assertEqual(Path(SYNC["ensure_dist"](root, tool, pack)), dist)
        self.assertEqual(inodes, {rel: os.lstat(dist / rel).st_ino for rel in inodes})
        self.assertEqual(os.readlink(dist / "proton"), str(launched / "proton"))
        self.assertEqual([name for name in os.listdir(root / "dist") if ".tmp-" in name], [])

    def test_reconcile_follows_stock_changes(self):
        root = self.store()
        tool = make_tool(self.tools, "Proton Experimental (ARM64)")
        pack = make_pack(root, tool, "valve-experimental-r1")
        dist = Path(SYNC["ensure_dist"](root, tool, pack))
        unix_wine = "files/lib/wine/aarch64-unix/wine"
        preloader = "files/lib/wine/aarch64-unix/wine-preloader"
        (tool / "files/new.txt").write_text("new")
        (tool / "files/lib/wine/aarch64-unix/new.so").write_text("new")
        (tool / "files/lib/wine/aarch64-unix/winevulkan.so").unlink()
        (dist / "files/share").unlink()
        os.symlink("/nonexistent", dist / "files/share")
        (dist / "files/stray").symlink_to("/nowhere")
        for rel in (NTDLL, WINESERVER, "files/bin-arm64/wine"):
            os.chmod(dist / rel, 0o444)
        os.chmod(dist / unix_wine, 0o555)
        kept = os.lstat(dist / unix_wine).st_ino
        (tool / "files/bin-arm64/wine").write_bytes(b"an updated stock wine loader")
        (tool / preloader).unlink()
        self.assertEqual(Path(SYNC["ensure_dist"](root, tool, pack)), dist)
        self.assertEqual(os.readlink(dist / "files/new.txt"), str(tool / "files/new.txt"))
        self.assertEqual(os.readlink(dist / "files/lib/wine/aarch64-unix/new.so"), str(tool / "files/lib/wine/aarch64-unix/new.so"))
        self.assertFalse(os.path.lexists(dist / "files/lib/wine/aarch64-unix/winevulkan.so"))
        self.assertFalse(os.path.lexists(dist / "files/stray"))
        self.assertEqual(os.readlink(dist / "files/share"), str(tool / "files/share"))
        self.assertEqual((dist / "files/bin-arm64/wine").read_bytes(), b"an updated stock wine loader")
        for rel in (NTDLL, WINESERVER, "files/bin-arm64/wine"):
            self.assertEqual(stat.S_IMODE(os.lstat(dist / rel).st_mode), 0o755, rel)
        self.assertEqual((dist / NTDLL).read_bytes(), (root / "packs" / pack["id"] / NTDLL).read_bytes())
        self.assertEqual(os.lstat(dist / unix_wine).st_ino, kept)
        self.assertTrue((dist / preloader).is_file() and not (dist / preloader).is_symlink())
        record = json.loads((dist / RECORD).read_text())
        self.assertEqual(record["copies"]["files/bin-arm64/wine"][2], sha(tool / "files/bin-arm64/wine"))
        self.assertIn(preloader, record["copies"])

    def test_tampered_pack_falls_back_to_stock(self):
        root = self.store()
        tool = make_tool(self.tools, "Proton Experimental (ARM64)")
        pack = make_pack(root, tool, "valve-experimental-r1")
        (root / "packs" / pack["id"] / NTDLL).write_bytes(b"tampered")
        command, env, said = self.select(tool, self.env())
        self.assertEqual(command[0], str(tool / "proton"))
        self.assertNotIn("WINESERVER", env)
        self.assertIn("unusable", said)
        self.assertIn(f"pack {pack['id']} is damaged; the app fetches it again", said)
        self.assertFalse((root / "packs" / pack["id"] / ".complete").exists())
        self.assertEqual([name for name in os.listdir(root / "dist") if not name.endswith(".lock")], [])
        command, env, said = self.select(tool, self.env())
        self.assertEqual(command[0], str(tool / "proton"))
        self.assertIn("no pack for Proton Experimental (ARM64)", said)
        self.assertIn(pack["stock"][NTDLL], (root / "wanted.tsv").read_text())

    def test_a_missing_tool_file_does_not_condemn_the_pack(self):
        root = self.store()
        tool = make_tool(self.tools, "Proton Experimental (ARM64)")
        pack = make_pack(root, tool, "valve-experimental-r1")
        with mock.patch.dict(GLOBALS, {"ensure_dist": mock.Mock(side_effect=OSError("disk full"))}):
            command, _, said = self.select(tool, self.env())
        self.assertEqual(command[0], str(tool / "proton"))
        self.assertIn("unusable", said)
        self.assertNotIn("damaged", said)
        self.assertTrue((root / "packs" / pack["id"] / ".complete").exists())

    def test_concurrent_ensure_dist_builds_one_dist(self):
        root = self.store()
        steam = self.tmp / "steam"
        tool = make_tool(steam / "steamapps/common", "Proton Experimental (ARM64)")
        pack = make_pack(root, tool, "valve-experimental-r1")
        context = multiprocessing.get_context("fork")
        barrier = context.Barrier(4)
        queue = context.Queue()
        workers = [context.Process(target=concurrent_worker, args=(barrier, queue, str(root), str(tool), pack, str(steam)))
                   for _ in range(4)]
        with mock.patch.dict(GLOBALS, {"SD_LIBRARY": str(self.tmp / "sd")}):
            for worker in workers:
                worker.start()
            results = [queue.get(timeout=60) for _ in workers]
            for worker in workers:
                worker.join(60)
        dist = self.dist_of(pack["id"], tool)
        self.assertEqual(results, [str(dist)] * 4)
        self.assertEqual(sorted(os.listdir(root / "dist")), sorted([dist.name, dist.name + ".lock"]))
        record = json.loads((dist / RECORD).read_text())
        self.assertEqual(record["pack"], pack["id"])
        for rel in (NTDLL, WINESERVER):
            self.assertEqual(sha(dist / rel), pack["files"][rel])
        for rel in COPY:
            self.assertEqual(sha(dist / rel), sha(tool / rel))
        self.assertEqual((root / "tools.tsv").read_text().count("\n"), 1)
        self.assertFalse([name for name in os.listdir(root) if ".tmp-" in name])

    def test_build_shadow_command_line(self):
        root = self.store()
        tool = make_tool(self.tools, "proton-cachyos-11", "1790000000 cachyos-11.0-1")
        pack = make_pack(root, tool, "cachyos-11-r1")
        out = self.tmp / "shadow" / "dist"
        for _ in range(2):
            result = subprocess.run([sys.executable, str(BIN / "droiddeck-esync"), "build-shadow", str(tool),
                                     str(root / "packs" / pack["id"]), str(out)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.strip(), str(out))
        self.assertEqual(os.listdir(out.parent), ["dist"])
        self.assertEqual(json.loads((out / RECORD).read_text())["pack"], pack["id"])
        self.assertEqual(os.readlink(out / "proton"), str(tool / "proton"))
        status = subprocess.run([sys.executable, str(BIN / "droiddeck-esync"), "status"], capture_output=True, text=True,
                                env={**os.environ, "HOME": str(self.home)}, check=True).stdout
        self.assertIn("packs: 1 installed", status)
        usage = subprocess.run([sys.executable, str(BIN / "droiddeck-esync"), "build-shadow"], capture_output=True, text=True)
        self.assertEqual(usage.returncode, 2)


class SelectTest(SyncTestCase):
    def test_store_missing_is_inert(self):
        tool = make_tool(self.tools, "Proton Experimental (ARM64)")
        before = files_under(self.tmp)
        env = self.env()
        command, chosen_env, said = self.select(tool, env)
        self.assertEqual((command, chosen_env, said), ([str(tool / "proton"), "waitforexitandrun", "game.exe"], env, ""))
        command, chosen_env, said = self.select(tool, self.env(PROTON_NO_NTSYNC="1", BL_SYNC="1"))
        self.assertEqual(chosen_env, self.env(PROTON_NO_NTSYNC="1", BL_SYNC="0"))
        self.assertIsNone(self.quiet(SYNC["reconcile"], str(self.tmp / "steam"), env))
        self.assertEqual(SYNC["gc"](None), 0)
        self.assertIsNone(SYNC["store_root"](env))
        self.assertEqual(files_under(self.tmp), before)

    def test_probe_prefixes_and_other_tools_are_left_alone(self):
        root = self.store()
        tool = make_tool(self.tools, "Proton Experimental (ARM64)")
        make_pack(root, tool, "valve-experimental-r1")
        for prefix in ("", "/steam/compatdata/0", "/steam/compatdata/0/", "/steam/compatdata/0-1234"):
            env = self.env(STEAM_COMPAT_DATA_PATH=prefix, PROTON_NO_NTSYNC="1")
            self.assertEqual(self.select(tool, env), ([str(tool / "proton"), "waitforexitandrun", "game.exe"], env, ""))
        env = self.env(PROTON_USE_ARM64="0")
        self.assertEqual(self.select(tool, env)[:2], ([str(tool / "proton"), "waitforexitandrun", "game.exe"], env))
        other = self.tmp / "not-proton"
        other.mkdir()
        self.assertEqual(self.select(other, self.env())[1], self.env())
        self.assertFalse((root / "dist").exists())
        self.assertFalse((root / "launches.log").exists())

    def test_fallback_switch_off(self):
        root = self.store()
        tool = make_tool(self.tools, "Proton Experimental (ARM64)")
        make_pack(root, tool, "valve-experimental-r1")
        for value in ("0", None, "yes"):
            env = self.env(BL_SYNC_FALLBACK=value)
            command, chosen_env, said = self.select(tool, env)
            self.assertEqual((command[0], chosen_env), (str(tool / "proton"), SYNC["wineserver_only"](env)))
            self.assertEqual(said, "droiddeck-esync: off; wineserver sync only\n")
        self.assertFalse((root / "dist").exists())
        self.assertFalse((root / "wanted.tsv").exists())
        self.assertEqual([line.split("\t")[4] for line in (root / "launches.log").read_text().splitlines()], ["-"] * 3)

    def test_pack_is_used_and_logged(self):
        root = self.store()
        tool = make_tool(self.tools, "Proton Experimental (ARM64)")
        pack = make_pack(root, tool, "valve-experimental-r1")
        dist = self.dist_of(pack["id"], tool)
        command, env, said = self.select(tool, self.env(BL_SYNC="1"))
        self.assertEqual(command, [str(dist / "proton"), "waitforexitandrun", "game.exe"])
        self.assertEqual(env["WINESERVER"], str(dist / WINESERVER))
        self.assertEqual(env["BL_SYNC_PACK"], pack["id"])
        self.assertEqual(env["WINEESYNC"], "1")
        self.assertNotIn("PROTON_NO_ESYNC", env)
        self.assertTrue(said.startswith("droiddeck-esync: pack %s for Proton Experimental (ARM64) (%s, exact match); "
                                        "droiddeck-ntsync on, droiddeck-esync on, fds " % (pack["id"], VALVE_LINE)), said)
        self.assertEqual(said.count("\n"), 1)
        fields = (root / "launches.log").read_text().splitlines()[-1].split("\t")
        self.assertEqual(fields[1:7], ["42", "waitforexitandrun", str(tool), pack["id"], "1", "1"])
        self.assertRegex(fields[7], r"^(\d+|unlimited)/(\d+|unlimited)$")
        command, env, said = self.select(tool, self.env(WINEESYNC="2"))
        self.assertEqual((env["WINEESYNC"], command[0]), ("2", str(dist / "proton")))
        self.assertIn("droiddeck-ntsync off", said)
        for _ in range(SYNC["LOG_LINES"] + 5):
            SYNC["log_launch"](str(root), command, env, str(tool), None)
        self.assertEqual(len((root / "launches.log").read_text().splitlines()), SYNC["LOG_LINES"])

    def test_fallback_switched_off_per_game(self):
        root = self.store()
        tool = make_tool(self.tools, "Proton Experimental (ARM64)")
        pack = make_pack(root, tool, "valve-experimental-r1")
        cases = ({"PROTON_NO_ESYNC": "1"}, {"STEAM_COMPAT_APP_ID": "2630"}, {"SteamAppId": "1060210"},
                 {"STEAM_COMPAT_DATA_PATH": "/steam/compatdata/414740"}, {"STEAM_COMPAT_APP_ID": "201510", "WINEESYNC": "1"},
                 {"STEAM_COMPAT_DATA_PATH": "/steam/compatdata/1233880/"}, {"WINEESYNC": "0"},
                 {"WINEESYNC": "0", "PROTON_NO_ESYNC": "0"})
        for extra in cases:
            command, env, _ = self.select(tool, self.env(**extra))
            self.assertEqual((env["WINEESYNC"], env["PROTON_NO_ESYNC"], env["BL_SYNC_PACK"]), ("0", "1", pack["id"]), extra)
            self.assertNotIn("PROTON_NO_FSYNC", env, extra)
            self.assertEqual(command[0], str(self.dist_of(pack["id"], tool) / "proton"))
        for extra in ({"PROTON_NO_ESYNC": "0"}, {"PROTON_NO_ESYNC": ""}, {"STEAM_COMPAT_APP_ID": "42", "SteamAppId": "2630"},
                      {"STEAM_COMPAT_APP_ID": "2630", "PROTON_NO_ESYNC": "0"}, {"STEAM_COMPAT_APP_ID": "2630", "PROTON_NO_ESYNC": ""}):
            env = self.select(tool, self.env(**extra))[1]
            self.assertEqual((env["WINEESYNC"], env["PROTON_NO_FSYNC"]), ("1", "1"), extra)

    def test_fallback_comes_before_fsync(self):
        root = self.store()
        tool = make_tool(self.tools, "Proton Experimental (ARM64)")
        make_pack(root, tool, "valve-experimental-r1")
        for extra, expected in (({}, "1"), ({"WINEESYNC": "2"}, "1"), ({"PROTON_NO_FSYNC": "0"}, "0"),
                                ({"PROTON_NO_FSYNC": ""}, ""), ({"PROTON_NO_FSYNC": "1"}, "1")):
            env = self.select(tool, self.env(**extra))[1]
            self.assertEqual(env.get("PROTON_NO_FSYNC"), expected, extra)
        stock = self.select(tool, self.env(BL_SYNC_FALLBACK="0"))[1]
        self.assertEqual((stock["PROTON_NO_FSYNC"], stock["WINEFSYNC"]), ("1", "0"))

    def test_open_file_limit_threshold(self):
        root = self.store()
        tool = make_tool(self.tools, "Proton Experimental (ARM64)")
        make_pack(root, tool, "valve-experimental-r1")
        cases = (((1024, 4096), None, "0"), ((1024, 4096), "4096", "1"), ((1024, 4096), "4097", "0"),
                 ((1024, 4096), "junk", "0"), ((1024, 8192), None, "1"), ((1024, -1), "999999", "1"))
        for limits, minimum, expected in cases:
            with mock.patch("resource.getrlimit", return_value=limits):
                command, env, said = self.select(tool, self.env(BL_SYNC_MIN_NOFILE=minimum))
            self.assertEqual(env["WINEESYNC"], expected, (limits, minimum))
        self.assertIn("fds 1024/unlimited", said)

    def test_proton_no_ntsync_turns_emulation_off(self):
        tool = make_tool(self.tools, "Proton Experimental (ARM64)")
        self.assertEqual(self.select(tool, self.env(PROTON_NO_NTSYNC="1", BL_SYNC="1"))[1]["BL_SYNC"], "0")
        self.assertEqual(self.select(tool, self.env(PROTON_NO_NTSYNC="1"))[1]["BL_SYNC"], "0")
        self.assertEqual(self.select(tool, self.env(PROTON_NO_NTSYNC="0", BL_SYNC="1"))[1]["BL_SYNC"], "1")
        root = self.store()
        _, env, _ = self.select(tool, self.env(PROTON_NO_NTSYNC="1", BL_SYNC="1"))
        self.assertEqual(env["BL_SYNC"], "0")
        pack = make_pack(root, tool, "valve-experimental-r1")
        _, env, said = self.select(tool, self.env(PROTON_NO_NTSYNC="1", BL_SYNC="1"))
        self.assertEqual((env["BL_SYNC"], env["BL_SYNC_PACK"]), ("0", pack["id"]))
        self.assertIn("droiddeck-ntsync off", said)

    def test_internal_failure_launches_stock(self):
        root = self.store()
        tool = make_tool(self.tools, "Proton Experimental (ARM64)")
        make_pack(root, tool, "valve-experimental-r1")

        def broken(*args, **kwargs):
            raise RuntimeError("boom")

        for name in ("measure", "load_packs", "ensure_dist", "find_pack"):
            with mock.patch.dict(GLOBALS, {name: broken}):
                command, env, said = self.select(tool, self.env(PROTON_NO_NTSYNC="1"))
            self.assertEqual(command[0], str(tool / "proton"), name)
            self.assertEqual(env, self.env(PROTON_NO_NTSYNC="1", BL_SYNC="0"), name)
            self.assertIn("boom", said)

    def test_droiddeck_ntsync_stands_in_without_a_pack(self):
        root = self.store()
        tool = make_tool(self.tools, "Proton Experimental (ARM64)")
        command, env, said = self.select(tool, self.env(BL_SYNC="0"))
        self.assertEqual(command[0], str(tool / "proton"))
        self.assertEqual(env, self.env(BL_SYNC="1"))
        self.assertTrue(said.endswith("; stock binaries with droiddeck-ntsync\n"), said)
        self.assertEqual((root / "launches.log").read_text().split("\n")[-2].split("\t")[6], "1")
        command, env, said = self.select(tool, self.env(PROTON_NO_NTSYNC="1", BL_SYNC="1"))
        self.assertEqual(env, self.env(PROTON_NO_NTSYNC="1", BL_SYNC="0"))
        self.assertTrue(said.endswith("; stock binaries\n"), said)
        pack = make_pack(root, tool, "valve-experimental-r1")
        (root / "packs" / pack["id"] / NTDLL).write_bytes(b"tampered")
        command, env, said = self.select(tool, self.env(BL_SYNC="0"))
        self.assertEqual(command[0], str(tool / "proton"))
        self.assertEqual(env, self.env(BL_SYNC="1"))
        self.assertIn("unusable", said)
        self.assertTrue(said.endswith("; stock binaries with droiddeck-ntsync\n"), said)

    def test_droiddeck_fsync_stands_in_without_a_pack(self):
        root = self.store()
        tool = make_tool(self.tools, "Proton Experimental (ARM64)", fsync=True)
        command, env, said = self.select(tool, self.env(BL_SYNC="0"))
        self.assertEqual(command, [str(tool / "proton"), "waitforexitandrun", "game.exe"])
        self.assertEqual(env, self.env(BL_SYNC="0", BL_FSYNC="1", WINEFSYNC="1"))
        self.assertEqual(said, "droiddeck-fsync: no pack for Proton Experimental (ARM64) (%s, ntdll %s); "
                         "stock binaries with Proton's fsync\n" % (VALVE_LINE, sha(tool / NTDLL)[:12]))
        fields = (root / "launches.log").read_text().splitlines()[-1].split("\t")
        self.assertEqual((fields[4], fields[6], fields[8]), ("-", "0", "1"))
        self.assertEqual(len((root / "wanted.tsv").read_text().splitlines()), 1)
        for extra in ({"BL_SYNC": "1"}, {"PROTON_NO_FSYNC": "1"}, {"STEAM_COMPAT_APP_ID": "2630"}, {"SteamAppId": "1233880"}):
            command, env, said = self.select(tool, self.env(**extra))
            self.assertNotIn("BL_FSYNC", env, extra)
            self.assertEqual(env["BL_SYNC"], "1", extra)
            self.assertTrue(said.endswith("; stock binaries with droiddeck-ntsync\n"), (extra, said))
        for extra in ({"STEAM_COMPAT_APP_ID": "2630", "PROTON_NO_FSYNC": "0"}, {"PROTON_NO_FSYNC": ""}, {"BL_SYNC": "0"}):
            self.assertEqual(self.select(tool, self.env(**extra))[1]["BL_FSYNC"], "1", extra)
        env = self.select(tool, self.env(PROTON_NO_FSYNC="1", PROTON_NO_NTSYNC="1"))[1]
        self.assertEqual((env["BL_SYNC"], "BL_FSYNC" in env), ("0", False))
        pack = make_pack(root, tool, "valve-experimental-r1")
        (root / "packs" / pack["id"] / NTDLL).write_bytes(b"tampered")
        command, env, said = self.select(tool, self.env())
        self.assertEqual((command[0], env["BL_FSYNC"]), (str(tool / "proton"), "1"))
        self.assertIn("\ndroiddeck-fsync: pack %s unusable for Proton Experimental (ARM64) (" % pack["id"], said)
        self.assertTrue(said.endswith("; stock binaries with Proton's fsync\n"), said)

    def test_droiddeck_fsync_first(self):
        root = self.store()
        tool = make_tool(self.tools, "Proton Experimental (ARM64)", fsync=True)
        pack = make_pack(root, tool, "valve-experimental-r1")
        for extra in ({}, {"BL_SYNC_FALLBACK": None}, {"BL_SYNC_FALLBACK": "0"}, {"BL_FSYNC": "0"}):
            command, env, said = self.select(tool, self.env(BL_FSYNC_FIRST="1", **extra))
            self.assertEqual(command[0], str(tool / "proton"), extra)
            self.assertEqual((env["BL_FSYNC"], env["BL_SYNC"], env["WINEFSYNC"]), ("1", "0", "1"), extra)
            self.assertNotIn("BL_SYNC_PACK", env, extra)
            self.assertEqual(said, "droiddeck-fsync: chosen for Proton Experimental (ARM64); stock binaries with Proton's fsync\n")
        self.assertFalse((root / "wanted.tsv").exists())
        command, env, said = self.select(tool, self.env(BL_FSYNC_FIRST="1", BL_SYNC="1"))
        self.assertEqual((command[0], env["BL_SYNC_PACK"], env["BL_SYNC"]), (str(self.dist_of(pack["id"], tool) / "proton"), pack["id"], "1"))
        self.assertNotIn("BL_FSYNC", env)
        command, env, said = self.select(tool, self.env(BL_FSYNC_FIRST="1", BL_FSYNC="1", STEAM_COMPAT_APP_ID="414740"))
        self.assertEqual((env["BL_FSYNC"], env["BL_SYNC_PACK"], env["PROTON_NO_ESYNC"]), ("0", pack["id"], "1"))
        command, env, said = self.select(tool, self.env(BL_FSYNC_FIRST="1", BL_FSYNC="1", BL_SYNC_FALLBACK="0", PROTON_NO_FSYNC="1"))
        self.assertEqual((command[0], env["BL_FSYNC"], said), (str(tool / "proton"), "0", "droiddeck-esync: off\n"))
        plain = make_tool(self.tools, "GE-Proton11-9", "1790000000 GE-Proton11-9", builtin=True, ge=True)
        command, env, said = self.select(plain, self.env(BL_FSYNC_FIRST="1"))
        self.assertEqual((command[0], said), (str(plain / "proton"), "droiddeck-esync: GE-Proton11-9 has esync built in\n"))
        self.assertNotIn("BL_FSYNC", env)

    def test_fsync_takes_over_when_esync_is_off_for_a_game(self):
        root = self.store()
        tool = make_tool(self.tools, "Proton Experimental (ARM64)", fsync=True)
        pack = make_pack(root, tool, "valve-experimental-r1")
        for extra in ({"PROTON_NO_ESYNC": "1"}, {"WINEESYNC": "0"}):
            command, env, said = self.select(tool, self.env(**extra))
            self.assertEqual((command[0], env["BL_FSYNC"]), (str(tool / "proton"), "1"), extra)
            self.assertEqual(said, "droiddeck-fsync: esync is off for this game; stock binaries with Proton's fsync\n")
        with mock.patch("resource.getrlimit", return_value=(1024, 4096)):
            env = self.select(tool, self.env(BL_SYNC_MIN_NOFILE=None))[1]
        self.assertEqual(env["BL_FSYNC"], "1")
        for extra in ({"STEAM_COMPAT_APP_ID": "2630"}, {"PROTON_NO_ESYNC": "1", "PROTON_NO_FSYNC": "1"}, {"WINEESYNC": "0", "BL_SYNC": "1"}):
            command, env, said = self.select(tool, self.env(**extra))
            self.assertEqual((env["BL_SYNC_PACK"], env["WINEESYNC"]), (pack["id"], "0"), extra)
            self.assertNotIn("BL_FSYNC", env, extra)
        command, env, said = self.select(tool, self.env())
        self.assertEqual((env["BL_SYNC_PACK"], env["WINEESYNC"], env["PROTON_NO_FSYNC"]), (pack["id"], "1", "1"))
        self.assertNotIn("BL_FSYNC", env)

    def test_the_hash_cache_remembers_fsync(self):
        root = self.store()
        tool = make_tool(self.tools, "Proton Experimental (ARM64)", fsync=True)
        self.select(tool, self.env())
        cache = (root / "hash-cache.tsv").read_text().splitlines()
        rows = {line.split("\t")[0]: line.split("\t") for line in cache}
        server = rows[os.path.realpath(tool / WINESERVER)]
        self.assertEqual((len(server), server[7], server[9], server[10]), (11, "0", "1", "1"))
        self.assertEqual(rows[os.path.realpath(tool / NTDLL)][9], "0")
        (root / "hash-cache.tsv").write_text("\n".join("\t".join(row[:9]) for row in rows.values()) + "\n")
        self.assertEqual(SYNC["parse_cache"]((root / "hash-cache.tsv").read_text()), {})
        self.assertEqual(self.select(tool, self.env())[1]["BL_FSYNC"], "1")

    def test_esync_off_games_get_droiddeck_ntsync_on_a_pack(self):
        root = self.store()
        tool = make_tool(self.tools, "Proton Experimental (ARM64)")
        pack = make_pack(root, tool, "valve-experimental-r1")
        for extra in ({"PROTON_NO_ESYNC": "1"}, {"STEAM_COMPAT_APP_ID": "2630"}, {"WINEESYNC": "0"}):
            command, env, said = self.select(tool, self.env(**extra))
            self.assertEqual((env["BL_SYNC_PACK"], env["WINEESYNC"], env["BL_SYNC"]), (pack["id"], "0", "1"), extra)
            self.assertIn("droiddeck-ntsync on, droiddeck-esync off", said)
        env = self.select(tool, self.env(PROTON_NO_ESYNC="1", PROTON_NO_NTSYNC="1"))[1]
        self.assertEqual((env["WINEESYNC"], env["BL_SYNC"]), ("0", "0"))
        env = self.select(tool, self.env(BL_SYNC="0"))[1]
        self.assertEqual((env["WINEESYNC"], env["BL_SYNC"]), ("1", "0"))

    def test_a_running_prefix_keeps_its_sync_choice(self):
        root = self.store()
        tool = make_tool(self.tools, "Proton Experimental (ARM64)", fsync=True)
        prefix = os.path.realpath("/compatdata/42/pfx")
        command, first, _ = self.select(tool, self.env())
        self.assertEqual(first["BL_FSYNC"], "1")
        rows = json.loads((root / "prefixes.json").read_text())
        self.assertEqual(rows[prefix]["env"]["BL_FSYNC"], "1")
        self.assertIsNone(rows[prefix]["env"]["BL_SYNC_PACK"])
        pack = make_pack(root, tool, "valve-experimental-r1")
        dist = self.dist_of(pack["id"], tool)
        running = []
        with mock.patch.dict(GLOBALS, {"server_running": lambda path: running.append(path) or True}):
            command, env, said = self.select(tool, self.env(BL_SYNC="0", WINEFSYNC=None))
        self.assertEqual(running, [prefix])
        self.assertEqual(command[0], str(tool / "proton"))
        self.assertEqual(env, self.env(BL_SYNC="0", BL_FSYNC="1", WINEFSYNC="1"))
        self.assertEqual(said, "droiddeck-esync: Wine is still running in this prefix; keeping its sync choice (droiddeck-fsync)\n")
        command, env, said = self.select(tool, self.env())
        self.assertEqual((command[0], env["BL_SYNC_PACK"]), (str(dist / "proton"), pack["id"]))
        self.assertNotIn("BL_FSYNC", env)
        with mock.patch.dict(GLOBALS, {"server_running": lambda path: True}):
            command, env, said = self.select(tool, self.env(BL_FSYNC_FIRST="1"))
            self.assertEqual((command[0], env["BL_SYNC_PACK"], env["WINESERVER"]), (str(dist / "proton"), pack["id"], str(dist / WINESERVER)))
            self.assertNotIn("BL_FSYNC", env)
            self.assertIn("(droiddeck-esync)", said)
            other = make_tool(self.tools, "Proton 11.0 (ARM64)", salt=b"11", fsync=True)
            self.assertEqual(self.select(other, self.env())[1]["BL_FSYNC"], "1")
            shutil.rmtree(self.root / "dist")
            command, env, said = self.select(tool, self.env())
            self.assertEqual((command[0], env["BL_SYNC_PACK"]), (str(dist / "proton"), pack["id"]))
            self.assertNotIn("keeping", said)
        (root / "prefixes.json").write_text("not json")
        with mock.patch.dict(GLOBALS, {"server_running": lambda path: True}):
            self.assertEqual(self.select(tool, self.env())[1]["BL_SYNC_PACK"], pack["id"])
        self.assertEqual(json.loads((root / "prefixes.json").read_text())[prefix]["env"]["BL_SYNC_PACK"], pack["id"])

    def test_droiddeck_ntsync_does_not_stand_in_when_esync_is_off(self):
        self.store()
        tool = make_tool(self.tools, "Proton Experimental (ARM64)")
        env = self.env(BL_SYNC="0")
        env.pop("BL_SYNC_FALLBACK", None)
        command, chosen, said = self.select(tool, env)
        self.assertEqual(chosen, SYNC["wineserver_only"](env))
        self.assertEqual(said, "droiddeck-esync: off; wineserver sync only\n")

    def test_the_wineserver_tab_turns_proton_sync_off(self):
        self.store()
        tool = make_tool(self.tools, "GE-Proton11-9", "1790000000 GE-Proton11-9", builtin=True, ge=True, fsync=True)
        command, env, said = self.select(tool, self.env(BL_SYNC_FALLBACK="0"))
        self.assertEqual(command[0], str(tool / "proton"))
        self.assertEqual({name: env.get(name) for name in ("PROTON_NO_ESYNC", "PROTON_NO_FSYNC", "PROTON_NO_NTSYNC", "WINEESYNC", "WINEFSYNC", "BL_SYNC")},
                         {"PROTON_NO_ESYNC": "1", "PROTON_NO_FSYNC": "1", "PROTON_NO_NTSYNC": "1", "WINEESYNC": "0", "WINEFSYNC": "0", "BL_SYNC": "0"})
        env = self.select(tool, self.env(BL_SYNC_FALLBACK="0", PROTON_NO_FSYNC="0"))[1]
        self.assertEqual((env["PROTON_NO_FSYNC"], "WINEFSYNC" in env, env["PROTON_NO_ESYNC"]), ("0", False, "1"))
        self.assertEqual(SYNC["describe"](SYNC["wineserver_only"]({})), "wineserver")

    def test_the_ntsync_tab_needs_a_wine_with_ntsync(self):
        root = self.store()
        tool = make_tool(self.tools, "Proton 11.0 (ARM64)", "1789000000 proton-11.0-3", fsync=True, ntsync=False)
        command, env, said = self.select(tool, self.env(BL_SYNC="1"))
        self.assertEqual((command[0], env["BL_SYNC"], env["BL_FSYNC"], env["WINEFSYNC"]), (str(tool / "proton"), "0", "1", "1"))
        self.assertIn("droiddeck-ntsync: Proton 11.0 (ARM64) has no ntsync; trying droiddeck-fsync\n", said)
        plain = make_tool(self.tools, "Proton 10.0 (ARM64)", "1780000000 proton-10.0-1", salt=b"p", ntsync=False)
        command, env, said = self.select(plain, self.env(BL_SYNC="1"))
        self.assertEqual((env["BL_SYNC"], "BL_FSYNC" in env), ("0", False))
        self.assertTrue(said.endswith("; stock binaries (this Wine has no ntsync)\n"), said)
        pack = make_pack(root, plain, "valve-10-r1")
        command, env, said = self.select(plain, self.env(PROTON_NO_ESYNC="1"))
        self.assertEqual((env["BL_SYNC_PACK"], env["WINEESYNC"], env.get("BL_SYNC")), (pack["id"], "0", None))

    def test_built_in_esync_still_falls_back_when_esync_is_off(self):
        self.store()
        tool = make_tool(self.tools, "GE-Proton11-9", "1790000000 GE-Proton11-9", builtin=True, ge=True, fsync=True)
        for extra in ({"PROTON_NO_ESYNC": "1"}, {"WINEESYNC": "0"}, {"STEAM_COMPAT_APP_ID": "2630", "PROTON_NO_FSYNC": "0"}):
            command, env, said = self.select(tool, self.env(**extra))
            self.assertEqual((command[0], env["BL_FSYNC"]), (str(tool / "proton"), "1"), extra)
        plain = make_tool(self.tools, "GE-Proton11-8", "1789900000 GE-Proton11-8", salt=b"g", builtin=True, ge=True)
        for extra in ({"PROTON_NO_ESYNC": "1"}, {"STEAM_COMPAT_APP_ID": "2630"}):
            command, env, said = self.select(plain, self.env(**extra))
            self.assertEqual((env["BL_SYNC"], env["PROTON_NO_ESYNC"], env["WINEESYNC"]), ("1", "1", "0"), extra)
            self.assertTrue(said.endswith("; stock binaries with droiddeck-ntsync\n"), (extra, said))
        command, env, said = self.select(plain, self.env())
        self.assertEqual(said, "droiddeck-esync: GE-Proton11-8 has esync built in\n")

    def test_a_stock_fallback_replaces_the_recorded_pack(self):
        root = self.store()
        tool = make_tool(self.tools, "Proton Experimental (ARM64)")
        pack = make_pack(root, tool, "valve-experimental-r1")
        command, env, said = self.select(tool, self.env())
        prefix = os.path.realpath("/compatdata/42/pfx")
        rows = json.loads((root / "prefixes.json").read_text())
        self.assertEqual(rows[prefix]["env"]["BL_SYNC_PACK"], pack["id"])
        stock = [str(tool / "proton"), "waitforexitandrun", "game.exe"]
        self.quiet(SYNC["record"], stock, self.env())
        rows = json.loads((root / "prefixes.json").read_text())
        self.assertEqual((rows[prefix]["command"], rows[prefix]["env"]["BL_SYNC_PACK"]), (stock[0], None))

    def test_built_in_esync_never_uses_a_pack(self):
        root = self.store()
        tool = make_tool(self.tools, "GE-Proton11-9", "1790000000 GE-Proton11-9", builtin=True, ge=True)
        make_pack(root, tool, "ge-builtin-r1")
        command, env, said = self.select(tool, self.env())
        self.assertEqual(command[0], str(tool / "proton"))
        self.assertNotIn("WINEESYNC", env)
        self.assertEqual(said, "droiddeck-esync: GE-Proton11-9 has esync built in\n")
        self.assertFalse((root / "wanted.tsv").exists())
        self.assertFalse((root / "dist").exists())

    def test_wanted_rows_are_deduplicated(self):
        root = self.store()
        tool = make_tool(self.tools, "GE-Proton11-7", GE_LINE, ge=True)
        command, env, said = self.select(tool, self.env())
        self.assertEqual(command[0], str(tool / "proton"))
        self.assertEqual(said, "droiddeck-esync: no pack for GE-Proton11-7 (%s, ntdll %s); stock binaries with droiddeck-ntsync\n"
                         % (GE_LINE, sha(tool / NTDLL)[:12]))
        self.assertEqual(env["BL_SYNC"], "1")
        wanted = root / "wanted.tsv"
        rows = [line.split("\t") for line in wanted.read_text().splitlines()]
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0][:5], [sha(tool / NTDLL), sha(tool / WINESERVER), "GE-Proton11-7",
                                       SYNC["exports_hash"](tool / NTDLL), str(tool)])
        self.assertTrue(rows[0][5].isdigit())
        inode = os.stat(wanted).st_ino
        self.select(tool, self.env())
        self.assertEqual(os.stat(wanted).st_ino, inode)
        copy = self.tmp / "elsewhere" / "GE-Proton11-7"
        shutil.copytree(tool, copy, symlinks=True)
        self.select(copy, self.env())
        rows_after = [line.split("\t") for line in wanted.read_text().splitlines()]
        self.assertEqual(len(rows_after), 1)
        self.assertEqual((rows_after[0][4], rows_after[0][5]), (str(copy), rows[0][5]))
        other = make_tool(self.tools, "GE-Proton11-8", "1790000001 GE-Proton11-8", salt=b"8", ge=True)
        self.select(other, self.env())
        self.assertEqual(len(wanted.read_text().splitlines()), 2)


class MatchingTest(SyncTestCase):
    def test_exact_beats_source_then_rev_then_id(self):
        root = self.store()
        tool = make_tool(self.tools, "Proton 11.0 (ARM64)", "1789000000 proton-11.0-3")
        other = {NTDLL: "1" * 64, WINESERVER: "2" * 64}
        find = SYNC["find_pack"]
        exact1 = make_pack(root, tool, "a-exact-r1", rev=1)
        source9 = make_pack(root, tool, "b-source-r9", rev=9, source_match=True, stock=other)
        measured = SYNC["measure"](tool)
        self.assertIs(find([exact1, source9], measured), exact1)
        exact2 = make_pack(root, tool, "c-exact-r2", rev=2)
        self.assertIs(find([exact1, source9, exact2], measured), exact2)
        exact2b = make_pack(root, tool, "d-exact-r2", rev=2)
        self.assertIs(find([exact2b, exact1, exact2, source9], measured), exact2b)
        source3 = make_pack(root, tool, "e-source-r3", rev=3, source_match=True, stock=other)
        self.assertIs(find([source3, source9], measured), source9)
        self.assertIsNone(find([make_pack(root, tool, "f-nosource", rev=5, stock=other)], measured))
        self.assertIsNone(find([make_pack(root, tool, "g-exports", source_match=True, stock=other, exports="3" * 64)], measured))
        self.assertIsNone(find([make_pack(root, tool, "h-version", source_match=True, stock=other, version="proton-11.0-4")], measured))
        self.assertIsNone(find([dict(exact2, revoked=True)], measured))
        loaded = {pack["id"] for pack in SYNC["load_packs"](root)}
        self.assertEqual(loaded, {"a-exact-r1", "b-source-r9", "c-exact-r2", "d-exact-r2", "e-source-r3", "f-nosource",
                                  "g-exports", "h-version"})
        self.assertEqual(SYNC["find_pack"](SYNC["load_packs"](root), SYNC["measure"](tool))["id"], "d-exact-r2")

    def test_exports_are_measured_only_when_needed(self):
        root = self.store()
        tool = make_tool(self.tools, "Proton 11.0 (ARM64)", "1789000000 proton-11.0-3")
        exact = make_pack(root, tool, "a-exact-r1")
        measured = SYNC["measure"](tool)
        self.assertEqual(SYNC["find_pack"]([exact], measured), exact)
        self.assertNotIn("exports", measured)
        other = {NTDLL: "1" * 64, WINESERVER: "2" * 64}
        unrelated = make_pack(root, tool, "b-source-other", source_match=True, stock=other, version="proton-10.0-1")
        self.assertIsNone(SYNC["find_pack"]([unrelated], measured))
        self.assertNotIn("exports", measured)
        source = make_pack(root, tool, "c-source", source_match=True, stock=other)
        self.assertEqual(SYNC["find_pack"]([source], measured), source)
        self.assertEqual(measured["exports"], SYNC["exports_hash"](tool / NTDLL))

    def test_only_complete_valid_packs_load(self):
        root = self.store()
        tool = make_tool(self.tools, "Proton 11.0 (ARM64)")
        make_pack(root, tool, "good")
        make_pack(root, tool, "incomplete", complete=False)
        make_pack(root, tool, "revoked", revoked=True)
        make_pack(root, tool, "badcopy", copy=["../escape"])
        make_pack(root, tool, "badrev", rev="1")
        make_pack(root, tool, "badhash", files={NTDLL: "x", WINESERVER: "y"})
        make_pack(root, tool, "ignored")
        (root / "packs/ignored/.ignored").write_text("")
        make_pack(root, tool, "nofiles")
        (root / "packs/nofiles" / WINESERVER).unlink()
        make_pack(root, tool, "renamed")
        (root / "packs/renamed").rename(root / "packs/other-name")
        make_pack(root, tool, "badformat", format=True)
        make_pack(root, tool, ".hidden")
        (root / "packs/broken").mkdir()
        (root / "packs/broken/.complete").write_text("")
        (root / "packs/broken/pack.json").write_text("{")
        self.assertEqual([pack["id"] for pack in SYNC["load_packs"](root)], ["good"])


class ReconcileTest(SyncTestCase):
    def test_states_tables_and_prebuilt_dists(self):
        root = self.store()
        steam = self.tmp / "steam"
        sd = self.tmp / "sd"
        valve = make_tool(steam / "steamapps/common", "Proton Experimental (ARM64)")
        builtin = make_tool(sd / "steamapps/common", "Proton 11.0 (ARM64)", "1789000000 proton-11.0-3", salt=b"b", builtin=True)
        tools = steam / "compatibilitytools.d"
        ge = make_tool(tools, "GE-Proton11-7", GE_LINE, salt=b"ge", ge=True, decorated=True)
        make_tool(tools, "droiddeck-proton-arm64", salt=b"own")
        make_tool(tools, ".hidden", salt=b"hidden")
        (make_tool(tools, "incomplete", salt=b"inc") / WINESERVER).unlink()
        loose = make_tool(self.tmp / "loose", "GE-Proton10-1", "1700000000 GE-Proton10-1", salt=b"loose")
        gone = make_tool(self.tmp / "gone", "GE-Proton9-1", "1600000000 GE-Proton9-1", salt=b"gone")
        pack = make_pack(root, valve, "valve-experimental-r1")
        with contextlib.redirect_stderr(io.StringIO()):
            self.select(loose, self.env())
            self.select(gone, self.env())
        shutil.rmtree(gone)
        with mock.patch.dict(GLOBALS, {"SD_LIBRARY": str(sd)}):
            summary = self.quiet(SYNC["reconcile"], str(steam), {"HOME": str(self.home)})
            self.assertEqual(summary, "droiddeck-esync: 1 pack(s) in use, 2 wanted")
            self.assertFalse((root / "dist").exists())
            rows = {row[0]: row[1:] for row in (line.split("\t") for line in (root / "tools.tsv").read_text().splitlines())}
            self.assertEqual(rows, {
                str(valve): [VALVE_LINE, sha(valve / NTDLL), sha(valve / WINESERVER), "pack", pack["id"]],
                str(builtin): ["1789000000 proton-11.0-3", sha(builtin / NTDLL), sha(builtin / WINESERVER), "builtin", "-"],
                str(ge): [GE_LINE, sha(ge / NTDLL), sha(ge / WINESERVER), "wanted", "-"],
            })
            wanted = [line.split("\t") for line in (root / "wanted.tsv").read_text().splitlines()]
            self.assertEqual(sorted(row[4] for row in wanted), sorted([str(loose), str(ge)]))
            self.assertTrue((root / "hash-cache.tsv").is_file())
            summary = self.quiet(SYNC["reconcile"], str(steam), {"HOME": str(self.home), "BL_SYNC_FALLBACK": "1"})
            self.assertEqual(summary, "droiddeck-esync: 1 pack(s) in use, 2 wanted")
            dist = self.dist_of(pack["id"], valve)
            self.assertEqual(json.loads((dist / RECORD).read_text())["tool"], str(valve))
            make_pack(root, ge, "ge-GE-Proton11-7-r1", flavor="ge")
            make_pack(root, loose, "ge-GE-Proton10-1-r1", flavor="ge")
            summary = self.quiet(SYNC["reconcile"], str(steam), {"HOME": str(self.home), "BL_SYNC_FALLBACK": "1"})
            self.assertEqual(summary, "droiddeck-esync: 2 pack(s) in use, 0 wanted")
            self.assertEqual((root / "wanted.tsv").read_text(), "")
            self.assertTrue(self.dist_of("ge-GE-Proton11-7-r1", ge).is_dir())
            self.assertIn("ge-GE-Proton11-7-r1", (root / "tools.tsv").read_text())

    def test_steam_compatibility_reports_sync(self):
        steam = self.tmp / "steam"
        make_tool(steam / "steamapps/common", "Proton Experimental (ARM64)")
        env = {"PATH": os.defpath, "HOME": str(self.home), "BL_SYNC_FALLBACK": "1"}
        result = subprocess.run([sys.executable, str(BIN / "steam-compatibility"), str(steam)], env=env,
                                capture_output=True, text=True, check=True)
        self.assertNotIn("droiddeck-esync", result.stdout + result.stderr)
        self.assertFalse(self.root.exists())
        root = self.store()
        make_pack(root, steam / "steamapps/common/Proton Experimental (ARM64)", "valve-experimental-r1")
        result = subprocess.run([sys.executable, str(BIN / "steam-compatibility"), str(steam)], env=env,
                                capture_output=True, text=True, check=True)
        self.assertIn("steam-compatibility: droiddeck-esync: 1 pack(s) in use, 0 wanted\n", result.stdout)
        self.assertTrue(self.dist_of("valve-experimental-r1", steam / "steamapps/common/Proton Experimental (ARM64)").is_dir())


class GcTest(SyncTestCase):
    def test_removes_stale_dists_unless_in_use(self):
        root = self.store()
        keep = make_tool(self.tools, "keep", salt=b"keep")
        uninstalled = make_tool(self.tools, "uninstalled", salt=b"uninstalled")
        updated = make_tool(self.tools, "updated", salt=b"updated")
        running = make_tool(self.tools, "running", salt=b"running")
        recent = make_tool(self.tools, "recent", salt=b"recent")
        dists = {}
        for tool in (keep, uninstalled, updated, running, recent):
            pack = make_pack(root, tool, "pack-" + tool.name)
            dists[tool.name] = Path(SYNC["ensure_dist"](root, tool, pack))
        for name in ("uninstalled", "running", "recent"):
            shutil.rmtree(root / "packs" / ("pack-" + name))
        (updated / NTDLL).write_bytes(elf(EXPORTS, salt=b"new build"))
        old = time.time() - 2 * SYNC["GC_GRACE"]
        for name in ("keep", "uninstalled", "updated", "running"):
            os.utime(dists[name], (old, old))
        stale_temp = root / "dist" / ".pack-x~00000000.tmp-123"
        stale_temp.mkdir()
        os.utime(stale_temp, (time.time() - 2 * SYNC["TEMP_AGE"],) * 2)
        fresh_temp = root / "dist" / ".pack-y~00000000.tmp-456"
        fresh_temp.mkdir()
        (root / "dist" / "ghost~00000000.lock").write_text("")
        cmdline = b"/usr/bin/python3\0" + str(dists["running"] / "proton").encode() + b"\0waitforexitandrun\0"
        with mock.patch.dict(GLOBALS, {"process_cmdlines": lambda: None}):
            self.assertEqual(SYNC["gc"](root), 0)
        self.assertTrue(dists["uninstalled"].exists())
        with mock.patch.dict(GLOBALS, {"process_cmdlines": lambda: [b"bash\0", cmdline]}):
            self.assertEqual(SYNC["gc"](root), 2)
        remaining = sorted(os.listdir(root / "dist"))
        self.assertEqual(remaining, sorted([dists["keep"].name, dists["keep"].name + ".lock", dists["running"].name,
                                            dists["running"].name + ".lock", dists["recent"].name, dists["recent"].name + ".lock",
                                            fresh_temp.name]))
        with mock.patch.dict(GLOBALS, {"process_cmdlines": lambda: []}):
            os.utime(dists["recent"], (old, old))
            self.assertEqual(SYNC["gc"](root), 2)
        self.assertEqual(sorted(os.listdir(root / "dist")), sorted([dists["keep"].name, dists["keep"].name + ".lock",
                                                                    fresh_temp.name]))

    def test_superseded_pack_revisions_are_collected(self):
        root = self.store()
        tool = make_tool(self.tools, "Proton Experimental (ARM64)")
        first = make_pack(root, tool, "valve-experimental-r1", rev=1)
        first_dist = Path(SYNC["ensure_dist"](root, tool, first))
        second = make_pack(root, tool, "valve-experimental-r2", rev=2)
        second_dist = Path(SYNC["ensure_dist"](root, tool, second))
        self.assertNotEqual(first_dist, second_dist)
        old = time.time() - 2 * SYNC["GC_GRACE"]
        for dist in (first_dist, second_dist):
            os.utime(dist, (old, old))
        with mock.patch.dict(GLOBALS, {"process_cmdlines": lambda: [str(first_dist / "proton").encode() + b"\0run\0"]}):
            self.assertEqual(SYNC["gc"](root), 0)
        with mock.patch.dict(GLOBALS, {"process_cmdlines": lambda: []}):
            self.assertEqual(SYNC["gc"](root), 1)
        self.assertEqual(sorted(os.listdir(root / "dist")), [second_dist.name, second_dist.name + ".lock"])
        self.assertTrue((root / "packs" / first["id"] / ".complete").is_file())
        _, env, _ = self.select(tool, self.env())
        self.assertEqual(env["BL_SYNC_PACK"], second["id"])


class LauncherTest(SyncTestCase):
    def test_game_env_applies_sync_keys_for_every_verb(self):
        tool = make_tool(self.tools, "Proton Experimental (ARM64)")
        config = self.home / ".config/droiddeck/game-environment.json"
        config.parent.mkdir(parents=True)
        config.write_text(json.dumps({"version": 1, "shared": {"PROTON_NO_NTSYNC": "1", "CUSTOM": "shared"},
                                      "games": {"42": {"WINEESYNC": "3", "BL_SYNC_FALLBACK": "1"}}}))
        env = {"PATH": os.defpath, "HOME": str(self.home), "STEAM_COMPAT_DATA_PATH": "/compatdata/42", "BL_SYNC": "1",
               "BL_SYNC_MIN_NOFILE": "1"}

        def launch(verb):
            output = subprocess.run([sys.executable, str(BIN / "bannerlator-game-env"), str(tool / "proton"), verb, "a b"],
                                    env=env, capture_output=True, text=True, check=True).stdout
            return json.loads(output)

        argv0, args, seen = launch("run")
        self.assertEqual((argv0, args), (str(tool / "proton"), ["run", "a b"]))
        self.assertEqual((seen["CUSTOM"], seen["BL_SYNC"], seen["WINEESYNC"]), (None, "0", "3"))
        self.assertEqual(launch("waitforexitandrun")[2]["CUSTOM"], "shared")
        root = self.store()
        pack = make_pack(root, tool, "valve-experimental-r1")
        dist = self.dist_of(pack["id"], tool)
        for verb in ("run", "waitforexitandrun", "getcompatpath"):
            argv0, args, seen = launch(verb)
            self.assertEqual((argv0, args), (str(dist / "proton"), [verb, "a b"]))
            self.assertEqual((seen["WINEESYNC"], seen["BL_SYNC_PACK"], seen["WINESERVER"]), ("3", pack["id"], str(dist / WINESERVER)))

    def test_failed_dist_exec_keeps_the_sync_choice(self):
        tool = make_tool(self.tools, "Proton Experimental (ARM64)")
        pack = make_pack(self.store(), tool, "valve-experimental-r1")
        dist = self.dist_of(pack["id"], tool)
        game_env = runpy.run_path(str(BIN / "bannerlator-game-env"))
        command = [str(tool / "proton"), "waitforexitandrun", "game.exe"]
        env = self.env(STEAM_COMPAT_APP_ID="2630", PROTON_NO_NTSYNC="1", BL_SYNC="1", WINEESYNC="1")
        calls = []

        def execvpe(path, args, environment):
            calls.append((path, list(args), dict(environment)))
            if len(calls) == 1:
                raise PermissionError("denied")
            raise SystemExit(0)

        with mock.patch.dict(os.environ, env, clear=True), mock.patch.object(sys, "argv", ["bannerlator-game-env"] + command), \
                mock.patch("os.execvpe", execvpe), contextlib.redirect_stderr(io.StringIO()) as said:
            with self.assertRaises(SystemExit):
                game_env["main"]()
        self.assertEqual([call[:2] for call in calls], [(str(dist / "proton"), [str(dist / "proton")] + command[1:]),
                                                        (str(tool / "proton"), command)])
        self.assertEqual((calls[0][2]["WINEESYNC"], calls[0][2]["PROTON_NO_ESYNC"], calls[0][2]["BL_SYNC_PACK"]), ("0", "1", pack["id"]))
        self.assertEqual(calls[1][2], self.env(STEAM_COMPAT_APP_ID="2630", PROTON_NO_NTSYNC="1", BL_SYNC="0", WINEESYNC="1"))
        self.assertIn("could not start (", said.getvalue())

    def test_generated_launchers_run_the_dist_proton(self):
        root = self.store()
        steam = self.tmp / "Steam"
        tools = steam / "compatibilitytools.d"
        depot = make_tool(steam / "steamapps/common", COMPAT["SOURCES"][0])
        extra = make_tool(tools, "GE-Proton11-7", GE_LINE, salt=b"ge", ge=True)
        (extra / "toolmanifest.vdf").write_text(MANIFEST)
        COMPAT["build_tool"](str(tools / COMPAT["TOOL"]), str(depot))
        COMPAT["adopt_extras"](str(tools))
        packs = {depot: make_pack(root, depot, "valve-experimental-r1"), extra: make_pack(root, extra, "ge-GE-Proton11-7-r1", flavor="ge")}
        wrappers = {depot: tools / COMPAT["TOOL"] / COMPAT["LAUNCHER"], extra: extra / COMPAT["EXTRA_WRAPPER"]}
        env = {"PATH": os.defpath, "HOME": str(self.home), "STEAM_COMPAT_DATA_PATH": "/compatdata/42",
               "STEAM_COMPAT_CLIENT_INSTALL_PATH": str(steam), "BL_SYNC_FALLBACK": "1", "BL_SYNC_MIN_NOFILE": "1"}
        for tool, wrapper in wrappers.items():
            wrapper.write_text(wrapper.read_text().replace("/usr/local/bin/bannerlator-game-env", str(BIN / "bannerlator-game-env")))
            dist = self.dist_of(packs[tool]["id"], tool)
            result = subprocess.run([str(wrapper), "waitforexitandrun", "game with spaces.exe"], env=env, text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            argv0, args, seen = json.loads(result.stdout)
            self.assertEqual((argv0, args), (str(dist / "proton"), ["waitforexitandrun", "game with spaces.exe"]))
            self.assertEqual((seen["BL_SYNC_PACK"], seen["WINESERVER"], seen["WINEESYNC"]),
                             (packs[tool]["id"], str(dist / WINESERVER), "1"))
            self.assertIn("droiddeck-esync: pack %s" % packs[tool]["id"], result.stderr)
            for name in ("bannerlator-proton-wrap", "toolmanifest.vdf", "compatibilitytool.vdf", "toolmanifest.vdf.bannerlator-orig"):
                self.assertFalse(os.path.lexists(dist / name), name)
            result = subprocess.run([str(wrapper), "waitforexitandrun"], env={**env, "BL_SYNC_FALLBACK": "0"}, text=True, capture_output=True)
            self.assertEqual(json.loads(result.stdout)[0], str(tool / "proton"), result.stderr)

    def test_session_collects_sync_tables(self):
        script = (BIN / "bannerlator-session").read_text()
        start = script.index("collect_steam_logs() {")
        function = script[start:script.index("\n}\n", start) + 3]
        debug = self.tmp / "debug"
        env = {"PATH": os.defpath, "HOME": str(self.home), "BL_DEBUG_DIR": str(debug)}
        subprocess.run(["bash", "-c", "set -u\n" + function + "collect_steam_logs"], env=env, check=True, capture_output=True)
        self.assertFalse((debug / "droiddeck-esync").exists())
        root = self.store()
        (root / "launches.log").write_text("launch\n")
        (root / "tools.tsv").write_text("tool\n")
        (root / "hash-cache.tsv").write_text("cache\n")
        subprocess.run(["bash", "-c", "set -u\n" + function + "collect_steam_logs"], env=env, check=True, capture_output=True)
        self.assertEqual(sorted(os.listdir(debug / "droiddeck-esync")), ["launches.log", "tools.tsv"])


if __name__ == "__main__":
    unittest.main()
