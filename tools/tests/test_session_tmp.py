import os
import re
import shutil
import socket
import subprocess
import tempfile
import unittest
from pathlib import Path

SESSION = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-session"

STEAM = ["mangohud.Ab3dE6gH", ".com.valvesoftware.Steam.x1Y2z3/", "steam_chrome_shmem_uid10480_0", "steamADfAnd/"]
CHROMIUM = ["scoped_dir3yu9Xt/SingletonSocket"]
APPIMAGE = ["droiddeck-appimage.19PoOD"]
BUS = ["dbus-1zWfOP1IaR="]  # = a socket
KDE = ["plasma-csd-generator.BVWIWA/"]
STALE = STEAM + CHROMIUM + APPIMAGE + BUS + KDE
KEPT = ["droiddeck-mangohud.conf", ".X11-unix/", "steamworks", "steamABCDEF/inside", "notes.txt",
        "mangohud.conf", ".wine-10480/", "dbus-1zWfOP1IaR.log", "dbus-notasocket",
        "plasma-csd-generator.CQGZSw/kept"]


def sweep_function(tmp):
    match = re.search(r"^clear_stale_tmp\(\) \{\n.*?^\}\n", SESSION.read_text(), re.M | re.S)
    return match.group(0).replace("find /tmp ", "find %s " % tmp)


class SessionTmpTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root, True)
        self.tmp = self.root / "tmp"
        self.tmp.mkdir()
        for name in STALE + KEPT:
            path = self.tmp / name
            if name.endswith("/"):
                path.mkdir(parents=True)
            elif name.endswith("="):
                listener = socket.socket(socket.AF_UNIX)
                listener.bind(str(self.tmp / name[:-1]))
                listener.close()
            else:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("x")
        self.bin = self.root / "bin"
        self.bin.mkdir()

    def sweep(self, running=""):
        pgrep = self.bin / "pgrep"
        pgrep.write_text("#!/bin/sh\nprintf '%s' '" + running + "'\n")
        pgrep.chmod(0o755)
        env = dict(os.environ, PATH="%s:%s" % (self.bin, os.environ["PATH"]))
        return subprocess.run(["bash", "-c", sweep_function(self.tmp) + "clear_stale_tmp"], env=env,
                              capture_output=True, text=True, timeout=30, check=True).stdout

    def exists(self, name):
        return os.path.lexists(self.tmp / name.rstrip("=").split("/")[0])

    def test_what_killed_sessions_left_goes_and_nothing_else(self):
        self.assertEqual("== /tmp: cleared 8 entries earlier sessions left\n", self.sweep())
        for name in STALE:
            self.assertFalse(self.exists(name), name)
        for name in KEPT:
            self.assertTrue((self.tmp / name).exists(), name)

    def kept_while(self, running, owned):
        self.sweep(running)
        for name in STALE:
            self.assertEqual(name in owned, self.exists(name), name)

    def test_a_running_client_keeps_its_own(self):
        self.kept_while("1234 /root/.local/share/Steam/steamrtarm64/steam -gamepadui\n", STEAM)

    def test_a_running_chromium_keeps_its_singleton(self):
        self.kept_while("88 /opt/Heroic/heroic --type=renderer --enable-features=x\n", CHROMIUM)

    def test_a_running_appimage_keeps_its_stamp(self):
        self.kept_while("77 /bin/bash /usr/local/bin/droiddeck-appimage-run /root/Apps/X.AppImage\n", APPIMAGE)

    def test_a_running_session_bus_keeps_its_socket(self):
        self.kept_while("66 dbus-daemon --config-file=/usr/share/dbus-1/session.conf --nofork --session\n", BUS)

    def test_the_system_bus_is_not_a_session_bus(self):
        self.kept_while("55 dbus-daemon --config-file=/usr/local/share/droiddeck/system-bus.conf --nofork\n", [])

    def test_a_running_desktop_keeps_its_decorations(self):
        self.kept_while("44 kwin_wayland --xwayland\n", KDE)

    def test_a_clean_tmp_says_nothing(self):
        self.sweep()
        self.assertEqual("", self.sweep())


if __name__ == "__main__":
    unittest.main()
