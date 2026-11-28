import errno
import fcntl
import json
import os
import shutil
import signal
import socket
import struct
import subprocess
import tempfile
import termios
import threading
import unittest
from pathlib import Path

from tools.tests.test_usr2_preload import runner

CLD_EXITED = 1

HOOK_BUILD = Path(__file__).resolve().parents[1] / "nativeguest/build.sh"

# fork() and exec argv[1] with "3", without the hook: prints the exit status it got
EXEC_PROBE = r"""
#include <stdio.h>
#include <sys/wait.h>
#include <unistd.h>

int main(int argc, char **argv) {
  pid_t pid = fork();
  if (pid == 0) {
    execl(argv[1], "target", "3", (char *)NULL);
    _exit(127);
  }
  int status = 0;
  if (waitpid(pid, &status, 0) != pid) return 1;
  printf("%d\n", WIFEXITED(status) ? WEXITSTATUS(status) : -1);
  return 0;
}
"""

PARENT = r"""
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <spawn.h>
#include <stdio.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

extern char **environ;

static int gate[2] = {-1, -1};

static void open_gate(int sig) {
  (void)sig;
  if (write(gate[1], "1", 1) != 1) _exit(125);
}

// nothread: a second thread forks the child; __WNOTHREAD waits see only their own thread's children
static char *nothread_arg;
static pthread_mutex_t nothread_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t nothread_cond = PTHREAD_COND_INITIALIZER;
static int nothread_step;
static pid_t nothread_child;

static void nothread_await(int step) {
  pthread_mutex_lock(&nothread_lock);
  while (nothread_step < step) pthread_cond_wait(&nothread_cond, &nothread_lock);
  pthread_mutex_unlock(&nothread_lock);
}

static void nothread_go(int step) {
  pthread_mutex_lock(&nothread_lock);
  nothread_step = step;
  pthread_cond_broadcast(&nothread_cond);
  pthread_mutex_unlock(&nothread_lock);
}

static void *nothread_forker(void *unused) {
  (void)unused;
  pid_t pid = fork();
  if (pid == 0) {
    execl("/bin/target", "target", nothread_arg, (char *)NULL);
    _exit(127);
  }
  nothread_child = pid;
  usleep(1500000);  // the program has exited by now
  nothread_go(1);
  nothread_await(2);
  int status = 0;
  pid_t r = waitpid(pid, &status, __WNOTHREAD);
  printf("own\t%d\n", r == pid);
  if (WIFEXITED(status)) printf("exit\t%d\n", WEXITSTATUS(status));
  return NULL;
}

int main(int argc, char **argv) {
  pid_t pid;
  if (strcmp(argv[1], "nothread") == 0) {
    nothread_arg = argv[2];
    pthread_t forker;
    pthread_create(&forker, NULL, nothread_forker, NULL);
    nothread_await(1);
    int status = 0;
    printf("other\t%d\n", (int)waitpid(-1, &status, WNOHANG | __WNOTHREAD));
    printf("otherpid\t%d\n", (int)waitpid(nothread_child, &status, WNOHANG | __WNOTHREAD));
    nothread_go(2);
    pthread_join(forker, NULL);
    return 0;
  }
  // waitidpid: the child holds before its exec until the parent's wait has begun
  if (strcmp(argv[1], "waitidpid") == 0 && pipe(gate) != 0) return 1;
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
    if (gate[0] >= 0) {
      char c;
      close(gate[1]);
      if (read(gate[0], &c, 1) != 1) _exit(126);
    }
    if (strcmp(argv[1], "manyargs") == 0) {
      // argv[3] after 32 others, as a long env-wrapped launch has it
      char *args[37] = {"target", argv[2]};
      for (int i = 2; i < 34; i++) args[i] = "x";
      args[34] = argv[3];
      args[35] = NULL;
      execv("/bin/target", args);
      _exit(127);
    }
    execl(argc > 3 ? argv[3] : "/bin/target", "target", argv[2], (char *)NULL);
    _exit(127);
  }
  int status = 0;
  if (strcmp(argv[1], "waitidpid") == 0) {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = open_gate;
    sa.sa_flags = SA_RESTART;
    sigaction(SIGALRM, &sa, NULL);
    alarm(1);
    siginfo_t si;
    memset(&si, 0, sizeof(si));
    int r = waitid(P_PID, pid, &si, WEXITED);
    printf("same\t%d\n", r == 0 && si.si_pid == pid);
    printf("code\t%d\n", si.si_code);
    printf("exit\t%d\n", si.si_status);
    printf("again\t%d\n", (int)waitpid(-1, &status, WNOHANG));
    return 0;
  }
  int all = strcmp(argv[1], "waitidall") == 0 ? __WALL : 0;
  if (strcmp(argv[1], "waitid") == 0 || all) {
    // at once, before the child has claimed its handoff, and for stops too (and with __WALL)
    siginfo_t si;
    errno = 0;
    printf("pid0\t%d\n", waitid(P_PID, 0, &si, WEXITED) < 0 && errno == EINVAL);
    memset(&si, 0, sizeof(si));
    int r = waitid(P_ALL, 0, &si, WEXITED | WSTOPPED | all);
    printf("same\t%d\n", r == 0 && si.si_pid == pid);
    printf("code\t%d\n", si.si_code);
    printf("exit\t%d\n", si.si_status);
    printf("uid\t%d\n", (int)si.si_uid);
    printf("again\t%d\n", (int)waitpid(-1, &status, WNOHANG));
    return 0;
  }
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

    def __init__(self, name, prefix, root, reports=True):
        super().__init__(daemon=True)
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.bind("\0" + name)
        self.sock.listen(4)
        self.prefix = prefix
        self.root = root
        self.reports = reports  # False: as an app that died with its worker, no exit report
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
            for fd in fds:
                if fd != table:
                    os.close(fd)
            if went:
                if request.get("ack"):
                    conn.sendall(b"ok\n")  # the app's last word, before the worker may run it
                os.write(gate_w, b"1")
            conn.close()
            os.close(gate_w)
            if not went:
                os.waitpid(pid, 0)
                os.close(table)
                continue
            threading.Thread(target=self.report if self.reports else self.reap, args=(pid, table, request), daemon=True).start()

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
    def reap(pid, table, request):
        os.waitpid(pid, 0)
        os.close(table)

    @staticmethod
    def report(pid, table, request):
        code = os.waitstatus_to_exitcode(os.waitpid(pid, 0)[1])
        status = (-code) & 0x7F if code < 0 else (code & 0xFF) << 8
        # as GuestWorkers.reportExit: one write of the status and the child it is for; the hook
        # moves the entry to exited itself
        entry = 64 + request["slot"] * 64
        os.pwrite(table, struct.pack("<ii", status, request["child"]), entry + 32)
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
        subprocess.run([cls.cc, "-O1", "-pthread", "-o", str(cls.parent), str(tmp / "parent.c")], check=True)
        cls.root = tmp / "root"
        (cls.root / "bin").mkdir(parents=True)
        (tmp / "target.c").write_text(TARGET)
        subprocess.run([cls.cc, "-static", "-O1", "-o", str(cls.root / "bin/target"), str(tmp / "target.c")], check=True)
        # What a child here gets from exec'ing an aarch64 program: 3 where the kernel runs them
        # (binfmt_misc with qemu-user), 127 on a plain x86 runner, which does not. A handed-off program is
        # started by the fake spawner through the runner itself either way.
        (tmp / "execs.c").write_text(EXEC_PROBE)
        subprocess.run([cls.cc, "-static", "-O1", "-o", str(tmp / "execs"), str(tmp / "execs.c")], check=True)
        probe = subprocess.run(list(cls.prefix or []) + [str(tmp / "execs"), str(cls.root / "bin/target")],
                               capture_output=True, text=True, timeout=60)
        assert probe.returncode == 0 and probe.stdout.strip() in ("3", "127"), (probe.returncode, probe.stdout, probe.stderr)
        cls.child_status = probe.stdout.strip()

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def run_parent(self, *argv, handoff="target", hook=True, reports=True, pressure=None, as_shell=False, terminal=False):
        name = "dd-handoff-test-%d-%s-%s-%d" % (os.getpid(), self.id().rsplit(".", 1)[-1], handoff, hook)
        spawner = FakeSpawner(name, self.prefix or [], self.root, reports)
        spawner.start()
        self.addCleanup(spawner.sock.close)
        hook_env = {"DDNATV_ROOT": str(self.root), "DD_SPAWN": name, "DD_HANDOFF": handoff}
        if hook:
            hook_env["LD_PRELOAD"] = str(self.hook)
        if pressure is not None:
            hook_env["DD_HANDOFF_PRESSURE"] = str(pressure)
        env = {k: v for k, v in os.environ.items() if k not in hook_env}
        prefix, parent = list(self.prefix or []), str(self.parent)
        if as_shell:
            # the hook tells a shell's child by its parent's name (qemu-user takes the program's)
            shell = Path(self.tmp.name) / ("shell-%s" % self.id().rsplit(".", 1)[-1]) / "bash"
            shell.parent.mkdir(exist_ok=True)
            shutil.copy(parent, shell)
            parent = str(shell)
        if prefix:
            cmd = prefix + [arg for key, value in hook_env.items() for arg in ("-E", f"{key}={value}")]
        else:
            cmd = []
            env.update(hook_env)
        stdin, stderr, ctty = None, subprocess.PIPE, None
        if terminal:
            # as a shell's foreground job: the controlling terminal of the parent's session as stdin
            # (terminal=True), or as stderr with stdin a pipe or /dev/null (terminal="pipe", "null")
            master, slave = os.openpty()
            self.addCleanup(os.close, master)
            self.addCleanup(os.close, slave)
            if terminal is True:
                stdin, fd = slave, 0
            else:
                stdin, stderr, fd = (subprocess.PIPE if terminal == "pipe" else subprocess.DEVNULL), slave, 2

            def ctty():
                os.setsid()
                fcntl.ioctl(fd, termios.TIOCSCTTY, 0)
        given = {"input": ""} if stdin == subprocess.PIPE else {"stdin": stdin}
        out = subprocess.run(cmd + [parent, *argv], env=env, stdout=subprocess.PIPE, stderr=stderr, preexec_fn=ctty,
                             text=True, timeout=120, **given)
        result = dict(line.split("\t", 1) for line in out.stdout.splitlines() if "\t" in line)
        return result, spawner.requests, out

    def test_a_program_gone_without_any_report_is_reported_killed(self):
        # its worker is gone and nobody reports how it ended (the app died with it): SIGKILL, after
        # the grace the app's report would have had
        result, requests, out = self.run_parent("wait", "7", reports=False)
        self.assertEqual(1, len(requests), out.stderr)
        self.assertEqual("1", result.get("same"), out.stdout)
        self.assertEqual(str(int(signal.SIGKILL)), result.get("signal"), out.stdout)

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

    def test_waitid_for_exits_and_stops_reports_the_program(self):
        result, requests, out = self.run_parent("waitid", "7")
        self.assertEqual(1, len(requests), out.stderr)
        self.assertEqual("1", result.get("pid0"), out.stdout)
        self.assertEqual("1", result.get("same"), out.stdout)
        self.assertEqual(str(CLD_EXITED), result.get("code"), out.stdout)
        self.assertEqual("7", result.get("exit"), out.stdout)
        self.assertEqual(str(os.getuid()), result.get("uid"), out.stdout)
        self.assertEqual("-1", result.get("again"), out.stdout)

    def test_waitid_with_wall_reports_the_program(self):
        result, requests, out = self.run_parent("waitidall", "7")
        self.assertEqual(1, len(requests), out.stderr)
        self.assertEqual("1", result.get("same"), out.stdout)
        self.assertEqual(str(CLD_EXITED), result.get("code"), out.stdout)
        self.assertEqual("7", result.get("exit"), out.stdout)
        self.assertEqual("-1", result.get("again"), out.stdout)

    def test_waitid_for_a_child_not_listed_is_the_kernels(self):
        result, requests, out = self.run_parent("waitid", "3", handoff="other")
        self.assertEqual([], requests)
        self.assertEqual("1", result.get("same"), out.stdout)
        self.assertEqual(str(CLD_EXITED), result.get("code"), out.stdout)
        self.assertEqual(self.child_status, result.get("exit"), out.stdout)
        self.assertEqual(str(os.getuid()), result.get("uid"), out.stdout)

    def test_near_the_limit_a_shells_child_is_handed_off(self):
        result, requests, out = self.run_parent("wait", "7", handoff="other", pressure=0, as_shell=True)
        self.assertEqual(1, len(requests), out.stderr)
        self.assertEqual(["target", "7"], requests[0]["argv"])
        self.assertEqual("7", result.get("exit"), out.stdout)

    def test_below_the_limit_a_shells_child_is_not(self):
        result, requests, out = self.run_parent("wait", "3", handoff="other", pressure=1000, as_shell=True)
        self.assertEqual([], requests)
        self.assertEqual(self.child_status, result.get("exit"), out.stdout)

    def test_near_the_limit_a_child_of_another_program_is_not(self):
        result, requests, out = self.run_parent("wait", "3", handoff="other", pressure=0)
        self.assertEqual([], requests)
        self.assertEqual(self.child_status, result.get("exit"), out.stdout)

    def test_near_the_limit_a_foreground_pipe_reader_is_not(self):
        # less in "cmd | less": stdin a pipe, the terminal on another fd
        result, requests, out = self.run_parent("wait", "3", handoff="other", pressure=0, as_shell=True, terminal="pipe")
        self.assertEqual([], requests)
        self.assertEqual(self.child_status, result.get("exit"), out.stdout)

    def test_near_the_limit_a_job_reading_dev_null_is_handed_off(self):
        # "cmd &" from a shell without job control: the foreground group, stdin /dev/null
        result, requests, out = self.run_parent("wait", "7", handoff="other", pressure=0, as_shell=True, terminal="null")
        self.assertEqual(1, len(requests), out.stdout)
        self.assertEqual("7", result.get("exit"), out.stdout)

    def test_near_the_limit_the_steam_client_is_not(self):
        steam = self.root / "bin/steam"
        if not steam.exists():
            shutil.copy(self.root / "bin/target", steam)
        result, requests, out = self.run_parent("wait", "3", "/bin/steam", handoff="other", pressure=0, as_shell=True)
        self.assertEqual([], requests)
        self.assertEqual(self.child_status, result.get("exit"), out.stdout)

    def test_near_the_limit_a_launch_naming_steam_in_any_argument_is_not(self):
        # as "env ... /path/Steam/steamrtarm64/steam", with the path after 32 other arguments
        result, requests, out = self.run_parent("manyargs", "3", "/root/.local/share/Steam/steamrtarm64/steam",
                                                handoff="other", pressure=0, as_shell=True)
        self.assertEqual([], requests)
        self.assertEqual("1", result.get("same"), out.stdout)
        self.assertEqual(self.child_status, result.get("exit"), out.stdout)

    def test_a_wait_of_another_thread_with_wnothread_does_not_take_the_program(self):
        result, requests, out = self.run_parent("nothread", "7")
        self.assertEqual(1, len(requests), out.stderr)
        self.assertEqual("-1", result.get("other"), out.stdout)
        self.assertEqual("-1", result.get("otherpid"), out.stdout)
        self.assertEqual("1", result.get("own"), out.stdout)
        self.assertEqual("7", result.get("exit"), out.stdout)

    def test_waitid_for_a_pid_begun_before_its_claim_reports_the_program(self):
        result, requests, out = self.run_parent("waitidpid", "7")
        self.assertEqual(1, len(requests), out.stderr)
        self.assertEqual("1", result.get("same"), out.stdout)
        self.assertEqual(str(CLD_EXITED), result.get("code"), out.stdout)
        self.assertEqual("7", result.get("exit"), out.stdout)
        self.assertEqual("-1", result.get("again"), out.stdout)

    def test_near_the_limit_a_terminals_foreground_job_is_not(self):
        result, requests, out = self.run_parent("wait", "3", handoff="other", pressure=0, as_shell=True, terminal=True)
        self.assertEqual([], requests)
        self.assertEqual(self.child_status, result.get("exit"), out.stdout)

    def ppid_in_worker(self, parent):
        # as a worker's own program: DDH_WORKER_PID is its pid, DDH_TABLE names the process that forked it
        tmp = Path(self.tmp.name)
        probe = tmp / "ppid"
        if not probe.exists():
            (tmp / "ppid.c").write_text('#include <stdio.h>\n#include <unistd.h>\nint main(void) { printf("%d", (int)getppid()); return 0; }\n')
            subprocess.run([self.cc, "-O1", "-o", str(probe), str(tmp / "ppid.c")], check=True)
        env = {"LD_PRELOAD": str(self.hook), "DDNATV_ROOT": str(self.root)}
        if parent is not None:
            env["DDH_TABLE"] = "3:0:%d:0" % parent
        settings = " ".join("-E %s=%s" % item for item in env.items()) if self.prefix else \
            " ".join("%s=%s" % item for item in env.items())
        worker = "-E DDH_WORKER_PID=$$" if self.prefix else "DDH_WORKER_PID=$$"
        runner = " ".join(self.prefix) + " " + settings + " " + worker if self.prefix else "env " + settings + " " + worker
        out = subprocess.run(["sh", "-c", "exec %s %s" % (runner, probe)], capture_output=True, text=True, timeout=60)
        return out.stdout.strip()

    def test_a_handed_off_programs_parent_is_the_process_that_forked_it(self):
        forker = subprocess.Popen(["sleep", "60"])
        self.addCleanup(forker.wait)
        self.addCleanup(forker.kill)
        self.assertEqual(str(forker.pid), self.ppid_in_worker(forker.pid))

    def test_a_handed_off_program_whose_forking_process_is_gone_has_init_as_parent(self):
        self.assertEqual("1", self.ppid_in_worker(4194303))

    def test_a_handed_off_program_whose_forking_process_exited_unreaped_has_init_as_parent(self):
        forker = subprocess.Popen(["true"])
        self.addCleanup(forker.wait)
        stat = Path("/proc/%d/stat" % forker.pid)
        pause = threading.Event()
        for _ in range(100):
            if stat.read_text().rsplit(")", 1)[1].split()[0] == "Z":
                break
            pause.wait(0.05)
        # exited and not reaped (Popen reaps only in wait() or poll())
        self.assertEqual("Z", stat.read_text().rsplit(")", 1)[1].split()[0])
        self.assertEqual("1", self.ppid_in_worker(forker.pid))
        self.assertEqual("Z", stat.read_text().rsplit(")", 1)[1].split()[0])

    def test_any_other_program_has_its_real_parent(self):
        self.assertEqual(str(os.getpid()), self.ppid_in_worker(None))

    def test_a_program_not_listed_is_a_normal_child(self):
        result, requests, out = self.run_parent("wait", "3", handoff="other")
        self.assertEqual([], requests)
        self.assertEqual("1", result.get("same"), out.stdout)
        self.assertEqual(self.child_status, result.get("exit"), out.stdout)


if __name__ == "__main__":
    unittest.main()
