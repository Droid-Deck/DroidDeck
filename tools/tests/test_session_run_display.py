import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SESSION = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-session"


def run_case():
    return re.search(r"^  run\)\n.*?^    ;;\n", SESSION.read_text(), re.M | re.S).group(0)


def steam_display():
    return re.search(r"^  steam\)\n(.*?)^    steam_x64=0\n", SESSION.read_text(), re.M | re.S).group(1)


class SessionRunDisplayTest(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.dir, True)
        self.target = self.dir / "target.sh"
        self.target.write_text("printf '%s\\n' \"${WAYLAND_DISPLAY-unset}\" \"${XDG_SESSION_TYPE-unset}\""
                               " > \"$1\"\n")

    def display(self, **env):
        base = {k: v for k, v in os.environ.items()
                if k not in ("GAMESCOPE_WAYLAND_DISPLAY", "WAYLAND_DISPLAY", "XDG_SESSION_TYPE",
                             "BL_LAUNCH_DIR")}
        seen = self.dir / "seen"
        script = ("program_cores() { :; }\nmode=run\ncase $mode in\n" + run_case() + "esac\n"
                  + "echo unreachable\n")
        run = subprocess.run(["bash", "-c", script, "droiddeck-session", str(self.target), str(seen)],
                             env={**base, **env}, capture_output=True, text=True, timeout=30)
        self.assertEqual(0, run.returncode, run.stderr)
        self.assertNotIn("unreachable", run.stdout)
        return seen.read_text().splitlines()

    def test_programs_under_gamescope_cannot_reach_a_wayland_server(self):
        self.assertEqual(["", "x11"], self.display(GAMESCOPE_WAYLAND_DISPLAY="gamescope-0",
                                                    WAYLAND_DISPLAY="gamescope-0", XDG_SESSION_TYPE="wayland"))

    def test_without_gamescope_the_display_is_left_alone(self):
        self.assertEqual(["wayland-0", "wayland"], self.display(WAYLAND_DISPLAY="wayland-0", XDG_SESSION_TYPE="wayland"))

    def steam_env(self, **env):
        base = {k: v for k, v in os.environ.items()
                if k not in ("GAMESCOPE_WAYLAND_DISPLAY", "WAYLAND_DISPLAY", "XDG_SESSION_TYPE")}
        script = steam_display() + ('printf "%s\\n" "${WAYLAND_DISPLAY-unset}" "${XDG_SESSION_TYPE-unset}"'
                                    ' "${MOZ_SHM_NO_SEALS-unset}" "${MOZ_DISABLE_CONTENT_SANDBOX-unset}"\n')
        run = subprocess.run(["bash", "-c", script], env={**base, **env}, capture_output=True, text=True, timeout=30)
        self.assertEqual(0, run.returncode, run.stderr)
        return run.stdout.splitlines()

    def test_steam_games_under_gamescope_cannot_reach_a_wayland_server(self):
        self.assertEqual(["", "x11", "1", "1"], self.steam_env(GAMESCOPE_WAYLAND_DISPLAY="gamescope-0",
                                                                WAYLAND_DISPLAY="gamescope-0", XDG_SESSION_TYPE="wayland"))

    def test_steam_without_gamescope_keeps_the_display(self):
        self.assertEqual(["wayland-0", "wayland", "1", "1"],
                         self.steam_env(WAYLAND_DISPLAY="wayland-0", XDG_SESSION_TYPE="wayland"))


if __name__ == "__main__":
    unittest.main()
