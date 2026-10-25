import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

PRELOAD = Path(__file__).resolve().parents[1] / "linuxfs/preload"

PROGRAM = r"""
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <sys/mman.h>

int main(int argc, char **argv) {
  if (argc < 2) return 2;
  int fd = open(argv[1], O_RDONLY);
  void *code = mmap(NULL, 4096, PROT_READ | PROT_EXEC, MAP_PRIVATE, fd, 0);
  printf("map %d\n", code == MAP_FAILED ? errno : 0);
  void *data = mmap(NULL, 4096, PROT_READ, MAP_PRIVATE, fd, 0);
  printf("protect %d\n", mprotect(data, 4096, PROT_READ | PROT_EXEC) ? errno : 0);
  printf("read %c\n", code == MAP_FAILED ? '-' : *(char *)code);
  return 0;
}
"""


def noexec_folder():
    for folder in ("/run/lock", "/dev/shm", "/run/user/%d" % os.getuid()):
        try:
            if os.access(folder, os.W_OK) and os.statvfs(folder).f_flag & os.ST_NOEXEC:
                return folder
        except OSError:
            pass
    return None


@unittest.skipUnless(shutil.which("cc") and noexec_folder(), "needs cc and a writable noexec mount")
class NoexecPreloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp())
        cls.lib = cls.tmp / "libnoexec.so"
        subprocess.run(["cc", "-O2", "-Wall", "-Werror", "-fPIC", "-shared", "-pthread", "-DNOEXEC_TEST", "-o", str(cls.lib),
                        str(PRELOAD / "noexec.c"), str(PRELOAD / "fsync.c"), str(PRELOAD / "robust.c"), str(PRELOAD / "writewatch.c"), "-ldl"], check=True)
        source = cls.tmp / "program.c"
        source.write_text(PROGRAM)
        for name in ("FEX", "game"):
            subprocess.run(["cc", "-O2", "-o", str(cls.tmp / name), str(source)], check=True)
        cls.target = Path(tempfile.mkdtemp(dir=noexec_folder())) / "Game.x86_64"
        cls.target.write_bytes(b"\x7fELF" + bytes(4092))

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)
        shutil.rmtree(cls.target.parent, ignore_errors=True)

    def run_as(self, name, preload=True):
        env = dict(os.environ, LD_PRELOAD=str(self.lib)) if preload else {k: v for k, v in os.environ.items() if k != "LD_PRELOAD"}
        result = subprocess.run([str(self.tmp / name), str(self.target)], env=env, capture_output=True, text=True, check=True)
        return result.stdout.split("\n")[:3]

    def test_fex_maps_a_program_on_noexec_storage(self):
        self.assertEqual(self.run_as("FEX"), ["map 0", "protect 0", "read \x7f"])

    def test_the_kernel_still_refuses_everyone_else(self):
        for refused in (self.run_as("game"), self.run_as("FEX", preload=False)):
            self.assertIn(refused[0], ("map 1", "map 13"))
            self.assertEqual(refused[1:], ["protect 13", "read -"])


EXECMOD = r"""
#define _GNU_SOURCE
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

static int private_file(void *addr) {
  FILE *maps = fopen("/proc/self/maps", "r");
  char line[512];
  int found = 0;
  while (maps && fgets(line, sizeof line, maps)) {
    unsigned long lo, hi;
    char perms[5];
    if (sscanf(line, "%lx-%lx %4s", &lo, &hi, perms) != 3) continue;
    if ((unsigned long)addr >= lo && (unsigned long)addr < hi) {
      found = perms[3] == 'p' && strchr(line, '/') != NULL;
      break;
    }
  }
  if (maps) fclose(maps);
  return found;
}

int mprotect(void *addr, size_t len, int prot) {
  if ((prot & PROT_EXEC) && private_file(addr)) {
    errno = EACCES;
    return -1;
  }
  return (int)syscall(SYS_mprotect, addr, len, prot);
}
"""

