import json
import os
import signal
import socket
import struct
import subprocess
import tempfile
import threading
import unittest
from pathlib import Path

from tools.tests.test_usr2_preload import runner

HOOK_BUILD = Path(__file__).resolve().parents[1] / "nativeguest/build.sh"

PARENT = r"""
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

int main(int argc, char **argv) {
  pid_t pid = fork();
  if (pid == 0) {
    execl("/bin/target", "target", argv[2], (char *)NULL);
    _exit(127);
  }
  int status = 0;
  if (strcmp(argv[1], "kill") == 0) {
    printf("nohang\t%d\n", (int)waitpid(pid, &status, WNOHANG));
    usleep(300000);
    printf("kill\t%d\n", kill(pid, SIGTERM));
  }
  pid_t r = waitpid(pid, &status, 0);
  printf("same\t%d\n", r == pid);
  if (WIFEXITED(status)) printf("exit\t%d\n", WEXITSTATUS(status));
  if (WIFSIGNALED(status)) printf("signal\t%d\n", WTERMSIG(status));
  printf("again\t%d\n", (int)waitpid(-1, &status, WNOHANG));
  return 0;
}
"""

TARGET = r"""
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
  if (strcmp(argv[1], "sleep") == 0) for (;;) pause();
  return atoi(argv[1]);
}
"""


class FakeSpawner(threading.Thread):
    """Stands in for the app's spawn socket: runs the program and reports its exit into the table."""

    def __init__(self, name, prefix, root):
        super().__init__(daemon=True)
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.bind("\0" + name)
        self.sock.listen(4)
        self.prefix = prefix
        self.root = root
        self.requests = []

    def run(self):
        while True:
            try:
                conn, _ = self.sock.accept()
            except OSError:
                return
            header = b""
            while len(header) < 4:
                header += conn.recv(4 - len(header))
            length = struct.unpack(">I", header)[0]
            payload = b""
            while len(payload) < length:
                payload += conn.recv(length - len(payload))
            request = json.loads(payload)
            fds = []
            while len(fds) < len(request["fds"]) + 1:
                _, chunk, _, _ = socket.recv_fds(conn, 1, request["fdchunks"])
                fds.extend(chunk)
            self.requests.append(request)
            table = fds[request["table"]]
            program = str(self.root) + request["file"]
            proc = subprocess.Popen(list(self.prefix) + [program] + request["argv"][1:])
            conn.sendall(b"pid %d\n" % proc.pid)
            conn.close()
            for fd in fds:
                if fd != table:
                    os.close(fd)
            threading.Thread(target=self.report, args=(proc, table, request), daemon=True).start()

    @staticmethod
    def report(proc, table, request):
        code = proc.wait()
        status = (-code) & 0x7F if code < 0 else (code & 0xFF) << 8
        entry = 64 + request["slot"] * 32
        os.pwrite(table, struct.pack("<i", status), entry + 16)
        os.pwrite(table, struct.pack("<i", 2), entry + 12)
        os.close(table)
        os.kill(request["parent"], signal.SIGCHLD)


class NativeHookHandoffTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cc, cls.prefix = runner()
        if cls.cc is None:
            raise unittest.SkipTest("no aarch64 compiler and runner")
        cls.tmp = tempfile.TemporaryDirectory(dir="/tmp")
        tmp = Path(cls.tmp.name)
        cls.hook = tmp / "hook.so"
        subprocess.run(["bash", str(HOOK_BUILD), str(cls.hook), cls.cc], check=True, capture_output=True)
        (tmp / "parent.c").write_text(PARENT)
        cls.parent = tmp / "parent"
        subprocess.run([cls.cc, "-O1", "-o", str(cls.parent), str(tmp / "parent.c")], check=True)
        cls.root = tmp / "root"
        (cls.root / "bin").mkdir(parents=True)
        (tmp / "target.c").write_text(TARGET)
        subprocess.run([cls.cc, "-static", "-O1", "-o", str(cls.root / "bin/target"), str(tmp / "target.c")], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def run_parent(self, *argv, handoff="target"):
        name = "dd-handoff-test-%d-%s" % (os.getpid(), self.id().rsplit(".", 1)[-1])
        spawner = FakeSpawner(name, self.prefix or [], self.root)
        spawner.start()
        self.addCleanup(spawner.sock.close)
        hook_env = {"DDNATV_ROOT": str(self.root), "LD_PRELOAD": str(self.hook), "DD_SPAWN": name,
                    "DD_HANDOFF": handoff}
        env = {k: v for k, v in os.environ.items() if k not in hook_env}
        if self.prefix:
            cmd = list(self.prefix) + [arg for key, value in hook_env.items() for arg in ("-E", f"{key}={value}")]
        else:
            cmd = []
            env.update(hook_env)
        out = subprocess.run(cmd + [str(self.parent), *argv], env=env, capture_output=True, text=True, timeout=120)
        result = dict(line.split("\t", 1) for line in out.stdout.splitlines() if "\t" in line)
        return result, spawner.requests, out

    def test_the_parent_waits_for_the_handed_off_program(self):
        result, requests, out = self.run_parent("wait", "7")
        self.assertEqual(1, len(requests), out.stderr)
        self.assertEqual("/bin/target", requests[0]["file"])
        self.assertEqual(["target", "7"], requests[0]["argv"])
        self.assertEqual("1", result.get("same"), out.stdout)
        self.assertEqual("7", result.get("exit"), out.stdout)
        self.assertEqual("-1", result.get("again"), out.stdout)

    def test_kill_reaches_the_program_and_its_signal_is_reported(self):
        result, requests, out = self.run_parent("kill", "sleep")
        self.assertEqual(1, len(requests), out.stderr)
        self.assertEqual("0", result.get("nohang"), out.stdout)
        self.assertEqual("0", result.get("kill"), out.stdout)
        self.assertEqual(str(int(signal.SIGTERM)), result.get("signal"), out.stdout)

    def test_a_program_not_listed_is_a_normal_child(self):
        result, requests, out = self.run_parent("wait", "3", handoff="other")
        self.assertEqual([], requests)
        self.assertEqual("1", result.get("same"), out.stdout)


if __name__ == "__main__":
    unittest.main()
