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

    def test_missing_component_is_skipped(self):
        self.pick("physx")
        names, notes = self.apply()
        self.assertEqual(names, [])
        self.assertEqual(notes, ["physx is not installed; skipped"])

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
        real["apply"].__defaults__ = (self.selection, self.store)
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


if __name__ == "__main__":
    unittest.main()
