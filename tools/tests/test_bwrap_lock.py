import json
import os
import runpy
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import unittest
from pathlib import Path

BWRAP = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-bwrap"

PROBE = """
import fcntl, os, sys
fd = os.open(sys.argv[1], os.O_RDWR)
try:
    fcntl.lockf(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
except OSError:
    print("held")
else:
    print("free")
"""


class BwrapLockFileTest(unittest.TestCase):
    def setUp(self):
        self.shim = runpy.run_path(str(BWRAP))
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.instance = Path(self.temp.name) / "instance"
        self.instance.mkdir()
        (self.instance / ".ref").write_text("")

    def spec(self, *extra):
        argv = ["--ro-bind", str(self.instance), "/run/user/1000/.flatpak/7", *extra, "--", "true"]
        return self.shim["parse"](argv)

    def probe(self):
        return subprocess.run([sys.executable, "-c", PROBE, str(self.instance / ".ref")],
                              capture_output=True, text=True, timeout=30, check=True).stdout.strip()

    def test_sandbox_paths_resolve_through_the_binds(self):
        spec = self.spec()
        self.assertEqual(str(self.instance / ".ref"), self.shim["host_path"](spec, "/run/user/1000/.flatpak/7/.ref"))
        self.assertEqual("/etc/hosts", self.shim["host_path"](spec, "/etc/hosts"))

    def test_the_instance_lock_is_held_while_the_sandbox_runs(self):
        spec = self.spec("--lock-file", "/run/user/1000/.flatpak/7/.ref")
        self.assertEqual("free", self.probe())
        self.shim["hold_locks"](spec)
        self.assertEqual("held", self.probe())

    def test_without_a_lock_file_nothing_is_locked(self):
        self.shim["hold_locks"](self.spec())
        self.assertEqual("free", self.probe())

    def test_the_lock_survives_exec(self):
        script = (
            "import runpy, sys; shim = runpy.run_path(sys.argv[1]); "
            "spec = shim['parse'](['--ro-bind', sys.argv[2], '/run/user/1000/.flatpak/7', "
            "'--lock-file', '/run/user/1000/.flatpak/7/.ref', '--', 'true']); "
            "shim['hold_locks'](spec); "
            "import os; os.execv(sys.executable, [sys.executable, '-c', "
            "'import subprocess, sys; print(subprocess.run([sys.executable, \"-c\", sys.argv[1], sys.argv[2]], "
            "capture_output=True, text=True).stdout.strip())', sys.argv[3], sys.argv[4]])"
        )
        out = subprocess.run([sys.executable, "-c", script, str(BWRAP), str(self.instance), PROBE,
                              str(self.instance / ".ref")], capture_output=True, text=True, timeout=30, check=True)
        self.assertEqual("held", out.stdout.strip())


class BwrapReportedPidTest(unittest.TestCase):
    def test_flatpak_is_given_the_wrapper_so_killing_it_ends_the_whole_sandbox(self):
        name = "droiddeck-bwrap-test-%d" % os.getpid()
        server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        server.bind("\0" + name)
        server.listen(1)
        self.addCleanup(server.close)

        def answer():
            conn, _ = server.accept()
            with conn:
                head = conn.recv(5)
                size = struct.unpack(">I", head[1:])[0]
                while size > 0:
                    size -= len(conn.recv(size))
                for kind, payload in ((b"P", b"424242"), (b"X", b"0")):
                    conn.sendall(kind + struct.pack(">I", len(payload)) + payload)

        thread = threading.Thread(target=answer, daemon=True)
        thread.start()
        read_end, write_end = os.pipe()
        env = dict(os.environ, DROIDDECK_BWRAP_SOCKET=name)
        proc = subprocess.Popen([sys.executable, str(BWRAP), "--ro-bind", "/", "/elsewhere", "--info-fd",
                                 str(write_end), "--", "true"], env=env, pass_fds=[write_end])
        os.close(write_end)
        info = os.read(read_end, 4096)
        os.close(read_end)
        self.assertEqual(0, proc.wait(30))
        thread.join(5)
        self.assertEqual({"child-pid": proc.pid}, json.loads(info))


if __name__ == "__main__":
    unittest.main()
