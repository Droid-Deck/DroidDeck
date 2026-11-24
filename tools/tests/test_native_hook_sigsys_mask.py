import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from tools.tests.test_usr2_preload import runner

HOOK_BUILD = Path(__file__).resolve().parents[1] / "nativeguest/build.sh"

PROBE = r"""
#define _GNU_SOURCE
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

static uint64_t current(void) {
  uint64_t mask = 0;
  syscall(SYS_rt_sigprocmask, SIG_SETMASK, NULL, &mask, sizeof(mask));
  return mask;
}

struct kact { void *handler; unsigned long flags; void *restorer; uint64_t mask; };

static void own(int sig, siginfo_t *info, void *ctx) { (void)sig; (void)info; (void)ctx; }

static long raw_sigaction(int sig, const struct kact *act, struct kact *old) {
  register long x8 __asm__("x8") = SYS_rt_sigaction;
  register long x0 __asm__("x0") = sig;
  register long x1 __asm__("x1") = (long)act;
  register long x2 __asm__("x2") = (long)old;
  register long x3 __asm__("x3") = 8;
  __asm__ volatile("svc #0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3) : "memory");
  return x0;
}

static void handlers(void) {
  struct kact act = { (void *)own, SA_SIGINFO, NULL, 0 }, seen, kernel;
  syscall(SYS_rt_sigaction, SIGSYS, &act, NULL, 8);
  syscall(SYS_rt_sigaction, SIGSYS, NULL, &seen, 8);
  raw_sigaction(SIGSYS, NULL, &kernel);
  printf("sigaction\t%d\t%d\n", seen.handler == (void *)own, kernel.handler == (void *)own);
  struct kact usr1 = { (void *)own, SA_SIGINFO, NULL, 0 };
  syscall(SYS_rt_sigaction, SIGUSR1, &usr1, NULL, 8);
  raw_sigaction(SIGUSR1, NULL, &kernel);
  printf("sigaction-usr1\t%d\n", kernel.handler == (void *)own);
}

static void show(const char *how) {
  uint64_t mask = current();
  printf("%s\t%d\t%d\n", how, !!(mask & (1ULL << (SIGSYS - 1))), !!(mask & (1ULL << (SIGUSR1 - 1))));
  uint64_t none = 0;
  syscall(SYS_rt_sigprocmask, SIG_SETMASK, &none, NULL, sizeof(none));
}

int main(int argc, char **argv) {
  uint64_t all = ~0ULL;
  sigset_t set;
  sigfillset(&set);
  for (int i = 1; i < argc; i++) {
    if (strcmp(argv[i], "syscall-setmask") == 0) syscall(SYS_rt_sigprocmask, SIG_SETMASK, &all, NULL, sizeof(all));
    else if (strcmp(argv[i], "syscall-block") == 0) syscall(SYS_rt_sigprocmask, SIG_BLOCK, &all, NULL, sizeof(all));
    else if (strcmp(argv[i], "syscall-unblock") == 0) {
      syscall(SYS_rt_sigprocmask, SIG_BLOCK, &all, NULL, sizeof(all));
      uint64_t usr1 = 1ULL << (SIGUSR1 - 1);
      syscall(SYS_rt_sigprocmask, SIG_UNBLOCK, &usr1, NULL, sizeof(usr1));
    }
    else if (strcmp(argv[i], "sigprocmask") == 0) sigprocmask(SIG_SETMASK, &set, NULL);
    else if (strcmp(argv[i], "pthread_sigmask") == 0) pthread_sigmask(SIG_SETMASK, &set, NULL);
    else if (strcmp(argv[i], "sigaction") == 0) {
      handlers();
      continue;
    }
    show(argv[i]);
  }
  return 0;
}
"""

WAYS = ("syscall-setmask", "syscall-block", "sigprocmask", "pthread_sigmask")


class NativeHookSigsysMaskTest(unittest.TestCase):
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
        subprocess.run([cls.cc, "-O1", "-pthread", "-o", str(cls.probe), str(tmp / "probe.c")], check=True)
        cls.root = tmp / "root"
        cls.root.mkdir()

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def masks(self, *ways, hook=True):
        hook_env = {"DDNATV_ROOT": str(self.root), "LD_PRELOAD": str(self.hook)} if hook else {}
        env = {k: v for k, v in os.environ.items() if k not in ("LD_PRELOAD", "DDNATV_ROOT")}
        if self.prefix:
            cmd = list(self.prefix) + [arg for key, value in hook_env.items() for arg in ("-E", f"{key}={value}")]
        else:
            cmd = []
            env.update(hook_env)
        out = subprocess.run(cmd + [str(self.probe), *ways], env=env, capture_output=True, text=True, timeout=120)
        self.assertEqual(out.returncode, 0, out.stderr)
        return {line.split("\t")[0]: tuple(int(v) for v in line.split("\t")[1:]) for line in out.stdout.splitlines()}

    def test_blocking_every_signal_leaves_sigsys_open(self):
        masks = self.masks(*WAYS)
        for way in WAYS:
            with self.subTest(way=way):
                self.assertEqual((0, 1), masks[way])

    def test_unblocking_is_unchanged(self):
        self.assertEqual((0, 0), self.masks("syscall-unblock")["syscall-unblock"])

    def test_a_sigsys_handler_set_through_syscall_keeps_the_hooks(self):
        seen = self.masks("sigaction")
        self.assertEqual((1, 0), seen["sigaction"])
        self.assertEqual((1,), seen["sigaction-usr1"])

    def test_without_the_hook_a_sigsys_handler_reaches_the_kernel(self):
        self.assertEqual((1, 1), self.masks("sigaction", hook=False)["sigaction"])

    def test_without_the_hook_every_signal_is_blocked(self):
        masks = self.masks(*WAYS, hook=False)
        for way in WAYS:
            with self.subTest(way=way):
                self.assertEqual((1, 1), masks[way])


if __name__ == "__main__":
    unittest.main()
