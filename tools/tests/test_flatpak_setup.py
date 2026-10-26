import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-flatpak-setup"


class FlatpakSetupTest(unittest.TestCase):
    def run_setup(self, uid, pacman):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bin_dir = root / "bin"
            bin_dir.mkdir()
            stubs = {"id": "echo %d" % uid, "curl": "exit 7", "pacman": pacman, "pacman-key": "exit 0"}
            for name, body in stubs.items():
                (bin_dir / name).write_text("#!/bin/sh\n%s\n" % body)
                (bin_dir / name).chmod(0o755)
            script = root / "setup"
            script.write_text(SCRIPT.read_text().replace("WORK=/var/cache/droiddeck-flatpak", "WORK=%s/work" % root)
                              .replace("/etc/pacman.d/gnupg/pubring.gpg", str(root / "pubring.gpg"))
                              .replace("    /etc/pacman.conf >", "    %s >" % (root / "pacman.conf")))
            (root / "pacman.conf").write_text("[options]\n[core]\nInclude = /etc/pacman.d/mirrorlist\n")
            (root / "pubring.gpg").write_text("")
            env = dict(os.environ, PATH="%s:%s" % (bin_dir, os.environ["PATH"]))
            result = subprocess.run(["bash", str(script)], env=env, capture_output=True, text=True, timeout=30)
            return result.returncode, result.stdout

    def test_a_manual_run_without_root_says_so(self):
        code, out = self.run_setup(1000, "exit 0")
        self.assertEqual(code, 1)
        self.assertIn("== FAIL needs root", out)
        self.assertNotIn("Reading the package lists", out)

    def test_an_unreachable_mirror_reports_pacmans_error(self):
        code, out = self.run_setup(0, "echo \"error: failed retrieving file 'core.db' : Could not resolve host\" >&2; exit 1")
        self.assertEqual(code, 1)
        self.assertIn("== FAIL could not reach the Arch Linux ARM mirrors (error: failed retrieving file 'core.db' : "
                      "Could not resolve host)", out)


if __name__ == "__main__":
    unittest.main()
