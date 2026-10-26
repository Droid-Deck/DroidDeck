import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

PRELOAD = Path(__file__).resolve().parents[1] / "linuxfs/preload"

NTDLL = r"""
#define _GNU_SOURCE
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

__attribute__((noinline)) int ntdll_open(const char *path) {
  int fd = open(path, O_RDONLY | O_CLOEXEC);
  __asm__ volatile("" ::: "memory");
  return fd;
}

__attribute__((noinline)) int ntdll_openat(const char *path) {
  int fd = openat(AT_FDCWD, path, O_RDONLY | O_CLOEXEC);
  __asm__ volatile("" ::: "memory");
  return fd;
}

__attribute__((noinline)) FILE *ntdll_fopen(const char *path) {
  FILE *f = fopen(path, "r");
  __asm__ volatile("" ::: "memory");
  return f;
}
"""

PROGRAM = r"""
#define _GNU_SOURCE
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <unistd.h>

int ntdll_open(const char *path);
int ntdll_openat(const char *path);
FILE *ntdll_fopen(const char *path);

static void report(const char *name, int fd) {
  char link[64], target[256] = "", text[16384], *field;
  ssize_t n;
  long tracer = -1;
  if (fd < 0) {
    printf("%s failed\n", name);
    return;
  }
  snprintf(link, sizeof(link), "/proc/self/fd/%d", fd);
  n = readlink(link, target, sizeof(target) - 1);
  target[n > 0 ? n : 0] = '\0';
  n = pread(fd, text, sizeof(text) - 1, 0);
  text[n > 0 ? n : 0] = '\0';
  if ((field = strstr(text, "\nTracerPid:\t")) != NULL) tracer = strtol(field + 12, NULL, 10);
  const char *kind = strncmp(target, "/proc/", 6) == 0 ? "proc"
      : strncmp(target, "/memfd:", 7) == 0 ? "memfd"
      : strncmp(target, "/tmp/.droiddeck-status-", 23) == 0 ? "copy" : target;
  printf("%s kind=%s tracer=%s rdonly=%d vm=%d\n", name, kind, tracer == 0 ? "hidden" : tracer > 0 ? "shown" : "missing",
         (fcntl(fd, F_GETFL) & O_ACCMODE) == O_RDONLY, strstr(text, "\nVmRSS:") != NULL);
  close(fd);
}

static void report_stream(const char *name, FILE *f) {
  if (f == NULL) {
    printf("%s failed\n", name);
    return;
  }
  report(name, dup(fileno(f)));
  fclose(f);
}

int main(void) {
  int ready[2];
  if (pipe(ready) != 0) return 1;
  pid_t child = fork();
  if (child == 0) {
    char ok = ptrace(PTRACE_TRACEME, 0, NULL, NULL) == 0;
    if (write(ready[1], &ok, 1) != 1) _exit(1);
    for (;;) pause();
  }
  char ok = 0;
  if (read(ready[0], &ok, 1) != 1 || !ok) {
    kill(child, SIGKILL);
    waitpid(child, NULL, 0);
    puts("no ptrace");
    return 0;
  }
  char other[64], self[64];
  snprintf(other, sizeof(other), "/proc/%d/status", (int)child);
  snprintf(self, sizeof(self), "/proc/%d/status", (int)getpid());
  report("main_other_open", open(other, O_RDONLY | O_CLOEXEC));
  report_stream("main_other_fopen", fopen(other, "r"));
  report("main_self_open", open("/proc/self/status", O_RDONLY | O_CLOEXEC));
  report("ntdll_other_open", ntdll_open(other));
  report("ntdll_other_openat", ntdll_openat(other));
  report_stream("ntdll_other_fopen", ntdll_fopen(other));
  report_stream("ntdll_self_fopen", ntdll_fopen(self));
  kill(child, SIGKILL);
  waitpid(child, NULL, 0);
  return 0;
}
"""


class TracerPreloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not shutil.which("cc"):
            raise unittest.SkipTest("no compiler")
        cls.tmp = tempfile.TemporaryDirectory()
        tmp = Path(cls.tmp.name)
        (tmp / "ntdll.c").write_text(NTDLL)
        (tmp / "prog.c").write_text(PROGRAM)
        sources = sorted(str(p) for p in PRELOAD.glob("*.c"))
        subprocess.run(["cc", "-shared", "-fPIC", "-O2", "-Wall", "-pthread", "-o", str(tmp / "libblsession.so"), *sources, "-ldl"],
                       check=True)
        subprocess.run(["cc", "-shared", "-fPIC", "-O2", "-o", str(tmp / "ntdll.so"), str(tmp / "ntdll.c")], check=True)
        subprocess.run(["cc", "-O1", "-o", str(tmp / "prog"), str(tmp / "prog.c"), str(tmp / "ntdll.so"), "-Wl,-rpath," + str(tmp)],
                       check=True)
        env = dict(os.environ, LD_PRELOAD=str(tmp / "libblsession.so"))
        out = subprocess.run([str(tmp / "prog")], env=env, capture_output=True, text=True, timeout=60)
        if out.returncode != 0:
            raise AssertionError(out.stdout + out.stderr)
        if "no ptrace" in out.stdout:
            raise unittest.SkipTest("ptrace not allowed")
        cls.lines = dict(line.split(" ", 1) for line in out.stdout.splitlines())

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_other_programs_still_see_no_tracer(self):
        self.assertEqual(self.lines["main_other_open"], "kind=copy tracer=hidden rdonly=1 vm=1")
        self.assertEqual(self.lines["main_other_fopen"], "kind=copy tracer=hidden rdonly=1 vm=1")
        self.assertEqual(self.lines["main_self_open"], "kind=memfd tracer=hidden rdonly=0 vm=1")

    def test_wine_reads_the_real_status_file(self):
        for name in ("ntdll_other_open", "ntdll_other_openat", "ntdll_other_fopen"):
            self.assertEqual(self.lines[name], "kind=proc tracer=shown rdonly=1 vm=1", name)
        self.assertEqual(self.lines["ntdll_self_fopen"], "kind=proc tracer=hidden rdonly=1 vm=1")


if __name__ == "__main__":
    unittest.main()
