import importlib.machinery
import importlib.util
import runpy
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
APPINFO = runpy.run_path(str(Path(__file__).with_name("test_droiddeck_fex.py")))["AppInfoTest"].appinfo


def load(name):
    loader = importlib.machinery.SourceFileLoader(name.replace("-", "_"), str(BIN / name))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


library = load("droiddeck-steam-library")


def record(kind, installdir, name):
    return {"common": {"type": kind, "name": name}, "config": {"installdir": installdir}}


class AdoptTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        self.steam = root / "Steam"
        self.card = root / "card"
        self.common = self.card / "steamapps/common"
        self.common.mkdir(parents=True)
        (self.steam / "appcache").mkdir(parents=True)
        (self.steam / "steamapps").mkdir()
        self.write_info({
            10: record("Game", "Alpha", 'Alpha "Deluxe"'),
            20: record("Game", "Beta", "Beta"),
            30: record("Tool", "Proton Thing", "Proton Thing"),
            40: record("Game", "Shared", "Shared Game"),
            41: record("Demo", "Shared", "Shared Demo"),
            50: record("Game", "Twin", "Twin One"),
            51: record("Game", "Twin", "Twin Two"),
        })
        for patcher in (patch.object(library, "BIN_DIR", str(BIN)),
                        patch("builtins.print")):
            patcher.start()
            self.addCleanup(patcher.stop)

    def write_info(self, apps):
        (self.steam / "appcache/appinfo.vdf").write_bytes(APPINFO(apps))

    def folder(self, name, files=("data.bin",)):
        path = self.common / name
        path.mkdir(parents=True, exist_ok=True)
        for f in files:
            (path / f).write_text("x")

    def manifest(self, app, root=None):
        return (root or self.card) / ("steamapps/appmanifest_%s.acf" % app)

    def adopt(self):
        return library.adopt(str(self.steam), str(self.card))

    def test_matched_folder_gets_a_validating_manifest(self):
        self.folder("Alpha")
        self.assertEqual(self.adopt(), 1)
        text = self.manifest(10).read_text()
        for line in ('"appid" "10"', '"Universe" "1"', '"StateFlags" "4"', '"installdir" "Alpha"',
                     '"name" "Alpha \\"Deluxe\\""', '"AutoUpdateBehavior" "1"'):
            self.assertIn(line, text)
        self.assertNotIn("buildid", text)
        self.assertNotIn("InstalledDepots", text)

    def test_existing_manifest_is_untouched(self):
        self.folder("Alpha")
        self.manifest(10).write_text("mine")
        self.assertEqual(self.adopt(), 0)
        self.assertEqual(self.manifest(10).read_text(), "mine")

    def test_unmatched_empty_and_hidden_folders_are_skipped(self):
        self.folder("Unknown")
        self.folder(".hidden")
        (self.common / "Beta").mkdir()
        (self.common / "file.txt").write_text("x")
        self.assertEqual(self.adopt(), 0)
        self.assertEqual(list((self.card / "steamapps").glob("appmanifest_*")), [])

    def test_tool_type_app_is_skipped(self):
        self.folder("Proton Thing")
        self.assertEqual(self.adopt(), 0)
        self.assertFalse(self.manifest(30).exists())

    def test_game_wins_over_demo_and_ambiguous_games_are_skipped(self):
        self.folder("Shared")
        self.folder("Twin")
        self.assertEqual(self.adopt(), 1)
        self.assertTrue(self.manifest(40).exists())
        self.assertFalse(self.manifest(41).exists())
        self.assertFalse(self.manifest(50).exists() or self.manifest(51).exists())

    def test_app_installed_in_another_library_is_left_alone(self):
        self.folder("Alpha")
        other = self.steam / "steamapps/appmanifest_10.acf"
        other.write_text('"AppState"\n{\n\t"appid"\t"10"\n\t"installdir"\t"Elsewhere"\n}\n')
        self.assertEqual(self.adopt(), 0)
        self.assertFalse(self.manifest(10).exists())
        self.assertEqual(len(list(self.steam.glob("steamapps/appmanifest_*"))), 1)

    def test_adoption_is_idempotent_and_only_touches_the_given_library(self):
        self.folder("Alpha")
        self.folder("Beta")
        self.assertEqual(self.adopt(), 2)
        first = {n: self.manifest(n).read_text() for n in (10, 20)}
        self.assertEqual(self.adopt(), 0)
        self.assertEqual({n: self.manifest(n).read_text() for n in (10, 20)}, first)
        self.assertEqual(list(self.steam.glob("steamapps/appmanifest_*")), [])

    def test_main_adopts_every_registered_library(self):
        self.folder("Beta")
        listing = self.steam.parent / "libraries.json"
        listing.write_text('[{"path": "%s", "label": "SD Card"}]' % self.card)
        with patch.dict("os.environ", {"BL_STEAM_LIBRARIES": str(listing)}), \
                patch("sys.argv", ["droiddeck-steam-library", str(self.steam)]):
            library.main()
        self.assertTrue(self.manifest(20).exists())
        self.assertIn(str(self.card), (self.steam / "steamapps/libraryfolders.vdf").read_text())
        self.assertIn('"20"', (self.steam / "steamapps/libraryfolders.vdf").read_text())


if __name__ == "__main__":
    unittest.main()
