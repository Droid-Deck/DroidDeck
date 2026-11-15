import contextlib
import fcntl
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-flatpak-setup"


class FlatpakSetupTest(unittest.TestCase):
    def run_setup(self, uid, pacman, locked=False, busy=False):
        with tempfile.TemporaryDirectory() as directory, contextlib.ExitStack() as held:
            root = Path(directory)
            if locked:
                (root / "work/db").mkdir(parents=True)
                (root / "work/db/db.lck").write_text("")
            if busy:
                holder = held.enter_context(open(root / "work/setup.lock", "w"))
                fcntl.flock(holder, fcntl.LOCK_EX | fcntl.LOCK_NB)
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

    SYNC = ("db=; while [ $# -gt 0 ]; do [ \"$1\" = --dbpath ] && db=$2; shift; done\n"
            "[ -e \"$db/db.lck\" ] && { echo 'error: failed to synchronize all databases (unable to lock database)' >&2; exit 1; }\n"
            "mkdir -p \"$db/sync\" && touch \"$db/sync/extra.db\"")

    def test_a_lock_left_by_an_interrupted_run_is_cleared(self):
        code, out = self.run_setup(0, self.SYNC, locked=True)
        self.assertNotIn("unable to lock database", out)
        self.assertIn("== STEP Downloading Flatpak", out)

    def test_a_setup_that_is_still_running_keeps_its_lock(self):
        code, out = self.run_setup(0, self.SYNC, locked=True, busy=True)
        self.assertEqual(code, 1)
        self.assertIn("== FAIL another Flatpak setup is still running", out)
        self.assertNotIn("Reading the package lists", out)

if __name__ == "__main__":
    unittest.main()
