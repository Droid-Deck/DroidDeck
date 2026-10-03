import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

RUN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-appimage-run"
KEYS = ("QT_QPA_PLATFORM", "WAYLAND_DISPLAY", "SDL_VIDEODRIVER", "XDG_SESSION_TYPE", "QT_WAYLAND_DISABLE_WINDOWDECORATION", "DISABLE_GAMESCOPE_WSI")
QUICK = ("usr/lib/libQt6Quick.so.6", "usr/lib/libvulkan.so.1", "usr/plugins/platforms/libqwayland-generic.so")
RUN_MODE = {"GAMESCOPE_WAYLAND_DISPLAY": "gamescope-0", "WAYLAND_DISPLAY": "gamescope-0", "DISPLAY": ":0",
            "QT_QPA_PLATFORM": "xcb", "SDL_VIDEODRIVER": "x11", "XDG_SESSION_TYPE": "x11"}
DESKTOP = {"WAYLAND_DISPLAY": "wayland-1", "DISPLAY": ":1", "QT_QPA_PLATFORM": "wayland;xcb",
           "SDL_VIDEODRIVER": "wayland,x11", "XDG_SESSION_TYPE": "wayland"}


class AppImageRunTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.dir = self.tmp / "opennow"
        self.out = self.tmp / "env"
        app = self.dir / "app"
        app.mkdir(parents=True)
        run = app / "AppRun"
        run.write_text("#!/bin/sh\nfor k in %s; do eval \"echo $k=\\${$k-unset}\"; done > %s\n" % (" ".join(KEYS), self.out))
        run.chmod(0o755)

    def bundle(self, *paths):
        for path in paths:
            lib = self.dir / "app" / path
            lib.parent.mkdir(parents=True, exist_ok=True)
            lib.write_bytes(b"")

    def launch(self, session):
        env = {k: v for k, v in os.environ.items() if k not in KEYS + ("GAMESCOPE_WAYLAND_DISPLAY", "DISPLAY")}
        env.update(session, DBUS_SESSION_BUS_ADDRESS="unix:path=/dev/null")
        result = subprocess.run(["bash", str(RUN), str(self.dir)], env=env, capture_output=True, text=True, timeout=30)
        self.assertEqual(0, result.returncode, result.stderr)
        return dict(line.split("=", 1) for line in self.out.read_text().splitlines()), result.stdout

    def test_a_vulkan_qt_quick_app_draws_on_gamescope_wayland(self):
        self.bundle(*QUICK)
        env, log = self.launch(RUN_MODE)
        self.assertEqual("wayland;xcb", env["QT_QPA_PLATFORM"])
        self.assertEqual("gamescope-0", env["WAYLAND_DISPLAY"])
        self.assertEqual("wayland", env["SDL_VIDEODRIVER"])
        self.assertEqual("wayland", env["XDG_SESSION_TYPE"])
        self.assertEqual("1", env["QT_WAYLAND_DISABLE_WINDOWDECORATION"])
        self.assertEqual("1", env["DISABLE_GAMESCOPE_WSI"])
        self.assertIn("Qt wayland (wayland;xcb)", log)

    def test_a_quick_app_that_loads_vulkan_itself_is_found_by_its_binary(self):
        self.bundle(QUICK[0], QUICK[2])
        binary = self.dir / "app/usr/bin/opennow-qt"
        binary.parent.mkdir(parents=True, exist_ok=True)
        binary.write_bytes(b"\x7fELF\0_ZN7QWindow17setVulkanInstanceEP15QVulkanInstance\0")
        env, _ = self.launch(RUN_MODE)
        self.assertEqual("wayland;xcb", env["QT_QPA_PLATFORM"])
        self.assertEqual("1", env["DISABLE_GAMESCOPE_WSI"])

    def test_other_apps_stay_on_xwayland(self):
        self.bundle("usr/lib/libQt6Widgets.so.6", "usr/lib/libvulkan.so.1", "usr/plugins/platforms/libqxcb.so")
        env, _ = self.launch(RUN_MODE)
        self.assertEqual("xcb", env["QT_QPA_PLATFORM"])
        self.assertEqual("x11", env["SDL_VIDEODRIVER"])
        self.assertEqual("unset", env["QT_WAYLAND_DISABLE_WINDOWDECORATION"])
        self.assertEqual("unset", env["DISABLE_GAMESCOPE_WSI"])

    def test_a_quick_app_without_the_wayland_plugin_stays_on_xwayland(self):
        self.bundle(*QUICK[:2])
        env, log = self.launch(RUN_MODE)
        self.assertEqual("xcb", env["QT_QPA_PLATFORM"])
        self.assertIn("stays on X11", log)

    def test_the_desktop_keeps_its_decorations(self):
        self.bundle(*QUICK)
        env, _ = self.launch(DESKTOP)
        self.assertEqual("wayland;xcb", env["QT_QPA_PLATFORM"])
        self.assertEqual("wayland-1", env["WAYLAND_DISPLAY"])
        self.assertEqual("wayland", env["SDL_VIDEODRIVER"])
        self.assertEqual("unset", env["QT_WAYLAND_DISABLE_WINDOWDECORATION"])
        self.assertEqual("unset", env["DISABLE_GAMESCOPE_WSI"])

    def test_the_per_app_choice_wins(self):
        self.bundle(*QUICK)
        (self.dir / "qt").write_text("xcb\n")
        env, log = self.launch(DESKTOP)
        self.assertEqual("xcb", env["QT_QPA_PLATFORM"])
        self.assertIn("Qt xcb (xcb)", log)
        (self.dir / "qt").write_text("wayland\n")
        (self.dir / "app/usr/lib/libQt6Quick.so.6").unlink()
        env, _ = self.launch(RUN_MODE)
        self.assertEqual("wayland;xcb", env["QT_QPA_PLATFORM"])


if __name__ == "__main__":
    unittest.main()
