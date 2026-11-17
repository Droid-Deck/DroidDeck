import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

PRELOAD = Path(__file__).resolve().parents[1] / "linuxfs/preload"
GIB = 1 << 30

PROGRAM = r"""
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static void report(const char *call, int fd, int result) {
  struct stat st;
  if (fstat(fd, &st) != 0) { printf("%s fstat-failed\n", call); return; }
  printf("%s %d %lld\n", call, result, (long long)st.st_size);
}

int main(int argc, char **argv) {
  const char *dir = argv[1];
  long long far = atoll(argv[2]);
  char path[4096];
  snprintf(path, sizeof(path), "%s/machine-id", dir);
  int fd = open(path, O_RDWR | O_CREAT | O_TRUNC, 0644);
  if (fd < 0 || write(fd, "0123456789abcdef0123456789abcdef\n", 33) != 33) return 2;
  report("fstat", fd, 0);
  report("ftruncate", fd, ftruncate(fd, far));
  report("pwrite", fd, pwrite(fd, "x", 1, far) == 1 ? 0 : errno);
  close(fd);
  snprintf(path, sizeof(path), "%s/reserved", dir);
  fd = open(path, O_RDWR | O_CREAT | O_TRUNC, 0644);
  int result = posix_fallocate(fd, 0, 4096);
  report("posix_fallocate", fd, result == EOPNOTSUPP ? 0 : result);
  result = fallocate(fd, 0, 0, 8192);
  report("fallocate", fd, result == 0 || errno == EOPNOTSUPP ? 0 : errno);
  int other = open(path, O_RDWR);
  struct flock lock = {.l_type = F_WRLCK, .l_whence = SEEK_SET, .l_start = far, .l_len = 1};
  result = fcntl(fd, F_OFD_SETLK, &lock);
  struct flock query = {.l_type = F_WRLCK, .l_whence = SEEK_SET, .l_start = far, .l_len = 1};
  int queried = fcntl(other, F_OFD_GETLK, &query);
  printf("lock %d %d %d %lld\n", result, queried, query.l_type == F_WRLCK, (long long)query.l_start);
  close(other);
  close(fd);
  return 0;
}
"""

LOCKS = r"""
#define _GNU_SOURCE
#include <fcntl.h>
#include <stdio.h>
#include <unistd.h>

int main(int argc, char **argv) {
  int fd = open(argv[1], O_RDWR | O_CREAT, 0644);
  struct flock lock = {.l_type = F_WRLCK, .l_whence = SEEK_SET, .l_start = 0, .l_len = 1};
  int set = fcntl(fd, F_SETLK, &lock);
  struct flock query = {.l_type = F_WRLCK, .l_whence = SEEK_SET, .l_start = 0, .l_len = 1};
  int get = fcntl(fd, F_GETLK, &query);
  printf("%d %d %s\n", set, get, query.l_type == F_UNLCK ? "unlocked" : "locked");
  return 0;
}
"""

NO_LOCKS = r"""
#include <errno.h>
int fcntl(int fd, int cmd, void *arg) { (void)fd; (void)cmd; (void)arg; errno = ENOSYS; return -1; }
int fcntl64(int fd, int cmd, void *arg) { (void)fd; (void)cmd; (void)arg; errno = ENOSYS; return -1; }
"""


def compiles(bits, workdir):
    source = Path(workdir) / ("probe%s.c" % bits)
    source.write_text("int main(void) { return 0; }\n")
    out = Path(workdir) / ("probe%s" % bits)
    if subprocess.run(["cc", bits, "-o", str(out), str(source)], capture_output=True).returncode != 0:
        return False
    return subprocess.run([str(out)], capture_output=True).returncode == 0


@unittest.skipUnless(shutil.which("cc"), "needs a C compiler")
class LargeFilePreloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dir = Path(tempfile.mkdtemp())
        (cls.dir / "program.c").write_text(PROGRAM)
        (cls.dir / "locks.c").write_text(LOCKS)
        (cls.dir / "nolocks.c").write_text(NO_LOCKS)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.dir, True)

    def build(self, bits):
        if not compiles(bits, self.dir):
            self.skipTest("no %s toolchain" % bits)
        out = self.dir / bits.lstrip("-")
        out.mkdir(exist_ok=True)
        lib, program = out / "libblsession.so", out / "program"
        if not lib.exists():
            sources = sorted(str(p) for p in PRELOAD.glob("*.c"))
            subprocess.run(["cc", bits, "-shared", "-fPIC", "-O2", "-pthread", "-o", str(lib)] + sources + ["-ldl"], check=True)
            subprocess.run(["cc", bits, "-O2", "-D_FILE_OFFSET_BITS=64", "-o", str(program), str(self.dir / "program.c")], check=True)
            subprocess.run(["cc", bits, "-O2", "-D_FILE_OFFSET_BITS=64", "-o", str(out / "locks"), str(self.dir / "locks.c")], check=True)
            subprocess.run(["cc", bits, "-shared", "-fPIC", "-O2", "-o", str(out / "nolocks.so"), str(self.dir / "nolocks.c")], check=True)
        return lib, program

    def run_program(self, bits, preload):
        lib, program = self.build(bits)
        work = Path(tempfile.mkdtemp(dir=self.dir))
        env = {k: v for k, v in os.environ.items() if not k.startswith("BL_")}
        env.pop("LD_PRELOAD", None)
        if preload:
            env["LD_PRELOAD"] = str(lib)
        result = subprocess.run([str(program), str(work), str(5 * GIB)], env=env, capture_output=True, text=True, timeout=60, check=True)
        return result.stdout.splitlines()

    def expected(self):
        return [
            "fstat 0 33",
            "ftruncate 0 %d" % (5 * GIB),
            "pwrite 0 %d" % (5 * GIB + 1),
            "posix_fallocate 0 4096",
            "fallocate 0 8192",
            "lock 0 0 1 %d" % (5 * GIB),
        ]

    def test_32_bit_large_file_calls_see_the_real_size_and_offsets(self):
        self.assertEqual(self.expected(), self.run_program("-m32", preload=True))

    def test_32_bit_programs_behave_as_without_the_preload(self):
        self.assertEqual(self.run_program("-m32", preload=False), self.run_program("-m32", preload=True))

    def locks_without_filesystem_support(self, bits):
        lib, program = self.build(bits)
        env = {k: v for k, v in os.environ.items() if not k.startswith("BL_")}
        env["LD_PRELOAD"] = "%s:%s" % (lib, program.parent / "nolocks.so")
        work = Path(tempfile.mkdtemp(dir=self.dir))
        result = subprocess.run([str(program.parent / "locks"), str(work / "file")], env=env, capture_output=True, text=True, timeout=60, check=True)
        return result.stdout.strip()

    def test_locks_on_filesystems_without_lock_support_still_succeed(self):
        self.assertEqual("0 0 unlocked", self.locks_without_filesystem_support("-m64"))
        self.assertEqual("0 0 unlocked", self.locks_without_filesystem_support("-m32"))

    def test_64_bit_programs_are_unchanged(self):
        self.assertEqual(self.expected(), self.run_program("-m64", preload=True))
        self.assertEqual(self.run_program("-m64", preload=False), self.run_program("-m64", preload=True))


if __name__ == "__main__":
    unittest.main()
