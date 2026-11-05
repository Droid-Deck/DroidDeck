import errno
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

PRELOAD = Path(__file__).resolve().parents[1] / "linuxfs/preload"

SHIM = r"""
#define _GNU_SOURCE
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

int close(int fd) {
  char link[64], path[PATH_MAX];
  snprintf(link, sizeof link, "/proc/self/fd/%d", fd);
  ssize_t n = readlink(link, path, sizeof path - 1);
  path[n > 0 ? n : 0] = 0;
  long r = syscall(SYS_close, fd);
  if (r == 0 && n > 4 && !strcmp(path + n - 4, ".eio")) {
    errno = EIO;
    return -1;
  }
  return (int)r;
}
"""

PROGRAM = r"""
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <unistd.h>

static void check(const char *name, const char *path, int flags) {
  int fd = open(path, flags);
  if (fd < 0) { printf("%s open-failed\n", name); return; }
  char b[4];
  if (read(fd, b, sizeof b) < 0) {}
  errno = 0;
  int r = close(fd);
  int error = r ? errno : 0;
  int still_open = fcntl(fd, F_GETFD) >= 0;
  printf("%s %d %d %d\n", name, r, error, still_open);
}

int main(int argc, char **argv) {
  check("readonly", argv[1], O_RDONLY);
  check("readwrite", argv[1], O_RDWR);
  check("plain", argv[2], O_RDONLY);
  errno = 0;
  int r = close(-1);
  printf("badfd %d %d\n", r, errno == EBADF);
  return 0;
}
"""


@unittest.skipUnless(shutil.which("cc"), "needs a C compiler")
class CloseEioPreloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.dir = Path(cls.temp.name)
        (cls.dir / "shim.c").write_text(SHIM)
        (cls.dir / "program.c").write_text(PROGRAM)
        cls.preload = cls.dir / "libblsession.so"
        cls.shim = cls.dir / "libshim.so"
        cls.program = cls.dir / "program"
        subprocess.run(["cc", "-shared", "-fPIC", "-O2", "-Wall", "-pthread", "-DBL_CLOCK_ANY_ARCH",
                        '-DCP_CTL="%s"' % (cls.dir / "clock"), "-o", str(cls.preload),
                        *map(str, sorted(PRELOAD.glob("*.c"))), "-ldl"], check=True)
        subprocess.run(["cc", "-shared", "-fPIC", "-O2", "-o", str(cls.shim), str(cls.dir / "shim.c")], check=True)
        subprocess.run(["cc", "-O2", "-o", str(cls.program), str(cls.dir / "program.c")], check=True)
        (cls.dir / "data.eio").write_bytes(b"data")
        (cls.dir / "plain").write_bytes(b"data")

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def run_program(self, preloads):
        env = dict(os.environ, LD_PRELOAD=":".join(map(str, preloads)))
        result = subprocess.run([str(self.program), str(self.dir / "data.eio"), str(self.dir / "plain")],
                                env=env, capture_output=True, text=True, check=True, timeout=30)
        return {line.split()[0]: line.split()[1:] for line in result.stdout.splitlines()}

    def test_a_flush_error_on_a_read_only_file_is_not_a_close_failure(self):
        out = self.run_program([self.preload, self.shim])
        self.assertEqual(["0", "0", "0"], out["readonly"])

    def test_a_flush_error_after_writing_access_is_still_reported(self):
        out = self.run_program([self.preload, self.shim])
        self.assertEqual(["-1", str(errno.EIO), "0"], out["readwrite"])

    def test_other_closes_are_unchanged(self):
        out = self.run_program([self.preload, self.shim])
        self.assertEqual(["0", "0", "0"], out["plain"])
        self.assertEqual(["-1", "1"], out["badfd"])

    def test_without_the_session_library_the_error_reaches_the_program(self):
        out = self.run_program([self.shim])
        self.assertEqual("-1", out["readonly"][0])


if __name__ == "__main__":
    unittest.main()
