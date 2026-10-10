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
                        str(PRELOAD / "noexec.c"), str(PRELOAD / "fsync.c"), str(PRELOAD / "robust.c"), "-ldl"], check=True)
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


if __name__ == "__main__":
    unittest.main()
