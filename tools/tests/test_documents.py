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
        self.proc = self.dir / "proc"
        self.sandbox = self.dir / "sandbox"
        (self.proc / "1234").mkdir(parents=True)
        (self.sandbox / self.dir.relative_to("/")).mkdir(parents=True)
        (self.sandbox / ".flatpak-info").write_text("[Application]\nname=org.example.App\n")
        (self.proc / "1234/root").symlink_to(self.sandbox)
        self.visible = self.sandbox / self.dir.relative_to("/") / "picked.iso"
        self.visible.write_text("data")
        self.bus = Gio.DBusConnection.new_for_address_sync(
            os.environ["DBUS_SESSION_BUS_ADDRESS"],
            Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT | Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION,
            None, None)
        self.alias = self.dir / "alias"
        self.shared = self.dir / "shared"
        self.alias.mkdir()
        (self.alias / "game.iso").write_text("data")
        (self.sandbox / self.shared.relative_to("/")).mkdir(parents=True)
        (self.sandbox / self.shared.relative_to("/") / "game.iso").write_text("data")
        (self.sandbox / self.alias.relative_to("/")).mkdir(parents=True)
        (self.sandbox / self.alias.relative_to("/") / "inside.iso").write_text("data")
        (self.alias / "inside.iso").write_text("data")
        (self.alias / "private.iso").write_text("data")
        (self.dir / "rootfs").mkdir()
        (self.sandbox / self.dir.relative_to("/") / "rootfs/alias").mkdir(parents=True)
        (self.sandbox / self.dir.relative_to("/") / "rootfs/alias/private.iso").write_text("data")
        env = dict(os.environ, DDNATV_ROOT=str(self.dir / "rootfs"),
                   DDNATV_BINDS="%s:%s|%s/rootfs/alias:%s" % (self.shared, self.alias, self.dir, self.alias))
        self.process = subprocess.Popen(["/usr/bin/python3", str(SCRIPT), str(self.proc)], stdout=subprocess.DEVNULL,
                                        env=env)
        self.session = self.dir / "session"
        (self.session / self.dir.relative_to("/")).mkdir(parents=True)
        (self.session / self.dir.relative_to("/") / "hidden.iso").write_text("data")
        (self.session / ".flatpak-info").write_text("[Application]\nname=org.example.App\n")
        for pid in (self.process.pid, 99):
            (self.proc / str(pid)).mkdir()
            (self.proc / str(pid) / "root").symlink_to(self.session)
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

    def test_a_file_the_application_cannot_reach_is_refused(self):
        flags = REUSE_EXISTING | PERSISTENT | AS_NEEDED_BY_APP
        hidden = self.dir / "hidden.iso"
        hidden.write_text("data")
        self.assertTrue((self.proc / "99/root" / hidden.relative_to("/")).exists())
        self.assertEqual("org.freedesktop.portal.Error.NotAllowed",
                         self.error_of("AddFull", "(ahusas)", ([0], flags, "org.example.App", ["read"]), [hidden]))
        self.assertEqual("org.freedesktop.portal.Error.NotAllowed",
                         self.error_of("AddFull", "(ahusas)", ([0], flags, "org.example.Other", ["read"]), [self.file]))
        self.assertEqual(([""], {}), self.call("AddFull", "(ahusas)", ([0], flags, "", ["read"]), [hidden]))

    def test_a_file_seen_through_a_bound_folder_is_found_under_its_host_name(self):
        flags = REUSE_EXISTING | PERSISTENT | AS_NEEDED_BY_APP
        self.assertEqual(([""], {}), self.call("AddFull", "(ahusas)", ([0], flags, "org.example.App", ["read"]),
                                               [self.alias / "game.iso"]))
        self.assertEqual(([""], {}), self.call("AddFull", "(ahusas)", ([0], flags, "org.example.App", ["read"]),
                                               [self.alias / "inside.iso"]))
        self.assertEqual("org.freedesktop.portal.Error.NotAllowed",
                         self.error_of("AddFull", "(ahusas)", ([0], flags, "org.example.App", ["read"]),
                                       [self.alias / "private.iso"]))
        (self.sandbox / self.shared.relative_to("/") / "game.iso").unlink()
        self.assertEqual("org.freedesktop.portal.Error.NotAllowed",
                         self.error_of("AddFull", "(ahusas)", ([0], flags, "org.example.App", ["read"]),
                                       [self.alias / "game.iso"]))

    def test_a_file_inside_the_root_filesystem_is_judged_by_its_own_name(self):
        flags = REUSE_EXISTING | PERSISTENT | AS_NEEDED_BY_APP
        home = self.dir / "rootfs/home.iso"
        home.write_text("data")
        (self.sandbox / home.relative_to("/")).write_text("data")
        self.assertEqual("org.freedesktop.portal.Error.NotAllowed",
                         self.error_of("AddFull", "(ahusas)", ([0], flags, "org.example.App", ["read"]), [home]))
        (self.sandbox / "home.iso").write_text("data")
        self.assertEqual(([""], {}), self.call("AddFull", "(ahusas)", ([0], flags, "org.example.App", ["read"]), [home]))

    @unittest.skipIf(os.getuid() == 0, "root reads every file")
    def test_a_location_the_application_cannot_use_as_asked_is_refused(self):
        flags = REUSE_EXISTING | PERSISTENT | AS_NEEDED_BY_APP
        self.visible.chmod(0)
        self.assertEqual("org.freedesktop.portal.Error.NotAllowed",
                         self.error_of("AddFull", "(ahusas)", ([0], flags, "org.example.App", ["read"]), [self.file]))
        self.visible.parent.chmod(0o500)
        try:
            self.assertEqual("org.freedesktop.portal.Error.NotAllowed",
                             self.error_of("AddNamedFull", "(hayusas)",
                                           (0, b"new.txt\0", AS_NEEDED_BY_APP, "org.example.App", ["read", "write"]),
                                           [self.dir]))
        finally:
            self.visible.parent.chmod(0o700)

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
