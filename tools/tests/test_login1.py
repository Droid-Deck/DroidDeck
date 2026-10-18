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
    ARGS = []
    AGENT_EXIT = None

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
        script = SCRIPT
        if self.AGENT_EXIT is not None:
            bin_dir = self.dir / "bin"
            bin_dir.mkdir()
            script = bin_dir / SCRIPT.name
            script.write_text(SCRIPT.read_text())
            agent = bin_dir / "droiddeck-agent"
            agent.write_text("#!/bin/sh\necho \"$@\" >> \"%s\"\nexit %d\n" % (self.dir / "agent-calls", self.AGENT_EXIT))
            agent.chmod(0o755)
        self.process = subprocess.Popen(["/usr/bin/python3", str(script), *self.ARGS], env=env,
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
        self.state("0" * 32, "awake")
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

    def test_legacy_upower_suspend_uses_the_same_wake_handshake(self):
        self.bus.call_sync("org.freedesktop.UPower", "/org/freedesktop/UPower",
                           "org.freedesktop.UPower", "Suspend", None, None,
                           Gio.DBusCallFlags.NONE, 2000, None)
        self.wait_for(lambda: (self.dir / "steam-sleep").exists())
        self.wait_for(lambda: self.signals == [True])
        token = (self.dir / "steam-sleep").read_text().strip()
        self.assertTrue(self.preparing())
        self.state(token, "awake")
        self.wait_for(lambda: self.signals == [True, False])
        self.assertFalse(self.preparing())

    def request_wake(self):
        staged = self.dir / "steam-wake.tmp"
        staged.write_text("wake\n")
        staged.replace(self.dir / "steam-wake")

    def request_native(self, token="1" * 32):
        staged = self.dir / "steam-native-sleep.tmp"
        staged.write_text(token + "\n")
        staged.replace(self.dir / "steam-native-sleep")
        return token

    def test_native_sleep_prepares_before_host_can_freeze(self):
        started = time.monotonic()
        token = self.request_native()
        self.wait_for(lambda: self.signals == [True])
        self.assertTrue(self.preparing())
        self.assertFalse((self.dir / "steam-native-ready").exists())
        self.assertFalse((self.dir / "steam-sleep").exists())
        self.wait_for(lambda: (self.dir / "steam-native-ready").exists())
        self.assertGreaterEqual(time.monotonic() - started, 1.4)
        self.assertEqual((self.dir / "steam-native-ready").read_text().strip(), token)
        self.state(token, "paused")
        self.state(token, "awake")
        self.wait_for(lambda: self.signals == [True, False])
        self.assertFalse(self.preparing())

    def test_return_during_native_preparation_cancels_late_pause(self):
        token = self.request_native()
        self.wait_for(lambda: self.signals == [True])
        self.state(token, "awake")
        self.wait_for(lambda: self.signals == [True, False])
        time.sleep(1.7)
        self.assertFalse((self.dir / "steam-native-ready").exists())
        self.assertFalse(self.preparing())

    def test_native_request_cancelled_before_helper_reads_it(self):
        os.kill(self.process.pid, signal.SIGSTOP)
        token = self.request_native()
        self.state(token, "awake")
        os.kill(self.process.pid, signal.SIGCONT)
        self.wait_for(lambda: not (self.dir / "steam-native-sleep").exists())
        time.sleep(1.7)
        self.assertFalse((self.dir / "steam-native-ready").exists())
        self.assertFalse(self.preparing())
        self.assertEqual(self.signals, [])

    def test_second_native_sleep_cannot_receive_first_preparation_callback(self):
        first = self.request_native()
        self.wait_for(lambda: self.signals == [True])
        self.state(first, "awake")
        self.wait_for(lambda: self.signals == [True, False])
        self.signals.clear()
        second = self.request_native("2" * 32)
        self.wait_for(lambda: (self.dir / "steam-native-ready").exists())
        self.assertEqual((self.dir / "steam-native-ready").read_text().strip(), second)
        self.state(second, "awake")
        self.wait_for(lambda: self.signals == [True, False])

    def test_native_sleep_with_missing_host_recovers(self):
        self.request_native()
        self.wait_for(lambda: self.signals == [True, False], timeout=9)
        self.assertFalse(self.preparing())
        self.assertFalse((self.dir / "steam-native-ready").exists())

    def test_host_resume_recovers_without_a_sleep_request(self):
        self.assertFalse(self.preparing())
        self.request_wake()
        self.wait_for(lambda: self.signals == [True, False])
        self.assertFalse((self.dir / "steam-wake").exists())

    def test_host_wake_survives_frozen_service(self):
        token = self.suspend()
        self.state(token, "paused")
        os.kill(self.process.pid, signal.SIGSTOP)
        self.request_wake()
        os.kill(self.process.pid, signal.SIGCONT)
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


class FakeAgentCase(unittest.TestCase):
    ARGS = []
    setUp = SleepHandshakeTest.setUp
    tearDown = SleepHandshakeTest.tearDown
    wait_for = SleepHandshakeTest.wait_for
    call = SleepHandshakeTest.call
    suspend = SleepHandshakeTest.suspend
    state = SleepHandshakeTest.state
    request_wake = SleepHandshakeTest.request_wake

    def asked(self):
        path = self.dir / "agent-calls"
        return path.read_text().split() if path.exists() else []


@unittest.skipUnless(Gio and os.environ.get("DBUS_SESSION_BUS_ADDRESS"), "requires dbus-run-session and PyGObject")
class AwakeSteamTest(FakeAgentCase):
    AGENT_EXIT = 5

    def test_resuming_an_awake_steam_sends_no_sleep_wake_pair(self):
        self.request_wake()
        self.wait_for(lambda: self.asked())
        deadline = time.monotonic() + 1.5
        while time.monotonic() < deadline:
            self.wait_for(lambda: True)
            time.sleep(0.05)
        self.assertEqual([], self.signals)
        self.assertEqual(["--steam-asleep"], self.asked())

    def test_a_pending_sleep_still_wakes_without_asking_steam(self):
        token = self.suspend()
        self.state(token, "paused")
        self.request_wake()
        self.wait_for(lambda: self.signals == [True, False])
        self.assertEqual([], self.asked())


@unittest.skipUnless(Gio and os.environ.get("DBUS_SESSION_BUS_ADDRESS"), "requires dbus-run-session and PyGObject")
class AsleepSteamTest(FakeAgentCase):
    AGENT_EXIT = 0

    def test_resuming_a_sleeping_steam_replays_the_pair(self):
        self.request_wake()
        self.wait_for(lambda: self.signals == [True, False])
        self.assertEqual(["--steam-asleep"], self.asked())


class WithNetworkManagerTest(SleepHandshakeTest):
    ARGS = ["--with-netmanager"]

    def owner(self, name):
        return self.bus.call_sync("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus",
                                  "GetConnectionUnixProcessID", GLib.Variant("(s)", (name,)), None,
                                  Gio.DBusCallFlags.NONE, 2000, None).unpack()[0]

    def test_one_process_owns_login1_and_networkmanager(self):
        self.wait_for(lambda: self.bus.call_sync(
            "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "NameHasOwner",
            GLib.Variant("(s)", ("org.freedesktop.NetworkManager",)), None,
            Gio.DBusCallFlags.NONE, 2000, None).unpack()[0])
        self.assertEqual(self.owner("org.freedesktop.NetworkManager"), self.process.pid)
        self.assertEqual(self.owner(NAME), self.process.pid)


if __name__ == "__main__":
    unittest.main()
