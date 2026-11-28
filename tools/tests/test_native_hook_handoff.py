import errno
import json
import os
import shutil
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
#include <fcntl.h>
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
    if (strcmp(argv[1], "closefds") == 0) {
      // as Python's subprocess does before exec; an ordinary fd shows that it closed them
      int probe = open("/dev/null", O_RDONLY);
      printf("closerange\t%ld\n", (long)syscall(SYS_close_range, 3, ~0U, 0));
      printf("closed\t%d\n", probe >= 3 && fcntl(probe, F_GETFD) < 0);
      fflush(stdout);
    }
    if (strcmp(argv[1], "group") == 0) setpgid(0, 0);
    execl("/bin/target", "target", argv[2], (char *)NULL);
    _exit(127);
  }
  int status = 0;
  int group = strcmp(argv[1], "group") == 0;
  if (group) {
    setpgid(pid, pid);
    usleep(300000);
    // the child is in a group of its own: not ours
    printf("ours\t%d\n", (int)waitpid(0, &status, WNOHANG));
  }
  if (strcmp(argv[1], "kill") == 0) {
    printf("nohang\t%d\n", (int)waitpid(pid, &status, WNOHANG));
    usleep(300000);
    printf("kill\t%d\n", kill(pid, SIGTERM));
  }
  pid_t r = waitpid(group ? -pid : pid, &status, 0);
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
                data, chunk, _, _ = socket.recv_fds(conn, 1, request["fdchunks"])
                fds.extend(chunk)
                if not data:
                    break  # the client went away between chunks
            if len(fds) < len(request["fds"]) + 1:
                for fd in fds:
                    os.close(fd)
                conn.close()
                continue
            self.requests.append(request)
            table = fds[request["table"]]
            program = str(self.root) + request["file"]
            # As a worker does: a process whose pid goes back first, which runs the program only
            # once the hook takes it on with "go"
            gate_r, gate_w = os.pipe()
            pid = os.fork()
            if pid == 0:
                os.close(gate_w)
                if os.read(gate_r, 1) == b"1":
                    argv = list(self.prefix) + [program] + request["argv"][1:]
                    os.execv(argv[0] if os.path.isabs(argv[0]) else shutil.which(argv[0]), argv)
                os._exit(127)
            os.close(gate_r)
            conn.sendall(b"pid %d\n" % pid)
            went = not request.get("ack") or self.read(conn, 3) == b"go\n"
            conn.close()
            for fd in fds:
                if fd != table:
                    os.close(fd)
            if went:
                os.write(gate_w, b"1")
            os.close(gate_w)
            if not went:
                os.waitpid(pid, 0)
                os.close(table)
                continue
            threading.Thread(target=self.report, args=(pid, table, request), daemon=True).start()

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
    def report(pid, table, request):
        code = os.waitstatus_to_exitcode(os.waitpid(pid, 0)[1])
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

    def run_parent(self, *argv, handoff="target", hook=True):
        name = "dd-handoff-test-%d-%s-%s-%d" % (os.getpid(), self.id().rsplit(".", 1)[-1], handoff, hook)
        spawner = FakeSpawner(name, self.prefix or [], self.root)
        spawner.start()
        self.addCleanup(spawner.sock.close)
        hook_env = {"DDNATV_ROOT": str(self.root), "DD_SPAWN": name, "DD_HANDOFF": handoff}
        if hook:
            hook_env["LD_PRELOAD"] = str(self.hook)
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
        if result.get("closerange") != "0":
            self.skipTest("close_range does not work here: %s" % result.get("closerange"))
        self.assertEqual("1", result.get("closed"), out.stdout)
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
        result, requests, out = self.run_parent("spawn", "6", "/bin/missing", handoff="missing")
        self.assertEqual(0, out.returncode, out.stderr)
        self.assertEqual([], requests)
        self.assertEqual(str(errno.ENOENT), result.get("spawned"), out.stdout)
        self.assertNotIn("exit", result, out.stdout)
        self.assertNotIn("signal", result, out.stdout)

    def test_a_posix_spawn_of_a_missing_program_not_listed_is_unchanged(self):
        # As without the hook: ENOENT natively; qemu-user, which runs glibc's vfork-style spawn child
        # as a separate process, gets 0 and an exit status of 127.
        result, requests, out = self.run_parent("spawn", "6", "/bin/missing", handoff="other")
        plain, _, plain_out = self.run_parent("spawn", "6", "/bin/missing", handoff="other", hook=False)
        self.assertEqual(0, out.returncode, out.stderr)
        self.assertEqual(0, plain_out.returncode, plain_out.stderr)
        self.assertIn("spawned", result, out.stdout)
        self.assertIn("spawned", plain, plain_out.stdout)
        self.assertEqual([], requests)
        self.assertEqual(plain.get("spawned"), result.get("spawned"), out.stdout + plain_out.stdout)
        self.assertEqual(plain.get("exit"), result.get("exit"), out.stdout + plain_out.stdout)

    def test_a_posix_spawn_of_a_script_without_its_interpreter_fails_as_it_would(self):
        script = self.root / "bin/orphan"
        script.write_text("#!/bin/no-such-interpreter\n")
        script.chmod(0o755)
        result, requests, out = self.run_parent("spawn", "6", "/bin/orphan", handoff="orphan")
        self.assertEqual([], requests)
        self.assertEqual(str(errno.ENOENT), result.get("spawned"), out.stdout)

    def test_a_wait_for_a_group_takes_only_that_groups_children(self):
        result, requests, out = self.run_parent("group", "4")
        self.assertEqual(1, len(requests), out.stderr)
        self.assertEqual("-1", result.get("ours"), out.stdout)
        self.assertEqual("1", result.get("same"), out.stdout)
        self.assertEqual("4", result.get("exit"), out.stdout)

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
