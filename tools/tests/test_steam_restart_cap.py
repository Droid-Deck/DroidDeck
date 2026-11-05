import re
import shlex
import subprocess
import tempfile
import unittest
from pathlib import Path

SESSION = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-session"


def stuck_function():
    match = re.search(r"^steam_update_stuck\(\) \{\n.*?^\}\n", SESSION.read_text(), re.M | re.S)
    return match.group(0) if match else None


class SteamRestartCapTest(unittest.TestCase):
    def restarts(self, *seconds_up):
        function = stuck_function()
        self.assertIsNotNone(function, "droiddeck-session has no steam_update_stuck()")
        script = function + "steam_updates=0\nfor up in %s; do\n" % " ".join(map(str, seconds_up))
        script += "  if steam_update_stuck \"$up\"; then echo stop; else echo restart; fi\ndone\n"
        return subprocess.run(["bash", "-c", script], capture_output=True, text=True, timeout=30,
                              check=True).stdout.split()

    def installs(self, *steps):
        function = stuck_function()
        self.assertIsNotNone(function, "droiddeck-session has no steam_update_stuck()")
        with tempfile.TemporaryDirectory() as root:
            script = function + "steam_root=%s\nmkdir -p \"$steam_root/package\"\n" % shlex.quote(root)
            script += "installed=\"$steam_root/package/steam_client_linuxarm64.installed\"\n"
            script += "echo 0 > \"$installed\"\nsteam_updates=0\nsteam_installs=0\n"
            script += "steam_update_stamp=$(stat -c '%n %Y %s' \"$steam_root\"/package/*.installed)\n"
            for n, installed in enumerate(steps, 1):
                if installed:
                    script += "printf '%%0%dd' 0 > \"$installed\"\n" % (n + 1)
                script += "if steam_update_stuck 10; then echo stop; else echo restart; fi\n"
            return subprocess.run(["bash", "-c", script], capture_output=True, text=True, timeout=30,
                                  check=True).stdout.split()

    def test_restarts_that_each_install_an_update_go_on(self):
        self.assertEqual(["restart"] * 4, self.installs(True, False, True, False))

    def test_an_update_that_never_installs_is_stopped_on_the_third(self):
        self.assertEqual(["restart", "restart", "stop"], self.installs(False, False, False))

    def test_installs_that_never_settle_are_stopped(self):
        self.assertIn("stop", self.installs(*[True] * 12))

    def test_an_update_that_keeps_asking_at_once_is_stopped_on_the_third(self):
        self.assertEqual(["restart", "restart", "stop"], self.restarts(11, 13, 9))

    def test_restarts_of_a_client_that_stayed_up_are_never_stopped(self):
        self.assertEqual(["restart"] * 5, self.restarts(75, 4000, 90, 600, 61))

    def test_a_run_that_stayed_up_starts_the_count_again(self):
        self.assertEqual(["restart", "restart", "restart", "restart", "stop"], self.restarts(12, 10, 300, 14, 8))


if __name__ == "__main__":
    unittest.main()
