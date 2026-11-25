import json
import os
from pathlib import Path
import resource
import secrets
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import unittest

from tools.tests.test_usr2_preload import runner

BWRAP = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-bwrap"
HOOK_BUILD = Path(__file__).resolve().parents[1] / "nativeguest/build.sh"

PROBE = r"""
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

int main(int argc, char **argv) {
  printf("env\t%s\n", getenv("DROIDDECK_FDS") ? "set" : "-");
  for (int i = 1; i < argc; i++) {
    int fd = atoi(argv[i]);
    char buf[64];
    int flags = fcntl(fd, F_GETFD);
    ssize_t n = flags < 0 ? -1 : read(fd, buf, sizeof(buf) - 1);
    buf[n > 0 ? n : 0] = 0;
    printf("%d\t%s\t%s\n", fd, n > 0 ? buf : "-", flags < 0 ? "closed" : flags & FD_CLOEXEC ? "cloexec" : "kept");
  }
  return 0;
}
"""


def pipe_holding(data):
    read, write = os.pipe()
    os.write(write, data)
    os.close(write)
    return read


def serve(listener, fds, targets):
    conn, _ = listener.accept()
    with conn, listener:
        conn.sendmsg([",".join(map(str, targets)).encode()],
                     [(socket.SOL_SOCKET, socket.SCM_RIGHTS, struct.pack("%di" % len(fds), *fds))])


class FakeApp:
    def __init__(self, test, launch):
        self.name = "droiddeck-test-" + secrets.token_hex(8)
        self.listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.listener.bind("\0" + self.name)
        self.listener.listen(1)
        self.launch = launch
        self.request = None
        self.thread = threading.Thread(target=self.run, daemon=True)
        self.thread.start()
        test.addCleanup(self.listener.close)

    def run(self):
        conn, _ = self.listener.accept()
        with conn:
            head = conn.recv(5, socket.MSG_WAITALL)
            body = conn.recv(struct.unpack(">I", head[1:])[0], socket.MSG_WAITALL)
            self.request = json.loads(body)
            output, status = self.launch(self.request)
            for kind, payload in ((b"O", output), (b"X", str(status).encode())):
                conn.sendall(kind + struct.pack(">I", len(payload)) + payload)


def fetch(name):
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as conn:
        conn.connect("\0" + name)
        text, fds, _, _ = socket.recv_fds(conn, 1024, 16)
    try:
        return text.decode(), [os.read(fd, 64) for fd in fds]
    finally:
        for fd in fds:
            os.close(fd)


class BwrapForwardsFdsTest(unittest.TestCase):
    def run_shim(self, launch, extra, pass_fds):
        app = FakeApp(self, launch)
        env = {k: v for k, v in os.environ.items() if not k.startswith("DDNATV_")}
        env["DROIDDECK_BWRAP_SOCKET"] = app.name
        work = tempfile.TemporaryDirectory()
        self.addCleanup(work.cleanup)
        out = subprocess.run([sys.executable, str(BWRAP), "--bind", work.name, "/mnt/elsewhere", *extra, "--",
                              "/nonexistent/program"], env=env, pass_fds=pass_fds, capture_output=True, timeout=30)
        self.assertEqual(0, out.returncode, out.stderr)
        return app.request, out.stdout

    def test_inherited_fds_are_offered_to_the_sandboxed_program(self):
        forwarded = pipe_holding(b"dbus peer")
        sync = pipe_holding(b"sync")
        self.addCleanup(os.close, forwarded)
        self.addCleanup(os.close, sync)
        got = {}

        def launch(request):
            got.update(zip(("targets", "data"), fetch(request["env"]["DROIDDECK_FDS"])))
            return b"", 0

        request, _ = self.run_shim(launch, ["--sync-fd", str(sync)], (forwarded, sync))
        self.assertEqual(str(forwarded), got["targets"])
        self.assertEqual([b"dbus peer"], got["data"])

    def test_the_offer_is_taken_once(self):
        forwarded = pipe_holding(b"once")
        self.addCleanup(os.close, forwarded)
        second = []

        def launch(request):
            fetch(request["env"]["DROIDDECK_FDS"])
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as conn:
                try:
                    conn.connect("\0" + request["env"]["DROIDDECK_FDS"])
                    second.append("connected")
                except OSError:
                    second.append("refused")
            return b"", 0

        self.run_shim(launch, [], (forwarded,))
        self.assertEqual(["refused"], second)

    def test_more_descriptors_than_can_be_passed_stop_the_launch(self):
        fds = [pipe_holding(b"x") for _ in range(254)]
        for fd in fds:
            self.addCleanup(os.close, fd)
        app = FakeApp(self, lambda request: (b"", 0))
        env = {k: v for k, v in os.environ.items() if not k.startswith("DDNATV_")}
        env["DROIDDECK_BWRAP_SOCKET"] = app.name
        work = tempfile.TemporaryDirectory()
        self.addCleanup(work.cleanup)
        out = subprocess.run([sys.executable, str(BWRAP), "--bind", work.name, "/mnt/elsewhere", "--",
                              "/nonexistent/program"], env=env, pass_fds=fds, capture_output=True, timeout=30)
        self.assertNotEqual(0, out.returncode)
        self.assertIn(b"Too many inherited file descriptors", out.stderr)
        self.assertIsNone(app.request)

    def test_nothing_is_offered_without_inherited_fds(self):
        request, _ = self.run_shim(lambda request: (b"", 0), [], ())
        self.assertNotIn("DROIDDECK_FDS", request["env"])


class NativeHookAdoptsFdsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cc, cls.prefix = runner()
        if cls.cc is None:
            raise unittest.SkipTest("no aarch64 compiler and runner")
        cls.tmp = tempfile.TemporaryDirectory()
        tmp = Path(cls.tmp.name)
        cls.hook = tmp / "hook.so"
        subprocess.run(["bash", str(HOOK_BUILD), str(cls.hook), cls.cc], check=True, capture_output=True)
        (tmp / "probe.c").write_text(PROBE)
        subprocess.run([cls.cc, "-O1", "-o", str(tmp / "probe"), str(tmp / "probe.c")], check=True)
        cls.probe_path = tmp / "probe"

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def offer(self, fds, targets):
        name = "droiddeck-test-" + secrets.token_hex(8)
        listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        listener.bind("\0" + name)
        listener.listen(1)
        listener.settimeout(60)
        threading.Thread(target=serve, args=(listener, fds, targets), daemon=True).start()
        self.addCleanup(listener.close)
        return name

    def run_probe(self, name, *wanted, fd_limit=None):
        hook_env = {"DROIDDECK_FDS": name, "LD_PRELOAD": str(self.hook)}
        env = {k: v for k, v in os.environ.items() if k != "LD_PRELOAD" and not k.startswith("DDNATV_")}
        if self.prefix:
            cmd = list(self.prefix) + [arg for key, value in hook_env.items() for arg in ("-E", f"{key}={value}")]
        else:
            cmd = []
            env.update(hook_env)
        limit = (lambda: resource.setrlimit(resource.RLIMIT_NOFILE, (fd_limit, fd_limit))) if fd_limit else None
        out = subprocess.run(cmd + [str(self.probe_path), *map(str, wanted)], env=env, capture_output=True,
                             text=True, timeout=120, preexec_fn=limit)
        self.assertEqual(0, out.returncode, out.stderr)
        return {line.split("\t")[0]: line.split("\t")[1:] for line in out.stdout.splitlines()}

    def held(self, *data):
        fds = [pipe_holding(d) for d in data]
        for fd in fds:
            self.addCleanup(os.close, fd)
        return fds

    def test_forwarded_fds_land_on_their_numbers_and_stay_inheritable(self):
        fds = self.held(b"peer", b"sync")
        got = self.run_probe(self.offer(fds, [22, 5]), 22, 5)
        self.assertEqual(["peer", "kept"], got["22"])
        self.assertEqual(["sync", "kept"], got["5"])
        self.assertEqual(["-"], got["env"])

    def test_targets_that_overlap_the_received_numbers_are_not_mixed_up(self):
        fds = self.held(b"first", b"second")
        got = self.run_probe(self.offer(fds, [4, 3]), 4, 3)
        self.assertEqual("first", got["4"][0])
        self.assertEqual("second", got["3"][0])

    def test_a_target_at_the_descriptor_limit_is_installed(self):
        fds = self.held(b"edge")
        got = self.run_probe(self.offer(fds, [255]), 255, fd_limit=256)
        self.assertEqual(["edge", "kept"], got["255"])

    def test_a_missing_offer_changes_nothing(self):
        got = self.run_probe("droiddeck-test-gone-" + secrets.token_hex(4), 22)
        self.assertEqual(["-", "closed"], got["22"])
        self.assertEqual(["-"], got["env"])


if __name__ == "__main__":
    unittest.main()
