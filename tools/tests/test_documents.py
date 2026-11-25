import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest

try:
    import gi
    gi.require_version("Gio", "2.0")
    from gi.repository import Gio, GLib
except ImportError:
    Gio = GLib = None

SCRIPT = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-documents"
NAME = "org.freedesktop.portal.Documents"
PATH = "/org/freedesktop/portal/documents"
REUSE_EXISTING = 1
PERSISTENT = 2
AS_NEEDED_BY_APP = 4
DIRECTORY = 8


@unittest.skipUnless(Gio and os.environ.get("DBUS_SESSION_BUS_ADDRESS"), "requires dbus-run-session and PyGObject")
class DocumentStoreTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.dir = Path(self.directory.name)
        self.file = self.dir / "picked.iso"
        self.file.write_text("data")
        self.bus = Gio.DBusConnection.new_for_address_sync(
            os.environ["DBUS_SESSION_BUS_ADDRESS"],
            Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT | Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION,
            None, None)
        self.process = subprocess.Popen(["/usr/bin/python3", str(SCRIPT)], stdout=subprocess.DEVNULL)
        deadline = time.monotonic() + 10
        while not self.owned():
            self.assertIsNone(self.process.poll())
            self.assertLess(time.monotonic(), deadline)
            time.sleep(0.05)

    def tearDown(self):
        self.process.terminate()
        self.process.wait(timeout=5)
        self.bus.close_sync(None)
        self.directory.cleanup()

    def owned(self):
        reply = self.bus.call_sync("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus",
                                   "NameHasOwner", GLib.Variant("(s)", (NAME,)), None,
                                   Gio.DBusCallFlags.NONE, -1, None)
        return reply.unpack()[0]

    def call(self, method, signature, args, paths=()):
        fds = Gio.UnixFDList.new()
        opened = [os.open(p, os.O_PATH | os.O_CLOEXEC) for p in paths]
        try:
            for fd in opened:
                fds.append(fd)
            reply, _ = self.bus.call_with_unix_fd_list_sync(
                NAME, PATH, NAME, method, GLib.Variant(signature, args), None, Gio.DBusCallFlags.NONE, -1,
                fds, None)
            return reply.unpack()
        finally:
            for fd in opened:
                os.close(fd)

    def error_of(self, *args, **kwargs):
        with self.assertRaises(GLib.Error) as caught:
            self.call(*args, **kwargs)
        return Gio.DBusError.get_remote_error(caught.exception)

    def test_a_picked_file_is_handed_back_in_place(self):
        got = self.call("AddFull", "(ahusas)", ([0], REUSE_EXISTING | PERSISTENT | AS_NEEDED_BY_APP, "org.example.App",
                                                ["read", "write"]), [self.file])
        self.assertEqual(([""], {}), got)

    def test_a_saved_file_name_is_handed_back_in_place(self):
        got = self.call("AddNamedFull", "(hayusas)", (0, b"new.txt\0", AS_NEEDED_BY_APP, "org.example.App", ["read"]),
                        [self.dir])
        self.assertEqual(("", {}), got)

    def test_a_picked_folder_needs_the_directory_flag(self):
        flags = REUSE_EXISTING | AS_NEEDED_BY_APP
        self.assertEqual(([""], {}), self.call("AddFull", "(ahusas)", ([0], flags | DIRECTORY, "", []), [self.dir]))
        self.assertEqual("org.freedesktop.portal.Error.InvalidArgument",
                         self.error_of("AddFull", "(ahusas)", ([0], flags, "", []), [self.dir]))
        self.assertEqual("org.freedesktop.portal.Error.InvalidArgument",
                         self.error_of("AddFull", "(ahusas)", ([0], flags | DIRECTORY, "", []), [self.file]))

    def test_documents_that_would_need_the_store_mount_are_refused(self):
        self.assertEqual("org.freedesktop.portal.Error.NotAllowed",
                         self.error_of("AddFull", "(ahusas)", ([0], REUSE_EXISTING, "", []), [self.file]))
        self.assertEqual("org.freedesktop.portal.Error.NotAllowed",
                         self.error_of("Add", "(hbb)", (0, True, False), [self.file]))
        self.assertEqual("org.freedesktop.portal.Error.Failed", self.error_of("GetMountPoint", "()", ()))

    def test_a_missing_descriptor_is_refused(self):
        self.assertEqual("org.freedesktop.portal.Error.InvalidArgument",
                         self.error_of("AddFull", "(ahusas)", ([3], AS_NEEDED_BY_APP, "", []), [self.file]))

    def test_no_documents_are_listed(self):
        self.assertEqual(("",), self.call("Lookup", "(ay)", (str(self.file).encode() + b"\0",)))
        self.assertEqual(({},), self.call("List", "(s)", ("org.example.App",)))
        version = self.bus.call_sync(NAME, PATH, "org.freedesktop.DBus.Properties", "Get",
                                     GLib.Variant("(ss)", (NAME, "version")), None, Gio.DBusCallFlags.NONE, -1, None)
        self.assertEqual(5, version.unpack()[0])


if __name__ == "__main__":
    unittest.main()
