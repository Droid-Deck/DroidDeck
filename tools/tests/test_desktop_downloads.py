import os
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-downloads"


class DesktopDownloadsTest(unittest.TestCase):
    def run_script(self, home, shim=None):
        env = dict(os.environ, HOME=str(home))
        if shim:
            env["PATH"] = f"{shim}:{env['PATH']}"
        subprocess.run(["bash", str(SCRIPT)], env=env, check=True)

    def make_home(self, temp):
        home = Path(temp) / "home"
        for name in ("Download", "Documents"):
            (home / "Storage" / name).mkdir(parents=True)
        return home

    def test_links_to_the_android_folders_are_made(self):
        with tempfile.TemporaryDirectory() as temp:
            home = self.make_home(temp)
            self.run_script(home)
            self.assertEqual(str(home / "Storage/Download"), os.readlink(home / "Downloads"))
            self.assertEqual(str(home / "Storage/Documents"), os.readlink(home / "Documents"))

    def test_working_links_are_kept(self):
        with tempfile.TemporaryDirectory() as temp:
            home = self.make_home(temp)
            self.run_script(home)
            before = os.lstat(home / "Downloads").st_ino
            self.run_script(home)
            self.assertEqual(before, os.lstat(home / "Downloads").st_ino)

    def test_links_that_name_the_folder_but_cannot_be_walked_are_made_again(self):
        with tempfile.TemporaryDirectory() as temp:
            home = self.make_home(temp)
            for link in ("Downloads", "Documents"):
                os.symlink(str(Path(temp) / "gone"), home / link)
            shim = Path(temp) / "bin"
            shim.mkdir()
            (shim / "readlink").write_text(
                '#!/bin/bash\ncase "${@: -1}" in\n'
                f'  "{home}/Downloads") echo "{home}/Storage/Download" ;;\n'
                f'  "{home}/Documents") echo "{home}/Storage/Documents" ;;\n'
                '  *) exec /usr/bin/readlink "$@" ;;\nesac\n')
            (shim / "readlink").chmod(0o755)
            self.run_script(home, shim)
            for link, folder in (("Downloads", "Download"), ("Documents", "Documents")):
                self.assertEqual(str(home / "Storage" / folder), os.readlink(home / link))
                self.assertTrue((home / link / ".").is_dir())
            self.assertEqual([], sorted(p.name for p in home.iterdir() if ".old." in p.name))


if __name__ == "__main__":
    unittest.main()
