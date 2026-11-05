import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

PRELOAD = Path(__file__).resolve().parents[1] / "linuxfs/preload"

FAKE = r"""
#define _GNU_SOURCE
#include <fcntl.h>
#include <stdio.h>
#include <errno.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/syscall.h>
#include <unistd.h>

uid_t getuid(void) {
  return (uid_t)syscall(SYS_getuid) + 1;
}

int fstatfs(int fd, struct statfs *out) {
  char link[32], path[4096];
  snprintf(link, sizeof(link), "/proc/self/fd/%d", fd);
  long n = syscall(SYS_readlinkat, AT_FDCWD, link, path, sizeof(path) - 1);
  path[n > 0 ? n : 0] = '\0';
  int result = (int)syscall(SYS_fstatfs, fd, out);
  if (result == 0 && strstr(path, "/fuse/")) out->f_type = 0x65735546;
  if (result == 0 && strstr(path, "/sdcardfs/")) out->f_type = 0x5dca2df5;
  if (result == 0 && strstr(path, "/ext4/")) out->f_type = 0xef53;
  if (result == 0 && strstr(path, "/fat/")) out->f_type = 0x4d44;
  if (result == 0 && strstr(path, "/exfat/")) out->f_type = 0x2011bab0;
  if (result == 0 && strstr(path, "/ntfs/")) out->f_type = 0x5346544e;
  if (strstr(path, "/broken/")) {
    errno = EIO;
    return -1;
  }
  return result;
}

int fchmod(int fd, mode_t mode) {
  (void)fd;
  (void)mode;
  errno = EPERM;
  return -1;
}
"""

PROGRAM = r"""
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

int main(int argc, char **argv) {
  uid_t real = (uid_t)syscall(SYS_getuid);
  for (int i = 1; i < argc; i++) {
    int fd = open(argv[i], O_RDONLY);
    struct stat st;
    errno = 1234;
    int result = fstat(fd, &st);
    printf("%s %d\n", st.st_uid == real ? "real" : st.st_uid == real + 1 ? "user" : "other", result == 0 && errno == 1234);
    errno = 0;
    printf("%s\n", fchmod(fd, 0755) == 0 ? "chmod-ok" : errno == EPERM ? "chmod-denied" : "chmod-other");
    close(fd);
  }
  return 0;
}
"""


@unittest.skipUnless(shutil.which("cc"), "needs a C compiler")
class StorageOwnerPreloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.dir = Path(cls.temp.name)
        build = cls.dir / "build"
        build.mkdir()
        (build / "fake.c").write_text(FAKE)
        (build / "program.c").write_text(PROGRAM)
        cls.preload = build / "libblsession.so"
        cls.fake = build / "fake.so"
        subprocess.run(["cc", "-shared", "-fPIC", "-O2", "-Wall", "-pthread", "-o", str(cls.preload),
                        *map(str, sorted(PRELOAD.glob("*.c"))), "-ldl"], check=True)
        subprocess.run(["cc", "-shared", "-fPIC", "-O2", "-o", str(cls.fake), str(build / "fake.c")], check=True)
        cls.wineserver = build / "wineserver"
        subprocess.run(["cc", "-O2", "-o", str(cls.wineserver), str(build / "program.c")], check=True)
        cls.other = build / "wine64-preloader"
        shutil.copy(cls.wineserver, cls.other)
        for kind in ("fuse", "sdcardfs", "ext4", "fat", "exfat", "ntfs", "broken"):
            (cls.dir / kind).mkdir()
            (cls.dir / kind / "file").write_text("x")

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def owners(self, program, *kinds):
        env = dict(os.environ, LD_PRELOAD="%s:%s" % (self.preload, self.fake))
        env.pop("BL_STORAGE_DIAGNOSTICS", None)
        paths = [str(self.dir / kind / "file") for kind in kinds]
        result = subprocess.run([str(program)] + paths, env=env, capture_output=True, text=True, check=True)
        return result.stdout.split()

    def test_wineserver_owns_what_is_on_android_storage(self):
        self.assertEqual(["user", "1", "chmod-ok"] * 2, self.owners(self.wineserver, "fuse", "sdcardfs"))

    def test_wineserver_owns_what_is_on_a_native_card(self):
        self.assertEqual(["user", "1", "chmod-ok"] * 2, self.owners(self.wineserver, "fat", "exfat"))

    def test_other_filesystems_keep_their_owner(self):
        self.assertEqual(["real", "1", "chmod-denied"] * 2, self.owners(self.wineserver, "ext4", "ntfs"))

    def test_chmod_keeps_its_error_when_the_volume_is_unknown(self):
        self.assertEqual(["real", "1", "chmod-denied"], self.owners(self.wineserver, "broken"))

    def test_other_programs_see_the_real_owner(self):
        self.assertEqual(["real", "1", "chmod-denied"] * 2, self.owners(self.other, "fuse", "sdcardfs"))


if __name__ == "__main__":
    unittest.main()
