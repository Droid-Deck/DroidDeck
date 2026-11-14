import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SESSION = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-session"

FAKE_DAEMON = """#!/usr/bin/env python3
import socket, sys, time
address = next(a for a in sys.argv if a.startswith("--address="))
path = address.split("unix:path=", 1)[1]
open(sys.argv[0] + ".args", "w").write(" ".join(sys.argv[1:]))
s = socket.socket(socket.AF_UNIX)
s.bind(path)
time.sleep(5)
"""


class SessionBusTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root, True)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.runtime = self.root / "run"
        self.runtime.mkdir()
        daemon = self.bin / "dbus-daemon"
        daemon.write_text(FAKE_DAEMON)
        daemon.chmod(0o755)

    def run_block(self, env_address=""):
        match = re.search(r'^ *if \[ -z "\$\{DBUS_SESSION_BUS_ADDRESS:-\}" \]; then\n.*?^ *fi\n *fi\n',
                          SESSION.read_text(), re.M | re.S)
        self.assertIsNotNone(match)
        script = "nap() { sleep \"$1\"; }\n" + match.group(0) + 'echo "ADDRESS=$DBUS_SESSION_BUS_ADDRESS"\nkill %1 2>/dev/null\n'
        env = {"PATH": "%s:/usr/bin:/bin" % self.bin, "XDG_RUNTIME_DIR": str(self.runtime)}
        if env_address:
            env["DBUS_SESSION_BUS_ADDRESS"] = env_address
        return subprocess.run(["bash", "-c", script], env=env, capture_output=True, text=True, timeout=30).stdout

    def test_steam_mode_exports_its_own_session_bus(self):
        out = self.run_block()
        self.assertIn("ADDRESS=unix:path=%s/bus" % self.runtime, out)
        self.assertIn("--session", (self.bin / "dbus-daemon.args").read_text())

    def test_an_existing_session_bus_is_kept(self):
        out = self.run_block("unix:path=/somewhere/else")
        self.assertIn("ADDRESS=unix:path=/somewhere/else", out)
        self.assertFalse((self.bin / "dbus-daemon.args").exists())


if __name__ == "__main__":
    unittest.main()