CALLER = r"""
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

int main(int argc, char **argv) {
  int fd = open(argv[1], O_RDWR);
  unsigned char *code = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_PRIVATE, fd, 0);
#if defined(__aarch64__)
  static const unsigned int body[] = {0x52800540, 0xd65f03c0};
#else
  static const unsigned char body[] = {0xb8, 0x2a, 0x00, 0x00, 0x00, 0xc3};
#endif
  memcpy(code + 64, body, sizeof body);
  int result = mprotect(code, 4096, PROT_READ | PROT_EXEC);
  printf("protect %d\n", result ? errno : 0);
  __builtin___clear_cache((char *)code, (char *)code + 4096);
  if (!result) printf("call %d\n", ((int (*)(void))(code + 64))());
  printf("data %d\n", code[0]);
  char line[512];
  FILE *maps = fopen("/proc/self/maps", "r");
  while (maps && fgets(line, sizeof line, maps)) {
    unsigned long lo, hi;
    if (sscanf(line, "%lx-%lx", &lo, &hi) == 2 && (unsigned long)code >= lo && (unsigned long)code < hi)
      printf("shared %d\n", strstr(line, ".droiddeck-rx") != NULL);
  }
  return 0;
}
"""


@unittest.skipUnless(shutil.which("cc") and os.uname().sysname == "Linux", "needs cc on Linux")
class ExecmodRebakeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp())
        cls.lib = cls.tmp / "libnoexec.so"
        subprocess.run(["cc", "-O2", "-Wall", "-Werror", "-fPIC", "-shared", "-pthread", "-DNOEXEC_TEST", "-o", str(cls.lib),
                        str(PRELOAD / "noexec.c"), str(PRELOAD / "fsync.c"), str(PRELOAD / "robust.c"), "-ldl"], check=True)
        (cls.tmp / "execmod.c").write_text(EXECMOD)
        cls.execmod = cls.tmp / "libexecmod.so"
        subprocess.run(["cc", "-O2", "-fPIC", "-shared", "-o", str(cls.execmod), str(cls.tmp / "execmod.c")], check=True)
        (cls.tmp / "caller.c").write_text(CALLER)
        cls.caller = cls.tmp / "wine"
        subprocess.run(["cc", "-O2", "-o", str(cls.caller), str(cls.tmp / "caller.c")], check=True)
        cls.image = cls.tmp / "ntdll.dll"
        cls.image.write_bytes(bytes([7]) + bytes(4095))

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def setUp(self):
        self.cache = self.tmp / self._testMethodName / "rx"
        self.cache.mkdir(parents=True)

    def tearDown(self):
        shutil.rmtree(self.tmp / self._testMethodName, ignore_errors=True)

    def run_with(self, *libs):
        env = dict(os.environ, LD_PRELOAD=":".join(str(lib) for lib in libs),
                   DROIDDECK_NOEXEC_CACHE_DIR=str(self.cache))
        result = subprocess.run([str(self.caller), str(self.image)], env=env, capture_output=True, text=True, check=True)
        return result.stdout.split()

    def test_a_refused_relocated_image_runs_from_anonymous_memory(self):
        self.assertEqual(self.run_with(self.lib, self.execmod), ["protect", "0", "call", "42", "data", "7", "shared", "1"])

    def test_processes_share_one_copy_of_the_code(self):
        self.run_with(self.lib, self.execmod)
        self.run_with(self.lib, self.execmod)
        self.assertEqual(len(os.listdir(self.cache)), 1)

    def test_without_the_preload_the_refusal_stands(self):
        self.assertEqual(self.run_with(self.execmod), ["protect", "13", "data", "7", "shared", "0"])

    def test_the_file_itself_is_not_changed(self):
        self.run_with(self.lib, self.execmod)
        self.assertEqual(self.image.read_bytes()[:65], bytes([7]) + bytes(64))


if __name__ == "__main__":
    unittest.main()
