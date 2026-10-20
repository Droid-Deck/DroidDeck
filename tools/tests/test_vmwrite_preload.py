import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

PRELOAD = Path(__file__).resolve().parents[1] / "linuxfs/preload"

PROGRAM = r"""
#define _GNU_SOURCE
#include <errno.h>
#include <stdint.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>

int main(void) {
  unsigned char *code = mmap((void *)0xc0000000UL, 4096, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  if (sizeof(void *) == 4 && (uintptr_t)code <= 0x7fffffffU) return 3;
  unsigned char *data = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  memset(code, 0x90, 4096);
  mprotect(code, 4096, PROT_READ | PROT_EXEC);
  int ready[2];
  if (pipe(ready)) return 2;
  pid_t child = fork();
  if (child == 0) {
    prctl(PR_SET_PDEATHSIG, SIGKILL);
    close(ready[0]);
    if (write(ready[1], "", 1) != 1) _exit(1);
    pause();
    _exit(0);
  }
  char c;
  close(ready[1]);
  if (read(ready[0], &c, 1) != 1) return 2;
  unsigned char patch[4] = {0xB8, 0x78, 0x56, 0x34};
  struct iovec mine = {patch, sizeof patch};
  struct iovec theirs[2] = {{code, sizeof patch}, {data, sizeof patch}};
  for (int i = 0; i < 2; i++) {
    ssize_t n = process_vm_writev(child, &mine, 1, &theirs[i], 1, 0);
    printf("%s %zd %d", i ? "data" : "code", n, n < 0 ? errno : 0);
    unsigned char back[4] = {0};
    struct iovec got = {back, sizeof back};
    process_vm_readv(child, &got, 1, &theirs[i], 1, 0);
    printf(" %s\n", memcmp(back, patch, sizeof patch) ? "old" : "new");
  }
  struct iovec split[2] = {{patch, 2}, {patch + 2, 2}};
  ssize_t n = process_vm_writev(child, split, 2, &theirs[0], 1, 0);
  printf("split %zd %d\n", n, n < 0 ? errno : 0);
  n = process_vm_writev(child, (const struct iovec *)8, 1, &theirs[0], 1, 0);
  printf("invalid %zd %d\n", n, n < 0 ? errno : 0);
  kill(child, SIGKILL);
  waitpid(child, NULL, 0);
  return 0;
}
"""


@unittest.skipUnless(shutil.which("cc") and os.path.exists("/proc/self/mem"), "needs cc and /proc")
class VmWritePreloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp())
        cls.addClassCleanup(shutil.rmtree, cls.tmp, ignore_errors=True)
        source = cls.tmp / "program.c"
        source.write_text(PROGRAM)
        cls.bits32 = True
        for flags, sub in (([], "64"), (["-m32"], "32")):
            (cls.tmp / sub).mkdir()
            try:
                subprocess.run(["cc", *flags, "-O2", "-Wall", "-Werror", "-fPIC", "-shared", "-o",
                                str(cls.tmp / sub / "libvmwrite.so"), str(PRELOAD / "vmwrite.c"), "-ldl"],
                               check=True, capture_output=True)
                for name in ("wineserver", "game"):
                    subprocess.run(["cc", *flags, "-O2", "-o", str(cls.tmp / sub / name), str(source)],
                                   check=True, capture_output=True)
            except subprocess.CalledProcessError:
                if not flags:
                    raise
                cls.bits32 = False

    def run_as(self, name, preload=True, bits="64"):
        lib = self.tmp / bits / "libvmwrite.so"
        env = dict(os.environ, LD_PRELOAD=str(lib)) if preload else {k: v for k, v in os.environ.items() if k != "LD_PRELOAD"}
        result = subprocess.run([str(self.tmp / bits / name)], env=env, capture_output=True, text=True, check=True)
        return result.stdout.splitlines()

    def test_wineserver_writes_a_page_the_kernel_refused(self):
        self.assertEqual(self.run_as("wineserver"), ["code 4 0 new", "data 4 0 new", "split -1 14", "invalid -1 14"])

    def test_a_32_bit_wineserver_writes_above_2_gib(self):
        if not self.bits32:
            self.skipTest("needs cc -m32")
        self.assertEqual(self.run_as("wineserver", bits="32"), ["code 4 0 new", "data 4 0 new", "split -1 14", "invalid -1 14"])

    def test_everyone_else_keeps_the_kernel_answer(self):
        for refused in (self.run_as("game"), self.run_as("wineserver", preload=False)):
            self.assertEqual(refused, ["code -1 14 old", "data 4 0 new", "split -1 14", "invalid -1 14"])


if __name__ == "__main__":
    unittest.main()
