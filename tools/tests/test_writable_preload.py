import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import unittest

PRELOAD = Path(__file__).resolve().parents[1] / "linuxfs/preload"

PROGRAM = r"""
#define _GNU_SOURCE
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
  for (int i = 1; i + 1 < argc; i += 2) {
    const char *op = argv[i], *path = argv[i + 1];
    int ok;
    if (strcmp(op, "access") == 0) ok = access(path, W_OK) == 0;
    else if (strcmp(op, "access-rw") == 0) ok = access(path, R_OK | W_OK) == 0;
    else if (strcmp(op, "faccessat") == 0) {
      int dir = open(".", O_RDONLY | O_DIRECTORY);
      ok = faccessat(dir, path, W_OK, 0) == 0;
      close(dir);
    } else if (strcmp(op, "write") == 0) {
      int fd = open(path, O_WRONLY | O_TRUNC);
      ok = fd >= 0 && write(fd, "saved", 5) == 5;
      if (fd >= 0) close(fd);
    } else if (strcmp(op, "create") == 0) {
      int fd = open(path, O_WRONLY | O_CREAT | O_EXCL, 0600);
      ok = fd >= 0;
      if (fd >= 0) close(fd);
    } else ok = 0;
    printf("%s %d\n", op, ok);
  }
  return 0;
}
"""


@unittest.skipUnless(shutil.which("cc") and os.geteuid() != 0, "needs a C compiler and a non-root user")
class WritablePreloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dir = Path(tempfile.mkdtemp())
        build = cls.dir / "build"
        build.mkdir()
        (build / "program.c").write_text(PROGRAM)
        cls.preload = build / "libblsession.so"
        subprocess.run(["cc", "-shared", "-fPIC", "-O2", "-Wall", "-pthread", "-o", str(cls.preload),
                        *map(str, sorted(PRELOAD.glob("*.c"))), "-ldl"], check=True)
        cls.editor = build / "kate"
        subprocess.run(["cc", "-O2", "-o", str(cls.editor), str(build / "program.c")], check=True)
        cls.wine = build / "wine64-preloader"
        shutil.copy(cls.editor, cls.wine)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.dir, True)

    def setUp(self):
        self.work = Path(tempfile.mkdtemp(dir=self.dir))
        self.addCleanup(self.unlock)
        self.ini = self.work / "My Games/FalloutNV/FALLOUT.INI"
        self.ini.parent.mkdir(parents=True)
        self.ini.write_text("bUseThreadedAI=0\n")
        self.ini.chmod(0o400)
        self.locked = self.work / "Locked"
        self.locked.mkdir()
        self.locked.chmod(0o500)

    def unlock(self):
        for path in (self.ini, self.locked):
            path.chmod(0o700)

    def run_as(self, program, *args, preload=True):
        env = dict(os.environ)
        env.pop("LD_PRELOAD", None)
        if preload:
            env["LD_PRELOAD"] = str(self.preload)
        out = subprocess.run([str(program), *map(str, args)], env=env, cwd=self.ini.parent,
                             capture_output=True, text=True, check=True, timeout=60).stdout
        return dict(line.split() for line in out.splitlines())

    def test_without_the_preload_a_read_only_file_cannot_be_saved(self):
        out = self.run_as(self.editor, "access", self.ini, "write", self.ini, preload=False)
        self.assertEqual(out, {"access": "0", "write": "0"})

    def test_a_desktop_app_can_save_a_read_only_file_like_root(self):
        out = self.run_as(self.editor, "access", self.ini, "access-rw", self.ini, "faccessat", self.ini.name,
                          "write", self.ini, "create", self.locked / "new.tmp")
        self.assertEqual(out, {"access": "1", "access-rw": "1", "faccessat": "1", "write": "1", "create": "1"})
        self.assertEqual(self.ini.read_text(), "saved")

    def test_checking_access_alone_leaves_the_mode_alone(self):
        self.run_as(self.editor, "access", self.ini)
        self.assertEqual(stat.S_IMODE(self.ini.stat().st_mode), 0o400)

    def test_wine_keeps_windows_read_only_files_read_only(self):
        out = self.run_as(self.wine, "access", self.ini, "write", self.ini, "create", self.locked / "new.tmp")
        self.assertEqual(out, {"access": "0", "write": "0", "create": "0"})
        self.assertEqual(self.ini.read_text(), "bUseThreadedAI=0\n")


if __name__ == "__main__":
    unittest.main()
