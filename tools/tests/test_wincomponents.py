import json
import os
import runpy
import tempfile
import unittest
from pathlib import Path
from unittest import mock

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
MODULE = runpy.run_path(str(BIN / "droiddeck-wincomponents"))
GAME_ENV = runpy.run_path(str(BIN / "droiddeck-game-env"))


class WinComponentsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.store = root / "store"
        self.compat = root / "compatdata/2571976725"
        self.selection = root / "wincomponents.json"
        self.pfx = self.compat / "pfx/drive_c/windows"

    def tearDown(self):
        self.tmp.cleanup()

    def component(self, cid, overrides, files):
        folder = self.store / cid
        for rel, data in files.items():
            path = folder / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        (folder / "component.json").write_text(json.dumps({"id": cid, "overrides": overrides}))

    def pick(self, *ids):
        self.selection.write_text(json.dumps({"version": 1, "games": {"2571976725": list(ids)}}))

    def apply(self):
        return MODULE["apply"](self.compat, "2571976725", self.selection, self.store)

    def test_places_files_before_the_prefix_exists_and_returns_overrides(self):
        self.component("openal", ["openal32"], {"syswow64/OpenAL32.dll": b"x86", "system32/OpenAL32.dll": b"x64"})
        self.pick("openal")
        names, notes = self.apply()
        self.assertEqual(names, ["openal32"])
        self.assertEqual((self.pfx / "syswow64/openal32.dll").read_bytes(), b"x86")
        self.assertEqual((self.pfx / "system32/openal32.dll").read_bytes(), b"x64")
        self.assertIn("2 file(s) placed", notes[-1])
        self.assertEqual(MODULE["overrides_value"](names + names), "openal32=n,b")

    def test_second_launch_copies_nothing(self):
        self.component("openal", ["openal32"], {"syswow64/OpenAL32.dll": b"x86"})
        self.pick("openal")
        self.apply()
        _, notes = self.apply()
        self.assertEqual(notes, [])

    def test_replaces_a_proton_builtin_symlink_and_keeps_the_prefix_spelling(self):
        self.component("xaudio", ["xaudio2_7"], {"syswow64/xaudio2_7.dll": b"native"})
        builtin = Path(self.tmp.name) / "proton/lib/wine/i386-windows/xaudio2_7.dll"
        builtin.parent.mkdir(parents=True)
        builtin.write_bytes(b"builtin")
        (self.pfx / "syswow64").mkdir(parents=True)
        (self.pfx / "syswow64/XAudio2_7.dll").symlink_to(builtin)
        self.pick("xaudio")
        self.apply()
        placed = self.pfx / "syswow64/XAudio2_7.dll"
        self.assertFalse(placed.is_symlink())
        self.assertEqual(placed.read_bytes(), b"native")
        self.assertEqual(builtin.read_bytes(), b"builtin")
        self.assertEqual(os.listdir(self.pfx / "syswow64"), ["XAudio2_7.dll"])

    def test_restores_a_file_an_older_proton_deleted(self):
        self.component("openal", ["openal32"], {"syswow64/OpenAL32.dll": b"x86"})
        self.pick("openal")
        self.apply()
        (self.pfx / "syswow64/openal32.dll").unlink()
        _, notes = self.apply()
        self.assertTrue((self.pfx / "syswow64/openal32.dll").is_file())
        self.assertIn("1 file(s) placed", notes[-1])

    def test_turning_a_component_off_removes_its_files_and_forgets_protons_config(self):
        self.component("openal", ["openal32"], {"syswow64/OpenAL32.dll": b"x86"})
        self.pick("openal")
        self.apply()
        (self.compat / "config_info").write_text("cached")
        self.pick()
        names, notes = self.apply()
        self.assertEqual(names, [])
        self.assertFalse((self.pfx / "syswow64/openal32.dll").exists())
        self.assertFalse((self.compat / "config_info").exists())
        self.assertIn("1 removed", notes[-1])

    def test_a_file_of_someone_elses_is_kept_and_put_back_when_turned_off(self):
        self.component("vc", ["msvcp100"], {"syswow64/msvcp100.dll": b"ours"})
        installer = self.pfx / "syswow64/MSVCP100.dll"
        installer.parent.mkdir(parents=True)
        installer.write_bytes(b"from a real installer")
        self.pick("vc")
        self.apply()
        self.assertEqual(installer.read_bytes(), b"ours")
        self.apply()
        self.pick()
        names, notes = self.apply()
        self.assertEqual(installer.read_bytes(), b"from a real installer")
        self.assertIn("1 put back as they were", notes[-1])
        self.assertFalse((self.compat / ".droiddeck-wincomponents-backup/drive_c/windows/syswow64/msvcp100.dll").exists())

    def test_protons_own_dlls_and_our_earlier_copy_get_no_backup(self):
        self.component("vc", ["msvcp100"], {"syswow64/msvcp100.dll": b"ours v1", "system32/msvcp100.dll": b"ours 64"})
        builtin = self.pfx / "system32/msvcp100.dll"
        builtin.parent.mkdir(parents=True)
        builtin.write_bytes(b"MZ" + b"\0" * 62 + b"Wine builtin DLL")
        self.pick("vc")
        self.apply()
        (self.store / "vc/syswow64/msvcp100.dll").write_bytes(b"ours v2")
        os.utime(self.store / "vc/syswow64/msvcp100.dll", (1, 1))
        self.apply()
        self.assertFalse((self.compat / ".droiddeck-wincomponents-backup").exists())

    def test_missing_component_is_skipped(self):
        self.pick("physx")
        names, notes = self.apply()
        self.assertEqual(names, [])
        self.assertEqual(notes, ["physx is not installed; skipped"])

    def test_a_component_gone_from_the_store_keeps_what_it_placed(self):
        self.component("dmband", ["dmband"], {"syswow64/dmband.dll": b"band"})
        meta = self.store / "directmusic"
        meta.mkdir(parents=True)
        (meta / "component.json").write_text(json.dumps({"id": "directmusic", "overrides": [], "requires": ["dmband"]}))
        self.component("openal", ["openal32"], {"syswow64/OpenAL32.dll": b"x86"})
        self.pick("directmusic", "openal")
        self.apply()
        for cid in ("dmband", "directmusic", "openal"):
            for path in sorted((self.store / cid).rglob("*"), reverse=True):
                path.rmdir() if path.is_dir() else path.unlink()
            (self.store / cid).rmdir()
        names, notes = self.apply()
        self.assertEqual(MODULE["overrides_value"](names), "dmband=n,b;openal32=n,b")
        self.assertEqual((self.pfx / "syswow64/dmband.dll").read_bytes(), b"band")
        self.assertEqual((self.pfx / "syswow64/openal32.dll").read_bytes(), b"x86")
        self.assertTrue(all("kept the files it placed" in note for note in notes), notes)
        self.component("openal", ["openal32"], {"syswow64/OpenAL32.dll": b"x86 v2"})
        os.utime(self.store / "openal/syswow64/OpenAL32.dll", (1, 1))
        names, _ = self.apply()
        self.assertEqual(MODULE["overrides_value"](names), "dmband=n,b;openal32=n,b")
        self.assertEqual((self.pfx / "syswow64/openal32.dll").read_bytes(), b"x86 v2")
        self.pick()
        self.apply()
        self.assertFalse((self.pfx / "syswow64/dmband.dll").exists())
        self.assertFalse((self.pfx / "syswow64/openal32.dll").exists())

    def test_state_from_before_the_update_keeps_a_missing_bundles_parts(self):
        self.component("dmband", ["dmband"], {"syswow64/dmband.dll": b"band"})
        meta = self.store / "directmusic"
        meta.mkdir(parents=True)
        (meta / "component.json").write_text(json.dumps({"id": "directmusic", "overrides": [], "requires": ["dmband"]}))
        self.pick("directmusic")
        self.apply()
        state_file = self.compat / ".droiddeck-wincomponents.json"
        state = json.loads(state_file.read_text())
        rel = next(iter(state["files"]))
        state_file.write_text(json.dumps({"files": state["files"], "components": state["components"], "registry": {}}))
        for cid in ("dmband", "directmusic"):
            for path in sorted((self.store / cid).rglob("*"), reverse=True):
                path.rmdir() if path.is_dir() else path.unlink()
            (self.store / cid).rmdir()
        dll = self.compat / "pfx" / rel
        for _ in range(2):
            names, notes = self.apply()
            self.assertEqual(MODULE["overrides_value"](names), "dmband=n,b")
            self.assertEqual(dll.read_bytes(), b"band")
            self.assertEqual(len(notes), 2)
            saved = json.loads(state_file.read_text())
            self.assertEqual(saved["guessed"], ["directmusic"])
            self.assertNotIn("directmusic", saved["requires"])
        self.component("dmband", ["dmband"], {"syswow64/dmband.dll": b"band2"})
        names, _ = self.apply()
        self.assertEqual(MODULE["overrides_value"](names), "dmband=n,b")
        self.assertEqual(dll.read_bytes(), b"band2")
        self.assertEqual(json.loads(state_file.read_text())["guessed"], ["directmusic"])
        self.pick()
        self.apply()
        self.assertFalse(dll.exists())

    def test_a_missing_legacy_bundle_with_its_own_files_keeps_its_parts_and_their_registry(self):
        self.pfx.parent.mkdir(parents=True)
        user = self.compat / "pfx/user.reg"
        user.write_text('WINE REGISTRY Version 2\n\n[Software\\\\Test] 1700000000\n"Mode"="ours"\n')
        ident = "HKCU\tsoftware\\test\tmode"
        own = self.compat / "pfx/drive_c/windows/syswow64/bundle.dll"
        part = self.compat / "pfx/drive_c/windows/syswow64/part.dll"
        for path in (own, part):
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"x")
        self.compat.joinpath(".droiddeck-wincomponents.json").write_text(json.dumps({
            "files": {"drive_c/windows/syswow64/bundle.dll": "bundle", "drive_c/windows/syswow64/part.dll": "part"},
            "components": ["bundle", "part", "tweak"],
            "registry": {ident: {"prev": None, "cid": "tweak", "hive": "HKCU", "key": "Software\\Test", "name": "Mode"}}}))
        self.pick("bundle")
        names, _ = self.apply()
        self.assertEqual(set(MODULE["overrides_value"](names).split(";")), {"bundle=n,b", "part=n,b"})
        self.assertTrue(own.exists() and part.exists())
        self.assertIn('"Mode"="ours"', user.read_text())
        saved = json.loads((self.compat / ".droiddeck-wincomponents.json").read_text())
        self.assertEqual(saved["guessed"], ["bundle"])
        self.assertIn(ident, saved["registry"])

    def test_a_missing_component_that_only_set_registry_values_keeps_them(self):
        self.pfx.parent.mkdir(parents=True)
        user = self.compat / "pfx/user.reg"
        user.write_text('WINE REGISTRY Version 2\n\n[Software\\\\Test] 1700000000\n"Mode"="ours"\n')
        ident = "HKCU\tsoftware\\test\tmode"
        self.compat.joinpath(".droiddeck-wincomponents.json").write_text(json.dumps({
            "files": {}, "components": ["tweak"], "requires": {}, "overrides": {"tweak": []},
            "registry": {ident: {"prev": None, "cid": "tweak", "hive": "HKCU", "key": "Software\\Test", "name": "Mode"}}}))
        self.pick("tweak")
        _, notes = self.apply()
        self.assertIn('"Mode"="ours"', user.read_text())
        self.assertIn("tweak is not in the store; kept the files it placed until it is installed again", notes)
        self.assertIn(ident, json.loads((self.compat / ".droiddeck-wincomponents.json").read_text())["registry"])

    def test_no_selection_touches_nothing(self):
        self.assertEqual(self.apply(), ([], []))
        self.assertFalse(self.compat.exists())

    def test_bad_selection_is_refused(self):
        self.selection.write_text(json.dumps({"version": 1, "games": {"2571976725": ["a/../etc"]}}))
        with self.assertRaises(ValueError):
            self.apply()

    def test_game_env_puts_component_overrides_before_the_games_own(self):
        self.component("openal", ["openal32"], {"syswow64/OpenAL32.dll": b"x86"})
        self.pick("openal")
        globals_ = GAME_ENV["win_components"].__globals__
        real = runpy.run_path(str(BIN / "droiddeck-wincomponents"))
        real["apply"].__defaults__ = (self.selection, self.store, self.selection.parent / "no-pending.json", None)
        with mock.patch.dict(globals_, {"runpy": mock.Mock(run_path=lambda _path: real)}):
            self.assertEqual(GAME_ENV["win_components"](str(self.compat) + "/", "2571976725"), "openal32=n,b")
            self.assertEqual(GAME_ENV["win_components"]("", "2571976725"), "")

    def test_bundle_brings_its_parts_and_catalog_names_with_dots_work(self):
        self.component("dmband", ["dmband"], {"syswow64/dmband.dll": b"band"})
        self.component("dsound", ["dsound=n"], {"syswow64/dsound.dll": b"ds"})
        meta = self.store / "directmusic"
        meta.mkdir(parents=True)
        (meta / "component.json").write_text(json.dumps({"id": "directmusic", "overrides": [], "requires": ["dmband", "dsound"]}))
        self.component("xaudio2.7", ["xaudio2_7"], {"system32/xaudio2_7.dll": b"xa"})
        self.pick("directmusic", "xaudio2.7")
        names, _ = self.apply()
        self.assertEqual(MODULE["overrides_value"](names), "dmband=n,b;dsound=n;xaudio2_7=n,b")
        self.assertEqual((self.pfx / "syswow64/dmband.dll").read_bytes(), b"band")
        self.assertEqual(json.loads((self.compat / ".droiddeck-wincomponents.json").read_text())["components"],
                         ["dmband", "dsound", "directmusic", "xaudio2.7"])

    def test_files_one_level_down_are_placed(self):
        self.component("gmdls", [], {"system32/drivers/GM.DLS": b"dls"})
        self.pick("gmdls")
        self.apply()
        self.assertEqual((self.pfx / "system32/drivers/gm.dls").read_bytes(), b"dls")

    def test_overrides_value_last_word_wins_and_refuses_junk(self):
        self.assertEqual(MODULE["overrides_value"](["a", "b=n", "a=b", "bad;name", "c=x"]), "b=n;a=b")


class PendingWaitTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.store = root / "store"
        self.pending = root / "pending.json"
        self.slept = []

    def tearDown(self):
        self.tmp.cleanup()

    def install(self, cid):
        folder = self.store / cid
        folder.mkdir(parents=True, exist_ok=True)
        (folder / "component.json").write_text(json.dumps({"id": cid, "overrides": []}))

    def clock(self):
        return len(self.slept) * 0.5

    def wait(self, timeout=10.0, on_sleep=None):
        def sleep(seconds):
            self.slept.append(seconds)
            if on_sleep:
                on_sleep(len(self.slept))
        return MODULE["wait_for_pending"]("1262580", self.pending, self.store, timeout, sleep, self.clock)

    def test_nothing_pending_means_no_wait(self):
        self.assertIsNone(self.wait())
        self.pending.write_text(json.dumps({"games": {"999": ["d3dx9"]}}))
        self.assertIsNone(self.wait())
        self.install("d3dx9")
        self.pending.write_text(json.dumps({"games": {"1262580": ["d3dx9"]}}))
        self.assertIsNone(self.wait())
        self.assertEqual([], self.slept)

    def test_waits_until_the_component_lands(self):
        self.pending.write_text(json.dumps({"games": {"1262580": ["vcredist2019_dll"]}}))
        note = self.wait(on_sleep=lambda n: self.install("vcredist2019_dll") if n == 3 else None)
        self.assertEqual(3, len(self.slept))
        self.assertEqual("wincomp: 1262580 waited 1500ms for vcredist2019_dll", note)

    def test_gives_up_cleanly_after_the_timeout(self):
        self.pending.write_text(json.dumps({"games": {"1262580": ["vcredist2019_dll", "d3dx9"]}}))
        self.install("d3dx9")
        note = self.wait(timeout=2.0)
        self.assertEqual(4, len(self.slept))
        self.assertEqual("wincomp: 1262580 launched without vcredist2019_dll (not ready)", note)

    def test_a_damaged_pending_file_is_no_wait(self):
        self.pending.write_text("{not json")
        self.assertIsNone(self.wait())


if __name__ == "__main__":
    unittest.main()
