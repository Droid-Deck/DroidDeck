import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[2] / "app/src/main/cpp/fakeinput_steam.cpp"

PROGRAM = r"""
#include <assert.h>
#include <errno.h>
#include <stdarg.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <unistd.h>

#ifdef BACKEND
int ioctl(int fd, unsigned long request, ...) {
  va_list ap;
  va_start(ap, request);
  int *value = va_arg(ap, int *);
  va_end(ap);
  assert(fd == -1 && request == 0x12345678 && value);
  *value = 0x51;
  errno = EACCES;
  return -1;
}
#else
int main(int argc, char **argv) {
  if (argc > 1) {
    int value = 0;
    assert(ioctl(-1, 0x12345678, &value) == -1);
    assert(value == 0x51 && errno == EACCES);
  } else {
    int fds[2];
    assert(pipe(fds) == 0);
    struct termios term;
    assert(ioctl(fds[0], TCGETS, &term) == -1 && errno == ENOTTY);
    close(fds[0]);
    close(fds[1]);
  }
  return 0;
}
#endif
"""


@unittest.skipUnless(sys.platform == "linux" and shutil.which("gcc") and shutil.which("g++"),
                     "needs Linux, gcc and g++")
class FakeinputIoctlTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.tmp.cleanup)
        cls.path = Path(cls.tmp.name)
        source = cls.path / "program.c"
        source.write_text(PROGRAM)
        subprocess.run(["gcc", "-o", str(cls.path / "program"), str(source)], check=True)
        subprocess.run(["gcc", "-shared", "-fPIC", "-DBACKEND", "-o",
                        str(cls.path / "backend.so"), str(source)], check=True)
        subprocess.run(["g++", "-shared", "-fPIC", "-O2", "-Wno-attributes", "-pthread",
                        "-std=c++17", "-static-libstdc++", "-static-libgcc",
                        "-Wl,--exclude-libs,ALL", "-o", str(cls.path / "libfakeinput.so"),
                        str(SOURCE), "-ldl"], check=True)

    def run_program(self, backend=False):
        preload = str(self.path / "libfakeinput.so")
        args = [str(self.path / "program")]
        if backend:
            preload += ":" + str(self.path / "backend.so")
            args.append("backend")
        subprocess.run(args, env=dict(os.environ, LD_PRELOAD=preload), check=True, timeout=10)

    def test_later_interposer_receives_arguments_and_returns_errno(self):
        self.run_program(backend=True)

    def test_libc_passthrough_retains_kernel_error(self):
        self.run_program()


if __name__ == "__main__":
    unittest.main()
