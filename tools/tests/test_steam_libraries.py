import importlib.machinery
import importlib.util
import json
import os
import re
import runpy
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"


def load(name):
    loader = importlib.machinery.SourceFileLoader(name.replace("-", "_"), str(BIN / name))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


library = load("droiddeck-steam-library")
desktop = load("droiddeck-desktop-games")
COMPAT = runpy.run_path(str(BIN / "steam-compatibility"))


def entry(index, path, label=""):
    return '\t"%d"\n\t{\n\t\t"path"\t\t"%s"\n\t\t"label"\t\t"%s"\n\t}\n' % (index, path, label)


def manifest(steamapps, appid, name="Game", installdir="Game", flags="4"):
    steamapps.mkdir(parents=True, exist_ok=True)
    (steamapps / ("appmanifest_%s.acf" % appid)).write_text(
        '"AppState"\n{\n\t"appid"\t\t"%s"\n\t"name"\t\t"%s"\n\t"StateFlags"\t\t"%s"\n\t"installdir"\t\t"%s"\n}\n'
        % (appid, name, flags, installdir))


class LibraryListTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.steam = self.root / "Steam"
        self.vdf = self.steam / "steamapps/libraryfolders.vdf"
        self.vdf.parent.mkdir(parents=True)
        self.games = self.root / "Games"
        self.card = self.root / "card"
        self.pc = self.games / "PC"
        self.old = self.games / "Old"
        self.user = self.root / "user-library"
        for folder in (self.pc, self.old, self.user, self.card):
            (folder / "steamapps").mkdir(parents=True)
        manifest(self.pc / "steamapps", 10)
        self.vdf.write_text('"libraryfolders"\n{\n' + entry(0, self.steam) + entry(1, self.old, "Old")
                            + entry(2, self.user, "Mine") + entry(3, self.card, "SD Card") + "}\n")
        self.listing = self.root / "steam-libraries.json"
        for target in (patch.object(library, "GAMES_DIR", str(self.games) + "/"),
                       patch.object(library, "LIBRARIES", ((str(self.card), "SD Card"),)),
                       patch("builtins.print")):
            target.start()
            self.addCleanup(target.stop)

    def run_main(self, listing=True):
        with patch.dict(os.environ), patch("sys.argv", ["droiddeck-steam-library", str(self.steam)]):
            os.environ.pop("BL_STEAM_LIBRARIES", None)
            if listing:
                os.environ["BL_STEAM_LIBRARIES"] = str(self.listing)
            library.main()
        return self.vdf.read_text()

    def paths(self, text):
        return [Path(p) for p in re.findall(r'"path"\s+"([^"]*)"', text)]

    def test_listed_games_folder_is_registered_and_the_rest_are_forgotten(self):
        self.listing.write_text(json.dumps([{"path": str(self.pc), "label": "PC (PNY USB drive)"}]))
        text = self.run_main()
        self.assertEqual([self.steam, self.user, self.pc], self.paths(text))
        self.assertIn('"label"\t\t"PC (PNY USB drive)"', text)
        self.assertIn('"10"\t\t"0"', text)
        self.assertIn('"label"\t\t"PC (PNY USB drive)"', (self.pc / "libraryfolder.vdf").read_text())

    def test_absent_listed_folder_is_forgotten_and_comes_back(self):
        self.listing.write_text(json.dumps([{"path": str(self.pc), "label": "PC"}, {"path": str(self.card), "label": "SD Card"}]))
        self.run_main()
        moved = self.root / "unplugged"
        self.pc.rename(moved)
        self.assertNotIn(self.pc, self.paths(self.run_main()))
        moved.rename(self.pc)
        self.assertIn(self.pc, self.paths(self.run_main()))
        self.assertIn(self.card, self.paths(self.vdf.read_text()))

    def test_without_a_list_only_the_card_is_managed(self):
        text = self.run_main(listing=False)
        self.assertEqual([self.steam, self.old, self.user, self.card], self.paths(text))

    def test_unreadable_list_keeps_games_folder_entries(self):
        self.listing.write_text("{not json")
        text = self.run_main()
        self.assertEqual([self.steam, self.old, self.user, self.card], self.paths(text))

    def test_labels_cannot_break_the_vdf(self):
        self.listing.write_text(json.dumps([{"path": str(self.pc), "label": 'P"C\\'}]))
        text = self.run_main()
        self.assertIn('"label"\t\t"P C "', text)


class CompatLibrariesTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.steam = self.root / "Steam"
        self.pc = self.root / "Games/My PC"
        (self.steam / "steamapps").mkdir(parents=True)
        (self.steam / "steamapps/libraryfolders.vdf").write_text(
            '"libraryfolders"\n{\n' + entry(0, self.steam) + entry(1, self.pc, "PC") + "}\n")

    def test_titles_and_tools_in_a_registered_library_are_found(self):
        manifest(self.steam / "steamapps", 20)
        manifest(self.pc / "steamapps", 30)
        manifest(self.pc / "steamapps", 3127680, installdir="FEX")
        self.assertEqual(["20", "30"], COMPAT["installed_apps"](str(self.steam)))
        self.assertEqual(["1628350"], COMPAT["fex_missing"](str(self.steam)))

    def test_newest_proton_across_libraries_wins(self):
        for root, build in ((self.steam, "100"), (self.pc, "200")):
            depot = root / "steamapps/common/Proton Experimental (ARM64)"
            (depot / "files/bin-arm64").mkdir(parents=True)
            (depot / "version").write_text(build + " x\n")
        self.assertEqual(str(self.pc / "steamapps/common/Proton Experimental (ARM64)"), COMPAT["find_source"](str(self.steam)))

    def test_missing_list_still_reads_the_client_library(self):
        (self.steam / "steamapps/libraryfolders.vdf").unlink()
        self.assertEqual(str(self.steam), COMPAT["libraries"](str(self.steam))[0])


class DesktopGamesTest(unittest.TestCase):
    def test_games_in_registered_libraries_get_entries(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            steam, pc = root / "Steam", root / "Games/PC"
            manifest(steam / "steamapps", 40, name="Internal")
            manifest(pc / "steamapps", 50, name="Outside")
            (steam / "steamapps/libraryfolders.vdf").write_text('"libraryfolders"\n{\n' + entry(0, steam) + entry(1, pc) + "}\n")
            with patch.object(desktop, "STEAM_ROOT", str(steam)), patch.object(desktop, "APPS_DIR", str(root / "apps")), \
                 patch.object(desktop, "LIBRARIES", ((str(steam), "Steam Games"), (str(root / "card"), "SD Games"))):
                self.assertEqual({40: "Internal", 50: "Outside"}, desktop.write_entries())


if __name__ == "__main__":
    unittest.main()
