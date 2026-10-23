import ctypes
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile
import unittest

PRELOAD = Path(__file__).resolve().parents[1] / "linuxfs/preload"

NTDLL = r"""
#include <signal.h>
__attribute__((noinline)) void syscall_dispatcher_return_slowpath(void) { raise(SIGUSR2); }
void slowpath(void) {
  syscall_dispatcher_return_slowpath();
  __asm__ volatile("" ::: "memory");
}
void slowpath_other(int sig) {
  raise(sig);
  __asm__ volatile("" ::: "memory");
}
"""

PROGRAM = r"""
#define _GNU_SOURCE
#include <asm/sigcontext.h>
#include <pthread.h>
#include <setjmp.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ucontext.h>
#include <unistd.h>

void slowpath(void);
void slowpath_other(int sig);

static char altstack[65536];
static char landing_stack[65536] __attribute__((aligned(16)));
static volatile int mode;
static int calls;
static volatile int on_alt, usr2_blocked, usr1_blocked, code_ok, stack_ok;
static volatile long handler_sp;
static jmp_buf back;
static uint64_t saved[31];
static unsigned __int128 saved_v[32];
static uint32_t saved_fpsr;

void landing(void);
__asm__(".text\n.globl landing\n.type landing, %function\nlanding:\n"
        "  sub sp, sp, #784\n"
        "  stp x0, x1, [sp, #0]\n  stp x2, x3, [sp, #16]\n  stp x4, x5, [sp, #32]\n  stp x6, x7, [sp, #48]\n"
        "  stp x8, x9, [sp, #64]\n  stp x10, x11, [sp, #80]\n  stp x12, x13, [sp, #96]\n  stp x14, x15, [sp, #112]\n"
        "  stp x16, x17, [sp, #128]\n  stp x18, x19, [sp, #144]\n  stp x20, x21, [sp, #160]\n  stp x22, x23, [sp, #176]\n"
        "  stp x24, x25, [sp, #192]\n  stp x26, x27, [sp, #208]\n  stp x28, x29, [sp, #224]\n  str x30, [sp, #240]\n"
        "  add x9, sp, #256\n"
        "  stp q0, q1, [x9, #0]\n  stp q2, q3, [x9, #32]\n  stp q4, q5, [x9, #64]\n  stp q6, q7, [x9, #96]\n"
        "  stp q8, q9, [x9, #128]\n  stp q10, q11, [x9, #160]\n  stp q12, q13, [x9, #192]\n  stp q14, q15, [x9, #224]\n"
        "  stp q16, q17, [x9, #256]\n  stp q18, q19, [x9, #288]\n  stp q20, q21, [x9, #320]\n  stp q22, q23, [x9, #352]\n"
        "  stp q24, q25, [x9, #384]\n  stp q26, q27, [x9, #416]\n  stp q28, q29, [x9, #448]\n  stp q30, q31, [x9, #480]\n"
        "  mrs x10, fpsr\n  str w10, [sp, #768]\n"
        "  mov x0, sp\n  bl landed\n  brk #0\n");

void landed(uint64_t *regs) {
  memcpy(saved, regs, sizeof(saved));
  memcpy(saved_v, regs + 32, sizeof(saved_v));
  saved_fpsr = *(uint32_t *)(regs + 96);
  longjmp(back, 1);
}

static struct fpsimd_context *fpsimd_of(ucontext_t *uc) {
  struct _aarch64_ctx *c = (struct _aarch64_ctx *)uc->uc_mcontext.__reserved;
  while (c->magic) {
    if (c->magic == FPSIMD_MAGIC) return (struct fpsimd_context *)c;
    c = (struct _aarch64_ctx *)((char *)c + c->size);
  }
  return NULL;
}

static void handler(int sig, siginfo_t *info, void *ctx) {
  ucontext_t *uc = ctx;
  sigset_t now;
  long sp = (long)&now;
  __atomic_add_fetch(&calls, 1, __ATOMIC_RELAXED);
  handler_sp = sp;
  on_alt = sp >= (long)altstack && sp < (long)altstack + (long)sizeof(altstack);
  pthread_sigmask(SIG_BLOCK, NULL, &now);
  usr2_blocked = sigismember(&now, SIGUSR2);
  usr1_blocked = sigismember(&now, SIGUSR1);
  code_ok = sig == SIGUSR2 && info->si_signo == SIGUSR2 && info->si_code == SI_TKILL && info->si_pid == getpid();
  stack_ok = uc->uc_stack.ss_sp == altstack && uc->uc_stack.ss_size == sizeof(altstack) && !(uc->uc_stack.ss_flags & SS_DISABLE);
  if (mode == 1) {
    struct fpsimd_context *fp = fpsimd_of(uc);
    for (int i = 0; i <= 28; i++) uc->uc_mcontext.regs[i] = 0x1000 + i;
    uc->uc_mcontext.regs[29] = 0x2900;
    uc->uc_mcontext.regs[30] = 0x3000;
    uc->uc_mcontext.sp = (uint64_t)(landing_stack + sizeof(landing_stack));
    uc->uc_mcontext.pc = (uint64_t)landing;
    uc->uc_mcontext.pstate = 0x60000000;
    for (int i = 0; i < 32; i++) fp->vregs[i] = ((unsigned __int128)(0x7700 + i) << 64) | (0x5500 + i);
    fp->fpsr = 0x8;
  }
}

static void usr1(int sig, siginfo_t *info, void *ctx) {
  (void)sig;
  (void)info;
  (void)ctx;
}

uint64_t kept_x[10];
double kept_d[8];

static int keep_callee_saved(void) {
  uint64_t *out = kept_x;
  double *d = kept_d;
  __asm__ volatile(
      "mov x19, #19\n mov x20, #20\n mov x21, #21\n mov x22, #22\n mov x23, #23\n"
      "mov x24, #24\n mov x25, #25\n mov x26, #26\n mov x27, #27\n mov x28, #28\n"
      "fmov d8, #1.0\n fmov d9, #2.0\n fmov d10, #3.0\n fmov d11, #4.0\n"
      "fmov d12, #5.0\n fmov d13, #6.0\n fmov d14, #7.0\n fmov d15, #8.0\n"
      "bl slowpath\n"
      "adrp x0, kept_x\n add x0, x0, :lo12:kept_x\n"
      "stp x19, x20, [x0, #0]\n stp x21, x22, [x0, #16]\n stp x23, x24, [x0, #32]\n"
      "stp x25, x26, [x0, #48]\n stp x27, x28, [x0, #64]\n"
      "adrp x0, kept_d\n add x0, x0, :lo12:kept_d\n"
      "stp d8, d9, [x0, #0]\n stp d10, d11, [x0, #16]\n stp d12, d13, [x0, #32]\n stp d14, d15, [x0, #48]\n"
      :
      :
      : "x0", "x1", "x2", "x3", "x4", "x5", "x6", "x7", "x8", "x9", "x10", "x11", "x12", "x13", "x14", "x15",
        "x16", "x17", "x19", "x20", "x21", "x22", "x23", "x24", "x25", "x26", "x27", "x28", "x30",
        "v0", "v1", "v2", "v3", "v4", "v5", "v6", "v7", "v8", "v9", "v10", "v11", "v12", "v13", "v14", "v15",
        "v16", "v17", "v18", "v19", "v20", "v21", "v22", "v23", "v24", "v25", "v26", "v27", "v28", "v29", "v30",
        "v31", "memory", "cc");
  for (int i = 0; i < 10; i++)
    if (out[i] != (uint64_t)(19 + i)) return 0;
  for (int i = 0; i < 8; i++)
    if (d[i] != (double)(i + 1)) return 0;
  return 1;
}

static void *stress(void *arg) {
  (void)arg;
  for (int i = 0; i < 2000; i++) slowpath();
  return NULL;
}

int main(int argc, char **argv) {
  stack_t ss = {.ss_sp = altstack, .ss_size = sizeof(altstack), .ss_flags = 0};
  struct sigaction sa;
  sigset_t mask, now;
  sigaltstack(&ss, NULL);
  memset(&sa, 0, sizeof(sa));
  sa.sa_sigaction = handler;
  sa.sa_flags = SA_SIGINFO | SA_ONSTACK | SA_RESTART;
  sigemptyset(&sa.sa_mask);
  sigaddset(&sa.sa_mask, SIGUSR1);
  sigaction(SIGUSR2, &sa, NULL);
  sa.sa_sigaction = usr1;
  sigemptyset(&sa.sa_mask);
  sigaction(SIGUSR1, &sa, NULL);

  if (argc > 1 && strcmp(argv[1], "bench") == 0) {
    int n = argc > 2 ? atoi(argv[2]) : 20000;
    struct timespec a, b;
    clock_gettime(CLOCK_MONOTONIC, &a);
    for (int i = 0; i < n; i++) slowpath();
    clock_gettime(CLOCK_MONOTONIC, &b);
    printf("%.2f us per raise, alt %d\n", ((b.tv_sec - a.tv_sec) * 1e9 + (b.tv_nsec - a.tv_nsec)) / 1e3 / n, on_alt);
    return 0;
  }

  mode = 0;
  calls = 0;
  int kept = keep_callee_saved();
  pthread_sigmask(SIG_BLOCK, NULL, &now);
  printf("returned calls=%d kept=%d usr2_blocked=%d usr1_blocked=%d code=%d stack=%d after_usr2=%d after_usr1=%d\n",
         calls, kept, usr2_blocked, usr1_blocked, code_ok, stack_ok, sigismember(&now, SIGUSR2),
         sigismember(&now, SIGUSR1));
  printf("path %s\n", on_alt ? "kernel" : "inline");

  mode = 1;
  calls = 0;
  if (!setjmp(back)) {
    slowpath();
    printf("redirect did not happen\n");
  } else {
    int ok = 1;
    for (int i = 0; i <= 28; i++)
      if (saved[i] != (uint64_t)(0x1000 + i)) ok = 0;
    if (saved[29] != 0x2900 || saved[30] != 0x3000) ok = 0;
    for (int i = 0; i < 32; i++)
      if (saved_v[i] != (((unsigned __int128)(0x7700 + i) << 64) | (0x5500 + i))) ok = 0;
    printf("redirect calls=%d regs=%d fpsr=%u\n", calls, ok, saved_fpsr & 0x8);
  }
  mode = 0;

  sigemptyset(&mask);
  sigaddset(&mask, SIGUSR2);
  pthread_sigmask(SIG_BLOCK, &mask, NULL);
  calls = 0;
  slowpath();
  int while_blocked = calls;
  pthread_sigmask(SIG_UNBLOCK, &mask, NULL);
  printf("blocked during=%d after=%d\n", while_blocked, calls);

  calls = 0;
  slowpath_other(SIGUSR1);
  raise(SIGUSR2);
  printf("other calls=%d main_path=%s\n", calls, on_alt ? "kernel" : "inline");

  calls = 0;
  pthread_t t[4];
  for (int i = 0; i < 4; i++) pthread_create(&t[i], NULL, stress, NULL);
  for (int i = 0; i < 4; i++) pthread_join(t[i], NULL);
  printf("threads calls=%d\n", calls);
  return 0;
}
"""


