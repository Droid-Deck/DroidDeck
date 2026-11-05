import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-library-perms"


class LibraryPermsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.restore)
        self.root = Path(self.temp.name)
        self.home = self.root / "home"
        self.steam = self.home / ".local/share/Steam"
        self.card = self.root / "card"
        self.stores = self.root / "stores"
        (self.steam / "steamapps").mkdir(parents=True)
        (self.steam / "steamapps/libraryfolders.vdf").write_text(
            '"libraryfolders"\n{\n\t"0"\n\t{\n\t\t"path"\t\t"%s"\n\t}\n\t"1"\n\t{\n\t\t"path"\t\t"%s"\n\t}\n}\n'
            % (self.steam, self.card))
        self.locked = []

    def restore(self):
        for path in self.locked:
            if path.exists() and not path.is_symlink():
                path.chmod(0o700 if path.is_dir() else 0o600)
        for path in sorted(self.root.rglob("*"), key=lambda p: len(p.parts), reverse=True):
            if path.is_dir() and not path.is_symlink():
                path.chmod(0o700)
        self.temp.cleanup()

    def file(self, path, mode=0o400):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("x")
        path.chmod(mode)
        self.locked.append(path)
        return path

    def folder(self, path, mode=0o500):
        path.mkdir(parents=True, exist_ok=True)
        self.locked.append(path)
        return path

    def run_script(self):
        env = dict(os.environ, HOME=str(self.home), DROIDDECK_STORES_ROOT=str(self.stores))
        for path in reversed(self.locked):
            if path.is_dir():
                path.chmod(0o500)
        return subprocess.run([sys.executable, str(SCRIPT)], env=env, check=True, capture_output=True, text=True).stdout

    def mode(self, path):
        return stat.S_IMODE(os.lstat(path).st_mode)

    def test_read_only_game_files_and_folders_become_writable_in_every_library(self):
        ini = self.file(self.steam / "steamapps/compatdata/3712566526/pfx/drive_c/users/steamuser/Documents/My Games/FalloutNV/FALLOUT.INI")
        data = self.folder(self.steam / "steamapps/common/Game/Data")
        game = self.file(data / "game.esm", 0o444)
        card = self.file(self.card / "steamapps/common/Other/settings.ini")
        gog = self.file(self.home / "Games/Stores/GOG/Fallout New Vegas/Fallout_default.ini")
        store = self.file(self.stores / "abc/Game/config.cfg")
        out = self.run_script()
        self.assertEqual(self.mode(ini) & 0o600, 0o600)
        self.assertEqual(self.mode(game), 0o644)
        self.assertEqual(self.mode(data) & 0o700, 0o700)
        for path in (card, gog, store):
            self.assertEqual(self.mode(path) & 0o600, 0o600, path)
        self.assertIn("made writable", out)

    def test_proton_and_steam_runtimes_are_left_as_valve_ships_them(self):
        proton = self.file(self.steam / "steamapps/common/Proton Experimental (ARM64)/files/lib/wine/a.so", 0o444)
        runtime = self.file(self.card / "steamapps/common/SteamLinuxRuntime_sniper/run", 0o555)
        nested = self.file(self.steam / "steamapps/common/Game/Proton notes.txt")
        self.run_script()
        self.assertEqual(self.mode(proton), 0o444)
        self.assertEqual(self.mode(runtime), 0o555)
        self.assertEqual(self.mode(nested) & 0o600, 0o600)

    def test_symlinks_are_not_followed(self):
        outside = self.file(self.root / "outside/keep.ini")
        link = self.steam / "steamapps/compatdata/1/pfx/dosdevices/z:"
        link.parent.mkdir(parents=True)
        link.symlink_to(self.root / "outside")
        self.run_script()
        self.assertEqual(self.mode(outside), 0o400)

    def test_writable_files_are_untouched_and_missing_libraries_are_fine(self):
        fine = self.file(self.steam / "steamapps/common/Game/ok.ini", 0o640)
        out = self.run_script()
        self.assertEqual(self.mode(fine), 0o640)
        self.assertIn("0 files and folders made writable", out)


if __name__ == "__main__":
    unittest.main()
