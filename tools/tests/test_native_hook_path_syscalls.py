import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from tools.tests.test_usr2_preload import runner

HOOK_BUILD = Path(__file__).resolve().parents[1] / "nativeguest/build.sh"

PROBE = r"""
#define _GNU_SOURCE
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <unistd.h>

static void show(const char *name, long ret) { printf("%s\t%d\n", name, ret >= 0); }

int main(int argc, char **argv) {
  const char *dir = argv[1];
  char p[PATH_MAX], q[PATH_MAX], cwd[PATH_MAX];
  snprintf(p, sizeof(p), "%s/made", dir);
  show("mkdirat", syscall(SYS_mkdirat, AT_FDCWD, p, 0755));
  snprintf(p, sizeof(p), "%s/made/file", dir);
  long fd = syscall(SYS_openat, AT_FDCWD, p, O_WRONLY | O_CREAT | O_CLOEXEC, 0644);
  show("openat", fd);
  if (fd >= 0) close((int)fd);
  show("truncate", syscall(SYS_truncate, p, 4));
  show("fchmodat", syscall(SYS_fchmodat, AT_FDCWD, p, 0600));
  show("utimensat", syscall(SYS_utimensat, AT_FDCWD, p, NULL, 0));
  fd = syscall(SYS_openat, AT_FDCWD, p, O_RDONLY | O_CLOEXEC);
  show("futimens", fd >= 0 ? syscall(SYS_utimensat, (int)fd, NULL, NULL, 0) : -1);
  if (fd >= 0) close((int)fd);
  snprintf(q, sizeof(q), "%s/made/moved", dir);
  show("renameat", syscall(SYS_renameat, AT_FDCWD, p, AT_FDCWD, q));
  show("renameat2", syscall(SYS_renameat2, AT_FDCWD, q, AT_FDCWD, p, 0));
  snprintf(q, sizeof(q), "%s/made/link", dir);
  show("symlinkat", syscall(SYS_symlinkat, "file", AT_FDCWD, q));
  show("unlinkat", syscall(SYS_unlinkat, AT_FDCWD, q, 0));
  snprintf(q, sizeof(q), "%s/made/sock", dir);
  struct sockaddr_un addr = {.sun_family = AF_UNIX};
  snprintf(addr.sun_path, sizeof(addr.sun_path), "%.107s", q);
  int server = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0), client = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
  show("bind", syscall(SYS_bind, server, &addr, sizeof(addr)));
  listen(server, 1);
  show("connect", syscall(SYS_connect, client, &addr, sizeof(addr)));
  close(client);
  close(server);
  syscall(SYS_unlinkat, AT_FDCWD, q, 0);
  snprintf(q, sizeof(q), "%s/made", dir);
  show("chdir", syscall(SYS_chdir, q));
  show("cwd", getcwd(cwd, sizeof(cwd)) && strstr(cwd, "/made") ? 0 : -1);
  show("unlinkat-file", syscall(SYS_unlinkat, AT_FDCWD, p, 0));
  show("unlinkat-dir", syscall(SYS_unlinkat, AT_FDCWD, q, AT_REMOVEDIR));
  return 0;
}
"""

ALLOCATOR = r"""
#define _GNU_SOURCE
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/syscall.h>
#include <unistd.h>

extern void *__libc_malloc(size_t);
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static int ready;

void *malloc(size_t size) {
  if (!ready) {
    pthread_mutex_lock(&lock);
    if (!ready) {
      long fd = syscall(SYS_openat, AT_FDCWD, "/proc/self/stat", O_RDONLY | O_CLOEXEC);
      if (fd >= 0) close((int)fd);
      ready = 1;
    }
    pthread_mutex_unlock(&lock);
  }
  return __libc_malloc(size);
}

int main(void) {
  puts("ready");
  return 0;
}
"""

EARLY = r"""
#include <stdlib.h>

__attribute__((constructor)) static void early(void) { free(malloc(1)); }
"""

CALLS = ("mkdirat", "openat", "truncate", "fchmodat", "utimensat", "futimens", "renameat", "renameat2", "symlinkat", "unlinkat",
         "bind", "connect", "chdir", "cwd", "unlinkat-file", "unlinkat-dir")


class NativeHookPathSyscallsTest(unittest.TestCase):
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
        cls.probe = tmp / "probe"
        subprocess.run([cls.cc, "-O1", "-o", str(cls.probe), str(tmp / "probe.c")], check=True)
        cls.root = tmp / "root"
        (cls.root / "opt").mkdir(parents=True)
        (tmp / "allocator.c").write_text(ALLOCATOR)
        cls.allocator = tmp / "allocator"
        subprocess.run([cls.cc, "-O1", "-rdynamic", "-pthread", "-o", str(cls.allocator), str(tmp / "allocator.c")], check=True)
        (tmp / "early.c").write_text(EARLY)
        cls.early = tmp / "early.so"
        subprocess.run([cls.cc, "-shared", "-fPIC", "-O1", "-o", str(cls.early), str(tmp / "early.c")], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def run_program(self, argv, hook_env):
        env = {k: v for k, v in os.environ.items() if k not in ("LD_PRELOAD", "DDNATV_ROOT")}
        if self.prefix:
            cmd = list(self.prefix) + [arg for key, value in hook_env.items() for arg in ("-E", f"{key}={value}")]
        else:
            cmd = []
            env.update(hook_env)
        return subprocess.run(cmd + argv, env=env, capture_output=True, text=True, timeout=120)

    def run_probe(self, guest_dir, hook=True):
        hook_env = {"DDNATV_ROOT": str(self.root), "LD_PRELOAD": str(self.hook)} if hook else {}
        out = self.run_program([str(self.probe), guest_dir], hook_env)
        self.assertEqual(out.returncode, 0, out.stderr)
        return {line.split("\t")[0]: int(line.split("\t")[1]) for line in out.stdout.splitlines()}

    def test_guest_paths_through_syscall_land_in_the_root(self):
        results = self.run_probe("/opt")
        for call in CALLS:
            with self.subTest(call=call):
                self.assertEqual(1, results[call])
        self.assertEqual([], list((self.root / "opt").iterdir()))

    def test_paths_already_in_the_root_are_kept(self):
        results = self.run_probe(str(self.root / "opt"))
        for call in CALLS:
            with self.subTest(call=call):
                self.assertEqual(1, results[call])

    def test_without_the_hook_guest_paths_reach_the_host(self):
        results = self.run_probe("/opt/droiddeck-hook-probe-missing", hook=False)
        self.assertEqual(0, results["mkdirat"])
        self.assertEqual(0, results["openat"])
        self.assertEqual(0, results["chdir"])

    def test_an_allocator_opening_files_before_the_hook_starts_does_not_hang(self):
        hook_env = {"DDNATV_ROOT": str(self.root), "DDNATV_BINDS": "/proc:/proc",
                    "LD_PRELOAD": f"{self.hook}:{self.early}"}
        out = self.run_program([str(self.allocator)], hook_env)
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertEqual("ready", out.stdout.strip())


if __name__ == "__main__":
    unittest.main()
