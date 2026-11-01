import importlib.machinery
import importlib.util
import os
from pathlib import Path
import socket
import sys
import tempfile
import types
import unittest
from unittest import mock

SCRIPT = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-flatpak"


def load():
    repository = types.ModuleType("gi.repository")
    repository.Flatpak = types.SimpleNamespace()
    repository.Gio = types.SimpleNamespace()
    repository.GLib = types.SimpleNamespace(Error=RuntimeError)
    gi = types.ModuleType("gi")
    gi.require_version = lambda *args: None
    gi.repository = repository
    saved = {name: sys.modules.get(name) for name in ("gi", "gi.repository")}
    sys.modules["gi"] = gi
    sys.modules["gi.repository"] = repository
    try:
        loader = importlib.machinery.SourceFileLoader("droiddeck_flatpak_bus", str(SCRIPT))
        module = importlib.util.module_from_spec(importlib.util.spec_from_loader(loader.name, loader))
        loader.exec_module(module)
    finally:
        for name, value in saved.items():
            if value is None:
                sys.modules.pop(name, None)
            else:
                sys.modules[name] = value
    return module


class FakeDaemon:
    started = []

    def __init__(self, argv, **kwargs):
        conf = next(a for a in argv if a.startswith("--config-file=")).split("=", 1)[1]
        text = open(conf).read()
        self.path = text.split("<listen>unix:path=", 1)[1].split("</listen>", 1)[0]
        self.server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.server.bind(self.path)
        self.server.listen(4)
        self.running = True
        FakeDaemon.started.append(self)

    def poll(self):
        return None if self.running else 0

    def terminate(self):
        self.running = False
        self.server.close()

    def wait(self, timeout=None):
        return 0

    def kill(self):
        self.terminate()


class FlatpakSystemBusTest(unittest.TestCase):
    def setUp(self):
        self.module = load()
        self.tmp = tempfile.mkdtemp()
        self.env = mock.patch.dict(os.environ, {"DBUS_SYSTEM_BUS_ADDRESS": "unix:path=" + os.path.join(self.tmp, "missing")})
        self.env.start()
        FakeDaemon.started = []

    def tearDown(self):
        self.env.stop()
        os.rmdir(self.tmp)

    def test_a_reachable_system_bus_is_used_as_it_is(self):
        path = os.path.join(self.tmp, "system")
        server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        server.bind(path)
        server.listen(1)
        try:
            os.environ["DBUS_SYSTEM_BUS_ADDRESS"] = "unix:path=" + path
            with mock.patch.object(self.module.subprocess, "Popen", FakeDaemon):
                self.assertIsNone(self.module.private_system_bus())
            self.assertEqual([], FakeDaemon.started)
        finally:
            server.close()
            os.unlink(path)

    def test_without_a_system_bus_a_private_one_runs_for_the_command_and_is_removed_after(self):
        with mock.patch.object(self.module.shutil, "which", return_value="/usr/bin/dbus-daemon"), \
                mock.patch.object(self.module.subprocess, "Popen", FakeDaemon):
            bus = self.module.private_system_bus()
        self.assertIsNotNone(bus)
        _, work, conf, path = bus
        self.assertEqual("unix:path=" + path, os.environ["DBUS_SYSTEM_BUS_ADDRESS"])
        self.assertTrue(self.module.bus_reachable(path))
        self.module.stop_system_bus(bus)
        self.assertFalse(os.path.exists(work))

    def test_without_dbus_daemon_the_command_still_runs_and_warns(self):
        with mock.patch.object(self.module.shutil, "which", return_value=None), \
                mock.patch.object(self.module, "emit") as emit:
            self.assertIsNone(self.module.private_system_bus())
        emit.assert_called_once()

    def test_every_command_that_changes_apps_gets_a_system_bus(self):
        calls = []
        self.module.private_system_bus = lambda: calls.append("start") or "bus"
        self.module.stop_system_bus = lambda bus: calls.append("stop:%s" % bus)
        self.module.prepare_rootfs = lambda: None
        self.module.install = lambda app_id: calls.append("install") or 0
        self.module.updates = lambda: calls.append("updates") or 0
        with mock.patch.object(sys, "argv", ["droiddeck-flatpak", "install", "org.example.App"]):
            self.assertEqual(0, self.module.main())
        self.assertEqual(["start", "install", "stop:bus"], calls)
        calls.clear()
        with mock.patch.object(sys, "argv", ["droiddeck-flatpak", "updates"]):
            self.assertEqual(0, self.module.main())
        self.assertNotIn("start", calls)
        self.assertIn("updates", calls)


if __name__ == "__main__":
    unittest.main()
