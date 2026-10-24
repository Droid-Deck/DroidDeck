import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[2] / "app/src/main/cpp/fakeinput_steam.cpp"

PROGRAM = r"""
#define _GNU_SOURCE
#include <assert.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

int main(int argc, char **argv) {
    assert(argc == 4);
    void *symbol = dlsym(RTLD_DEFAULT, argv[1]);
    assert(symbol);
    int (*plain)(const char *, int, ...) = symbol;
    int (*at)(int, const char *, int, ...) = symbol;
    int relative = strstr(argv[1], "at") != NULL;
    int temporary = !strcmp(argv[2], "temporary");
    int directory = !strcmp(argv[2], "directory");
    int dirfd = open(argv[3], O_RDONLY | O_DIRECTORY);
    assert(dirfd >= 0);
    assert(fchdir(dirfd) == 0);
    umask(0);
    const char *path = temporary || directory ? "." : "created";
    int flags = directory ? O_RDONLY | O_DIRECTORY : O_RDWR | (temporary ? O_TMPFILE : O_CREAT | O_EXCL);
    int fd;
    if (directory) fd = relative ? at(dirfd, path, flags) : plain(path, flags);
    else fd = relative ? at(dirfd, path, flags, 0640) : plain(path, flags, 0640);
    if (temporary && fd < 0 && (errno == EOPNOTSUPP || errno == EINVAL)) return 77;
    assert(fd >= 0);
    struct stat st;
    assert(fstat(fd, &st) == 0);
    if (directory) assert(S_ISDIR(st.st_mode));
    else {
        if ((st.st_mode & 0777) != 0640) {
            fprintf(stderr, "%s %s: expected mode 640, got %o\n", argv[1], argv[2], st.st_mode & 0777);
            return 1;
        }
        assert(write(fd, "ok", 2) == 2);
        assert(lseek(fd, 0, SEEK_SET) == 0);
        char bytes[2];
        assert(read(fd, bytes, 2) == 2 && !memcmp(bytes, "ok", 2));
    }
    close(fd);
    close(dirfd);
    return 0;
}
"""


@unittest.skipUnless(sys.platform == "linux" and shutil.which("gcc") and shutil.which("g++"),
                     "needs Linux, gcc and g++")
class FakeinputOpenTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.tmp.cleanup)
        cls.path = Path(cls.tmp.name)
        source = cls.path / "program.c"
        source.write_text(PROGRAM)
        subprocess.run(["gcc", "-o", str(cls.path / "program"), str(source), "-ldl"], check=True)
        subprocess.run(["g++", "-shared", "-fPIC", "-O2", "-Wno-attributes", "-pthread",
                        "-std=c++17", "-static-libstdc++", "-static-libgcc",
                        "-Wl,--exclude-libs,ALL", "-o", str(cls.path / "libfakeinput.so"),
                        str(SOURCE), "-ldl"], check=True)

    def check_open(self, kind):
        for function in ("open", "openat", "open64", "openat64"):
            with self.subTest(function=function), tempfile.TemporaryDirectory() as directory:
                args = [str(self.path / "program"), function, kind, directory]
                baseline = subprocess.run(args, capture_output=True, text=True)
                if baseline.returncode == 77:
                    self.skipTest("filesystem does not support O_TMPFILE")
                self.assertEqual(baseline.returncode, 0, baseline.stderr)
                if kind == "named":
                    (Path(directory) / "created").unlink()
                result = subprocess.run(args, env=dict(os.environ, LD_PRELOAD=str(self.path / "libfakeinput.so")),
                                        capture_output=True, text=True, timeout=10)
                self.assertEqual(result.returncode, 0, result.stderr)

    def test_unnamed_temporary_files_keep_requested_permissions(self):
        self.check_open("temporary")

    def test_named_files_keep_requested_permissions(self):
        self.check_open("named")

    def test_directory_opens_do_not_need_a_mode_argument(self):
        self.check_open("directory")


if __name__ == "__main__":
    unittest.main()
