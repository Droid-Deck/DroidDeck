"""BlueZ adapter contract over a real D-Bus (requires python3-gi and dbus).

Run on Linux: dbus-run-session -- python3 -m unittest discover -s tools/tests -p test_bluez.py
"""
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest

try:
    import gi
    gi.require_version("Gio", "2.0")
    from gi.repository import Gio, GLib
except ImportError:
    Gio = GLib = None

SCRIPT = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-bluez"
if Gio:
    loader = importlib.machinery.SourceFileLoader("bluez", str(SCRIPT))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    bz = importlib.util.module_from_spec(spec)
    loader.exec_module(bz)

ON = {"airplaneMode": False, "bluetooth": {"present": True, "enabled": True, "name": "Phone"}}


@unittest.skipUnless(Gio and os.environ.get("DBUS_SESSION_BUS_ADDRESS"), "requires dbus-run-session and PyGObject")
class BluezBusTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.state = Path(self.temp.name) / "radios.json"
        self.request = self.state.with_suffix(".request")
        self.hold = self.state.with_suffix(".hold")
        self.write(ON)
        self.bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
        env = dict(os.environ, DBUS_SYSTEM_BUS_ADDRESS=os.environ["DBUS_SESSION_BUS_ADDRESS"])
        launch = ("import runpy,sys; from pathlib import Path; n=runpy.run_path(sys.argv[1]); g=n['main'].__globals__; "
                  "p=Path(sys.argv[2]); g['STATE']=p; g['REQUEST']=p.with_suffix('.request'); "
                  "g['RADIO_HOLD']=p.with_suffix('.hold'); n['main']()")
        self.process = subprocess.Popen([sys.executable, "-c", launch, str(SCRIPT), str(self.state)], env=env,
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.wait_for(lambda: self.adapter() is not None)

    def tearDown(self):
        self.process.terminate()
        _, stderr = self.process.communicate(timeout=5)
        self.temp.cleanup()
        self.assertEqual(stderr, b"", stderr.decode())

    def write(self, state):
        staged = self.state.with_suffix(".staged")
        staged.write_text(json.dumps(state))
        staged.replace(self.state)

    def call(self, path, interface, method, parameters=None):
        return self.bus.call_sync(bz.NAME, path, interface, method, parameters, None,
                                  Gio.DBusCallFlags.NONE, 1000, None).unpack()

    def adapter(self):
        try:
            return self.call("/", bz.OBJECT_MANAGER, "GetManagedObjects")[0].get(bz.ADAPTER, {}).get(bz.ADAPTER_INTERFACE)
        except GLib.Error:
            return None

    def powered(self, value):
        return GLib.Variant("(ssv)", (bz.ADAPTER_INTERFACE, "Powered", GLib.Variant("b", value)))

    def wait_for(self, condition, timeout=6):
        deadline = time.monotonic() + timeout
        while not condition():
            if self.process.poll() is not None:
                self.fail(self.process.communicate()[1].decode())
            if time.monotonic() >= deadline:
                self.fail("timed out")
            time.sleep(0.05)

    def test_adapter_follows_android(self):
        adapter = self.adapter()
        self.assertTrue(adapter["Powered"])
        self.assertEqual(adapter["Name"], "Phone")
        self.call("/org/bluez", bz.AGENTS, "RegisterAgent", GLib.Variant("(os)", ("/org/steam/btagent", "KeyboardDisplay")))
        with self.assertRaises(GLib.Error):
            self.call(bz.ADAPTER, bz.ADAPTER_INTERFACE, "StartDiscovery")
        self.write(dict(ON, bluetooth=dict(ON["bluetooth"], enabled=False)))
        self.wait_for(lambda: self.adapter()["Powered"] is False)
        self.assertEqual(self.adapter()["PowerState"], "off")
        self.write(dict(ON, bluetooth={"present": False, "enabled": False}))
        self.wait_for(lambda: self.adapter() is None)
        self.write(ON)
        self.wait_for(lambda: self.adapter() is not None)

    def test_powered_switch_waits_for_the_radio_hold(self):
        self.call(bz.ADAPTER, bz.PROPERTIES, "Set", self.powered(False))
        self.assertFalse(self.request.exists())
        self.hold.write_text(str(time.time() + 60))
        self.call(bz.ADAPTER, bz.PROPERTIES, "Set", self.powered(False))
        self.assertFalse(self.request.exists())
        self.hold.write_text("0")
        self.call(bz.ADAPTER, bz.PROPERTIES, "Set", self.powered(False))
        self.assertEqual(self.request.read_text(), "off")
        self.call(bz.ADAPTER, bz.PROPERTIES, "Set", self.powered(True))
        self.assertEqual(self.request.read_text(), "on")
        with self.assertRaises(GLib.Error):
            self.call(bz.ADAPTER, bz.PROPERTIES, "Set",
                      GLib.Variant("(ssv)", (bz.ADAPTER_INTERFACE, "Discoverable", GLib.Variant("b", True))))


if __name__ == "__main__":
    unittest.main()