def has_gcs_hwcap():
    try:
        getauxval = ctypes.CDLL(None).getauxval
    except (AttributeError, OSError):
        return False
    getauxval.restype = ctypes.c_ulong
    getauxval.argtypes = [ctypes.c_ulong]
    return bool(getauxval(16) & (1 << 32))


def runner():
    if platform.machine() in ("aarch64", "arm64") and shutil.which("gcc") and not has_gcs_hwcap():
        return "gcc", []
    if shutil.which("aarch64-linux-gnu-gcc") and shutil.which("qemu-aarch64") and Path("/usr/aarch64-linux-gnu").is_dir():
        return "aarch64-linux-gnu-gcc", ["qemu-aarch64", "-cpu", "neoverse-n1", "-L", "/usr/aarch64-linux-gnu"]
    return None, None


class Usr2PreloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cc, cls.prefix = runner()
        if cls.cc is None:
            raise unittest.SkipTest("no aarch64 compiler and runner")
        cls.tmp = tempfile.TemporaryDirectory()
        tmp = Path(cls.tmp.name)
        (tmp / "ntdll.c").write_text(NTDLL)
        (tmp / "prog.c").write_text(PROGRAM)
        sources = sorted(str(p) for p in PRELOAD.glob("*.c"))
        subprocess.run([cls.cc, "-shared", "-fPIC", "-O2", "-Wall", "-pthread", "-o", str(tmp / "libblsession.so"),
                        *sources, "-ldl"], check=True)
        subprocess.run([cls.cc, "-shared", "-fPIC", "-O2", "-o", str(tmp / "ntdll.so"), str(tmp / "ntdll.c")], check=True)
        subprocess.run([cls.cc, "-O1", "-U_FORTIFY_SOURCE", "-pthread", "-o", str(tmp / "prog"), str(tmp / "prog.c"), str(tmp / "ntdll.so"),
                        "-Wl,-rpath," + str(tmp)], check=True)
        cls.program = tmp / "prog"
        cls.preload = tmp / "libblsession.so"

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def run_program(self, preload):
        env = dict(os.environ)
        env.pop("LD_PRELOAD", None)
        cmd = list(self.prefix) + [str(self.program)]
        if preload:
            if self.prefix:
                cmd = list(self.prefix) + ["-E", "LD_PRELOAD=" + str(self.preload), str(self.program)]
            else:
                env["LD_PRELOAD"] = str(self.preload)
        out = subprocess.run(cmd, env=env, capture_output=True, text=True, timeout=120)
        self.assertEqual(out.returncode, 0, out.stderr)
        return out.stdout.splitlines()

    def test_a_ntdll_raise_runs_the_handler_without_a_kernel_signal(self):
        lines = self.run_program(True)
        self.assertIn("path inline", lines)
        self.assertIn("returned calls=1 kept=1 usr2_blocked=1 usr1_blocked=1 code=1 stack=1 after_usr2=0 after_usr1=0", lines)

    def test_b_context_the_handler_writes_is_the_one_that_resumes(self):
        self.assertIn("redirect calls=1 regs=1 fpsr=8", self.run_program(True))

    def test_c_behaves_like_the_kernel_signal(self):
        stock = [line for line in self.run_program(False) if not line.startswith("path ")]
        inline = [line for line in self.run_program(True) if not line.startswith("path ")]
        self.assertIn("returned calls=1 kept=1 usr2_blocked=1 usr1_blocked=1 code=1 stack=1 after_usr2=0 after_usr1=0", stock)
        self.assertEqual(stock, inline)

    def test_d_blocked_other_signals_and_other_callers_use_the_kernel(self):
        lines = self.run_program(True)
        self.assertIn("blocked during=0 after=1", lines)
        self.assertIn("other calls=1 main_path=kernel", lines)
        self.assertIn("threads calls=8000", lines)


if __name__ == "__main__":
    unittest.main()
