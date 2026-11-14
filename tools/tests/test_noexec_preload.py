import os
from pathlib import Path
import platform
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

IMAGE = r"""
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <sys/mman.h>
#include <unistd.h>

static void run(const char *name, void *code, size_t len, int prot) {
  int error = mprotect(code, len, prot) ? errno : 0;
  printf("%s %d %d\n", name, error, error ? -1 : ((int (*)(void))code)());
}

int main(int argc, char **argv) {
  if (argc < 2) return 2;
  size_t page = (size_t)sysconf(_SC_PAGESIZE);
  int fd = open(argv[1], O_RDONLY);
  run("private", mmap(NULL, page, PROT_READ | PROT_WRITE, MAP_PRIVATE, fd, 0), page, PROT_READ | PROT_EXEC);
  run("hidden", mmap(NULL, page, PROT_NONE, MAP_PRIVATE, fd, 0), page, PROT_READ | PROT_EXEC);
  char *mixed = mmap(NULL, 2 * page, PROT_READ, MAP_PRIVATE, fd, 0);
  if (mixed == MAP_FAILED || mmap(mixed + page, page, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, -1, 0) != mixed + page)
    return 3;
  run("mixed", mixed, page + 1, PROT_READ | PROT_EXEC);
  char *past = mmap(NULL, 3 * page, PROT_NONE, MAP_PRIVATE, fd, 0);
  if (past == MAP_FAILED || mmap(past + page, page, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, -1, 0) != past + page ||
      mmap(past + 2 * page, page, PROT_READ, MAP_PRIVATE | MAP_FIXED, fd, page) != past + 2 * page)
    return 3;
  int error = mprotect(past, 3 * page, PROT_READ | PROT_EXEC) ? errno : 0;
  past[page] = 7;
  printf("past %d %d\n", error, past[page]);
  error = mprotect(mmap(NULL, page, PROT_READ, MAP_SHARED, fd, 0), page, PROT_READ | PROT_EXEC) ? errno : 0;
  printf("shared %d\n", error);
  return 0;
}
"""

RETURN_42 = {"x86_64": bytes.fromhex("b82a000000c3"), "aarch64": bytes.fromhex("40058052c0035fd6")}


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
                        str(PRELOAD / "noexec.c"), str(PRELOAD / "fsync.c"), str(PRELOAD / "robust.c"), str(PRELOAD / "writewatch.c"), str(PRELOAD / "clockpause.c"), "-ldl"], check=True)
        source = cls.tmp / "program.c"
        source.write_text(PROGRAM)
        for name in ("FEX", "game"):
            subprocess.run(["cc", "-O2", "-o", str(cls.tmp / name), str(source)], check=True)
        image = cls.tmp / "image.c"
        image.write_text(IMAGE)
        subprocess.run(["cc", "-O2", "-o", str(cls.tmp / "wine"), str(image)], check=True)
        cls.target = Path(tempfile.mkdtemp(dir=noexec_folder())) / "Game.x86_64"
        cls.target.write_bytes(b"\x7fELF" + bytes(4092))
        cls.code = cls.target.parent / "game.exe"
        cls.code.write_bytes(RETURN_42.get(platform.machine(), b"").ljust(os.sysconf("SC_PAGE_SIZE"), b"\0"))

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)
        shutil.rmtree(cls.target.parent, ignore_errors=True)

    def run_as(self, name, preload=True, target=None, lines=3):
        env = dict(os.environ, LD_PRELOAD=str(self.lib)) if preload else {k: v for k, v in os.environ.items() if k != "LD_PRELOAD"}
        result = subprocess.run([str(self.tmp / name), str(target or self.target)], env=env, capture_output=True, text=True, check=True)
        return result.stdout.split("\n")[:lines]

    def test_fex_maps_a_program_on_noexec_storage(self):
        self.assertEqual(self.run_as("FEX"), ["map 0", "protect 0", "read \x7f"])

    def test_the_kernel_still_refuses_everyone_else(self):
        refused = self.run_as("FEX", preload=False)
        self.assertIn(refused[0], ("map 1", "map 13"))
        self.assertEqual(refused[1:], ["protect 13", "read -"])
        self.assertIn(self.run_as("game")[0], ("map 1", "map 13"))

    @unittest.skipUnless(platform.machine() in RETURN_42, "needs a known host instruction set")
    def test_private_image_pages_become_executable_copies(self):
        self.assertEqual(self.run_as("wine", target=self.code, lines=5), ["private 0 42", "hidden 0 42", "mixed 0 42", "past 13 7", "shared 13"])

    @unittest.skipUnless(platform.machine() in RETURN_42, "needs a known host instruction set")
    def test_image_pages_stay_refused_without_the_preload(self):
        self.assertEqual(self.run_as("wine", preload=False, target=self.code, lines=5),
                         ["private 13 -1", "hidden 13 -1", "mixed 13 -1", "past 13 7", "shared 13"])


if __name__ == "__main__":
    unittest.main()
