import os
import runpy
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
KEYS = ("TU_DEBUG", "DROIDDECK_GAME_TU_DEBUG")
SESSION = {"TU_DEBUG": "sysmem", "DROIDDECK_GAME_TU_DEBUG": ""}


class GameGpuLaunchersTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.out = self.tmp / "env"
        self.tools = self.tmp / "tools"
        self.tools.mkdir()
        self.app = self.tmp / "deploy"
        (self.app / "files/bin").mkdir(parents=True)
        self.report = self.tools / "report"
        self.report.write_text("#!/bin/sh\nfor k in %s; do eval \"echo $k=\\${$k-unset}\"; done > %s\n"
                               % (" ".join(KEYS), self.out))
        self.report.chmod(0o755)
        flatpak = self.tools / "flatpak"
        flatpak.write_text("#!/bin/sh\n"
                           "if [ \"$1\" = info ]; then [ \"$3\" = org.example.App ] && echo %s; exit 0; fi\n"
                           "exec %s\n" % (self.app, self.report))
        flatpak.chmod(0o755)

    def chromium(self):
        (self.app / "files/lib/browser").mkdir(parents=True)
        (self.app / "files/lib/browser/chrome_100_percent.pak").write_bytes(b"")

    def run_env(self, command, session):
        env = {k: v for k, v in os.environ.items() if k not in KEYS + ("DBUS_SESSION_BUS_ADDRESS", "GAMESCOPE_WAYLAND_DISPLAY")}
        env.update(session, PATH="%s:%s" % (self.tools, env.get("PATH", "/usr/bin:/bin")),
                   DBUS_SESSION_BUS_ADDRESS="unix:path=/dev/null")
        result = subprocess.run(command, env=env, capture_output=True, text=True, timeout=30)
        self.assertEqual(0, result.returncode, result.stderr)
        return dict(line.split("=", 1) for line in self.out.read_text().splitlines())

    def wrapper(self, *args, session=SESSION):
        return self.run_env([sys.executable, str(BIN / "droiddeck-game-gpu"), *args, "--", str(self.report)], session)

    def flatpak(self, session=SESSION):
        return self.run_env(["bash", str(BIN / "droiddeck-flatpak-run"), "--command=app", "org.example.App"], session)

    def test_the_wrapper_gives_a_program_the_game_setting(self):
        self.assertEqual({"TU_DEBUG": "unset", "DROIDDECK_GAME_TU_DEBUG": "unset"}, self.wrapper())
        self.assertEqual("gmem", self.wrapper(session={"TU_DEBUG": "sysmem", "DROIDDECK_GAME_TU_DEBUG": "gmem"})["TU_DEBUG"])

    def test_the_wrapper_keeps_the_session_setting_for_chromium(self):
        self.assertEqual({"TU_DEBUG": "sysmem", "DROIDDECK_GAME_TU_DEBUG": "unset"}, self.wrapper("--chromium"))
        self.assertEqual("unset", self.wrapper("--for", str(self.app / "files"))["TU_DEBUG"])
        self.chromium()
        self.assertEqual("sysmem", self.wrapper("--for", str(self.app / "files"))["TU_DEBUG"])

    def test_without_the_game_setting_the_wrapper_changes_nothing(self):
        self.assertEqual("sysmem", self.wrapper(session={"TU_DEBUG": "sysmem"})["TU_DEBUG"])
        self.assertEqual("unset", self.wrapper(session={})["TU_DEBUG"])

    def test_the_wrapper_reports_a_missing_program(self):
        result = subprocess.run([sys.executable, str(BIN / "droiddeck-game-gpu"), "--", str(self.tmp / "missing")],
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(127, result.returncode)
        self.assertEqual(2, subprocess.run([sys.executable, str(BIN / "droiddeck-game-gpu")], capture_output=True,
                                           timeout=30).returncode)

    def test_a_flatpak_app_gets_the_game_setting(self):
        self.assertEqual({"TU_DEBUG": "unset", "DROIDDECK_GAME_TU_DEBUG": "unset"}, self.flatpak())
        self.assertEqual("nolrz", self.flatpak({"TU_DEBUG": "sysmem", "DROIDDECK_GAME_TU_DEBUG": "nolrz"})["TU_DEBUG"])

    def test_a_chromium_flatpak_keeps_the_session_setting(self):
        self.chromium()
        self.assertEqual({"TU_DEBUG": "sysmem", "DROIDDECK_GAME_TU_DEBUG": "unset"}, self.flatpak())

    def test_a_flatpak_without_the_game_setting_is_unchanged(self):
        self.assertEqual("sysmem", self.flatpak({"TU_DEBUG": "sysmem"})["TU_DEBUG"])

    def test_a_snap_gets_the_game_setting_unless_it_is_chromium(self):
        try:
            snap_run = runpy.run_path(str(BIN / "droiddeck-snap-run"))
        except ImportError:
            self.skipTest("PyYAML is not installed")
        gpu = snap_run["game_gpu"]
        snap = self.app / "files"
        self.assertEqual({"KEEP": "1"}, gpu(dict(SESSION, KEEP="1"), str(snap)))
        self.assertEqual({"TU_DEBUG": "nolrz"}, gpu({"TU_DEBUG": "sysmem", "DROIDDECK_GAME_TU_DEBUG": "nolrz"}, str(snap)))
        self.chromium()
        self.assertEqual({"TU_DEBUG": "sysmem"}, gpu(dict(SESSION), str(snap)))


if __name__ == "__main__":
    unittest.main()
