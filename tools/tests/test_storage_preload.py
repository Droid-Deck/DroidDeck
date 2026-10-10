import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

PRELOAD = Path(__file__).resolve().parents[1] / "linuxfs/preload"

FAKE = r"""
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

static void path_of(int fd, char *out, size_t size) {
  char link[32];
  snprintf(link, sizeof(link), "/proc/self/fd/%d", fd);
  long n = syscall(SYS_readlinkat, AT_FDCWD, link, out, size - 1);
  out[n > 0 ? n : 0] = '\0';
}

int fallocate(int fd, int mode, off_t offset, off_t length) {
  (void)fd; (void)mode; (void)offset; (void)length;
  errno = EOPNOTSUPP;
  return -1;
}

int ftruncate(int fd, off_t length) {
  char path[4096];
  path_of(fd, path, sizeof(path));
  FILE *log = fopen(getenv("FAKE_LOG"), "a");
  if (log) { fprintf(log, "%s %lld\n", strrchr(path, '/') + 1, (long long)length); fclose(log); }
  if (strstr(path, "ntfs")) { errno = EPERM; return -1; }
  return (int)syscall(SYS_ftruncate, fd, length);
}
"""

PROGRAM = r"""
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

int main(int argc, char **argv) {
  for (int i = 1; i + 2 < argc; i += 3) {
    int fd = open(argv[i + 1], O_RDWR | O_CREAT, 0644);
    if (fd < 0) return 2;
    long long length = atoll(argv[i + 2]);
    int result;
    errno = 0;
    if (strcmp(argv[i], "fallocate") == 0) result = fallocate(fd, 0, 0, length);
    else result = ftruncate(fd, length);
    int error = result == 0 ? 0 : errno;
    struct stat st;
    fstat(fd, &st);
    printf("%s %d %s %lld\n", argv[i], result, error ? strerrorname_np(error) : "-", (long long)st.st_size);
    close(fd);
  }
  return 0;
}
"""


@unittest.skipUnless(shutil.which("cc"), "needs a C compiler")
class StoragePreloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dir = Path(tempfile.mkdtemp())
        build = cls.dir / "build"
        build.mkdir()
        (build / "fake.c").write_text(FAKE)
        (build / "program.c").write_text(PROGRAM)
        cls.storage = build / "storage.so"
        cls.fake = build / "fake.so"
        subprocess.run(["cc", "-shared", "-fPIC", "-O2", "-o", str(cls.storage), str(PRELOAD / "storage.c"), "-ldl"], check=True)
        subprocess.run(["cc", "-shared", "-fPIC", "-O2", "-o", str(cls.fake), str(build / "fake.c")], check=True)
        cls.steam = cls.dir / "steamrtarm64/steam"
        cls.steam.parent.mkdir()
        subprocess.run(["cc", "-O2", "-o", str(cls.steam), str(build / "program.c")], check=True)
        cls.other = build / "steamwebhelper"
        shutil.copy(cls.steam, cls.other)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.dir, True)

    def setUp(self):
        self.lib = Path(tempfile.mkdtemp(dir=self.dir))
        self.staging = self.lib / "steamapps/downloading/252410"
        self.staging.mkdir(parents=True)
        self.log = self.lib / "ftruncate.log"

    def run_program(self, *steps, program=None):
        env = dict(os.environ, LD_PRELOAD="%s:%s" % (self.storage, self.fake), FAKE_LOG=str(self.log))
        env.pop("BL_STORAGE_DIAGNOSTICS", None)
        args = [str(program or self.steam)]
        for call, path, length in steps:
            args += [call, str(path), str(length)]
        result = subprocess.run(args, env=env, capture_output=True, text=True, check=True)
        return result.stdout.splitlines()

    def reached(self):
        return self.log.read_text().splitlines() if self.log.exists() else []

    def test_reservation_after_failed_fallocate_is_not_zero_filled(self):
        data = self.staging / "data.pak"
        out = self.run_program(("fallocate", data, 1 << 20), ("ftruncate", data, 1 << 20), ("ftruncate", data, 0))
        self.assertEqual(["fallocate -1 EOPNOTSUPP 0", "ftruncate 0 - 0", "ftruncate 0 - 0"], out)
        self.assertEqual(["data.pak 0"], self.reached())

    def test_reservation_is_real_until_the_device_has_refused_one(self):
        data = self.staging / "data.pak"
        self.assertEqual(["ftruncate 0 - 4096"], self.run_program(("ftruncate", data, 4096)))
        self.assertEqual(["data.pak 4096"], self.reached())

    def test_refused_extension_is_reported_as_done_and_remembered(self):
        first, second = self.staging / "ntfs-one", self.staging / "two"
        out = self.run_program(("ftruncate", first, 8192), ("ftruncate", second, 8192))
        self.assertEqual(["ftruncate 0 - 0", "ftruncate 0 - 0"], out)
        self.assertEqual(["ntfs-one 8192"], self.reached())

    def test_refused_shrink_keeps_its_error(self):
        data = self.staging / "ntfs-data"
        data.write_bytes(b"x" * 100)
        self.assertEqual(["ftruncate -1 EPERM 100"], self.run_program(("ftruncate", data, 10)))

    def test_files_outside_staging_are_untouched(self):
        manifest = self.lib / "steamapps/ntfs-appmanifest.acf"
        out = self.run_program(("fallocate", self.staging / "a", 4096), ("ftruncate", manifest, 4096))
        self.assertEqual(["fallocate -1 EOPNOTSUPP 0", "ftruncate -1 EPERM 0"], out)
        self.assertEqual(["ntfs-appmanifest.acf 4096"], self.reached())

    def test_other_programs_are_untouched(self):
        data = self.staging / "ntfs-data"
        out = self.run_program(("fallocate", data, 4096), ("ftruncate", data, 4096), program=self.other)
        self.assertEqual(["fallocate -1 EOPNOTSUPP 0", "ftruncate -1 EPERM 0"], out)


if __name__ == "__main__":
    unittest.main()
