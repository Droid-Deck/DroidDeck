"""Real login1 sleep handshakes; run with dbus-run-session and python3-gi."""
import os
from pathlib import Path
import subprocess
import signal
import tempfile
import time
import unittest

try:
    import gi
    gi.require_version("Gio", "2.0")
    from gi.repository import Gio, GLib
except ImportError:
    Gio = GLib = None

SCRIPT = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-login1"
NAME = "org.freedesktop.login1"
ROOT = "/org/freedesktop/login1"
IFACE = NAME + ".Manager"


@unittest.skipUnless(Gio and os.environ.get("DBUS_SESSION_BUS_ADDRESS"), "requires dbus-run-session and PyGObject")
class SleepHandshakeTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.dir = Path(self.directory.name)
        self.signals = []
        self.bus = Gio.DBusConnection.new_for_address_sync(
            os.environ["DBUS_SESSION_BUS_ADDRESS"],
            Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT | Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION,
            None, None)
        self.subscription = self.bus.signal_subscribe(
            NAME, IFACE, "PrepareForSleep", ROOT, None, Gio.DBusSignalFlags.NONE,
            lambda bus, sender, path, interface, name, body: self.signals.append(body.unpack()[0]))
        env = dict(os.environ, BL_LAUNCH_DIR=str(self.dir),
                   DBUS_SYSTEM_BUS_ADDRESS=os.environ["DBUS_SESSION_BUS_ADDRESS"])
        self.process = subprocess.Popen(["/usr/bin/python3", str(SCRIPT)], env=env,
                                        stdout=subprocess.DEVNULL)
        self.wait_for(lambda: (self.dir / "steam-sleep-ready").exists())

    def tearDown(self):
        os.kill(self.process.pid, signal.SIGCONT)
        self.process.terminate()
        self.process.wait(timeout=5)
        self.bus.signal_unsubscribe(self.subscription)
        self.bus.close_sync(None)
        self.directory.cleanup()

    def wait_for(self, condition, timeout=8):
        deadline = time.monotonic() + timeout
        while not condition():
            while GLib.MainContext.default().pending():
                GLib.MainContext.default().iteration(False)
            if time.monotonic() >= deadline:
                self.fail("timed out waiting for login1")
            time.sleep(0.01)

    def call(self, interface, method, args=None):
        return self.bus.call_sync(NAME, ROOT, interface, method, args, None,
                                  Gio.DBusCallFlags.NONE, 2000, None).unpack()

    def preparing(self):
        return self.call("org.freedesktop.DBus.Properties", "Get",
                         GLib.Variant("(ss)", (IFACE, "PreparingForSleep")))[0]

    def suspend(self):
        self.call(IFACE, "Suspend", GLib.Variant("(b)", (True,)))
        self.wait_for(lambda: (self.dir / "steam-sleep").exists())
        self.wait_for(lambda: self.signals == [True])
        return (self.dir / "steam-sleep").read_text().strip()

    def state(self, token, state):
        staged = self.dir / "steam-sleep-state.tmp"
        staged.write_text(token + ":" + state + "\n")
        staged.replace(self.dir / "steam-sleep-state")

    def test_resume_and_second_sleep(self):
        self.assertEqual(self.call(IFACE, "CanSuspend"), ("yes",))
        token = self.suspend()
        self.assertTrue(self.preparing())
        self.state(token, "paused")
        self.state("0" * 32, "awake")  # stale session acknowledgments cannot wake this one
        time.sleep(0.1)
        self.assertTrue(self.preparing())
        self.state(token, "awake")
        self.wait_for(lambda: self.signals == [True, False])
        self.assertFalse(self.preparing())
        self.signals.clear()
        second = self.suspend()
        self.assertNotEqual(token, second)
        self.state(second, "awake")
        self.wait_for(lambda: self.signals == [True, False])

    def test_missing_host_recovers(self):
        self.suspend()
        self.wait_for(lambda: self.signals == [True, False])
        self.assertFalse(self.preparing())
        self.assertFalse((self.dir / "steam-sleep").exists())

    def test_wake_after_service_was_frozen_past_ack_timeout(self):
        token = self.suspend()
        self.state(token, "paused")
        os.kill(self.process.pid, signal.SIGSTOP)
        time.sleep(6)
        self.state(token, "awake")
        os.kill(self.process.pid, signal.SIGCONT)
        self.wait_for(lambda: self.signals == [True, False])
        self.assertFalse(self.preparing())

    def test_suspend_after_service_was_frozen(self):
        os.kill(self.process.pid, signal.SIGSTOP)
        time.sleep(0.1)
        os.kill(self.process.pid, signal.SIGCONT)
        token = self.suspend()
        self.state(token, "awake")
        self.wait_for(lambda: self.signals == [True, False])
        self.assertFalse(self.preparing())

    def test_failed_pause_recovers_and_duplicate_is_rejected(self):
        token = self.suspend()
        with self.assertRaises(GLib.Error):
            self.call(IFACE, "Suspend", GLib.Variant("(b)", (True,)))
        self.state(token, "awake")
        self.wait_for(lambda: self.signals == [True, False])
        with self.assertRaises(GLib.Error):
            self.call(IFACE, "PowerOff", GLib.Variant("(b)", (True,)))


if __name__ == "__main__":
    unittest.main()
