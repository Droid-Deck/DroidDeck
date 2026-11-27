import errno
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
#define _GNU_SOURCE
#include <signal.h>
#include <spawn.h>
#include <stdio.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

extern char **environ;

int main(int argc, char **argv) {
  pid_t pid;
  if (strcmp(argv[1], "spawn") == 0) {
    // as Python's subprocess does since 3.13, with its close_fds as a file action
    posix_spawn_file_actions_t actions;
    posix_spawn_file_actions_init(&actions);
    posix_spawn_file_actions_addclosefrom_np(&actions, 3);
    char *args[] = {"target", argv[2], NULL};
    int err = posix_spawn(&pid, argc > 3 ? argv[3] : "/bin/target", &actions, NULL, args, environ);
    printf("spawned\t%d\n", err);
    if (err) return 0;
  } else if ((pid = fork()) == 0) {
    // as Python's subprocess does before exec
    if (strcmp(argv[1], "closefds") == 0) syscall(SYS_close_range, 3, ~0U, 0);
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
            header = self.read(conn, 4)
            payload = self.read(conn, struct.unpack(">I", header)[0]) if header else b""
            if not payload:
                conn.close()
                continue
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
    def read(conn, size):
        data = b""
        while len(data) < size:
            more = conn.recv(size - len(data))
            if not more:
                return b""  # the client went away
            data += more
        return data

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
        name = "dd-handoff-test-%d-%s-%s" % (os.getpid(), self.id().rsplit(".", 1)[-1], handoff)
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

    def test_a_child_that_closed_its_inherited_fds_is_handed_off(self):
        result, requests, out = self.run_parent("closefds", "5")
        self.assertEqual(1, len(requests), out.stderr)
        self.assertEqual("1", result.get("same"), out.stdout)
        self.assertEqual("5", result.get("exit"), out.stdout)

    def test_a_posix_spawn_is_handed_off(self):
        result, requests, out = self.run_parent("spawn", "6")
        self.assertEqual("0", result.get("spawned"), out.stdout)
        self.assertEqual(1, len(requests), out.stderr)
        self.assertEqual(["target", "6"], requests[0]["argv"])
        self.assertEqual("1", result.get("same"), out.stdout)
        self.assertEqual("6", result.get("exit"), out.stdout)

    def test_a_posix_spawn_of_a_missing_program_fails_as_it_would(self):
        # posix_spawn() returns the exec's error, even with closefrom(3) among the file actions
        result, requests, out = self.run_parent("spawn", "6", "/bin/missing")
        self.assertEqual([], requests)
        self.assertEqual(str(errno.ENOENT), result.get("spawned"), out.stdout)

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
