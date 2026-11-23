import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tempfile
import unittest

PRELOAD = Path(__file__).resolve().parents[1] / "linuxfs/preload"

FAKE = r"""
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <unistd.h>

static void path_of(int fd, char *path, size_t size) {
  char link[32];
  snprintf(link, sizeof(link), "/proc/self/fd/%d", fd);
  long n = syscall(SYS_readlinkat, AT_FDCWD, link, path, size - 1);
  path[n > 0 ? n : 0] = '\0';
}

static int answer(int fd) {
  char path[4096];
  path_of(fd, path, sizeof(path));
  if (strstr(path, "/card/")) return ENOTSUP;
  if (strstr(path, "/denied/")) return EACCES;
  return ENODATA;
}

ssize_t fgetxattr(int fd, const char *name, void *value, size_t size) {
  char path[4096], side[4200];
  (void)name;
  path_of(fd, path, sizeof(path));
  if (strstr(path, "/native/")) {
    snprintf(side, sizeof(side), "%s.xattr", path);
    int file = (int)syscall(SYS_openat, AT_FDCWD, side, O_RDONLY | O_CLOEXEC, 0);
    if (file < 0) {
      errno = ENODATA;
      return -1;
    }
    long n = syscall(SYS_read, file, value, size);
    syscall(SYS_close, file);
    return n;
  }
  errno = answer(fd);
  return -1;
}

int fsetxattr(int fd, const char *name, const void *value, size_t size, int flags) {
  (void)name; (void)value; (void)size; (void)flags;
  int error = answer(fd);
  if (error == ENODATA) return 0;
  errno = error;
  return -1;
}

int fremovexattr(int fd, const char *name) {
  (void)name;
  errno = answer(fd);
  return -1;
}
"""

PROGRAM = r"""
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/xattr.h>
#include <unistd.h>

static int hex_value(char c) {
  return c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : -1;
}

static const char *error(void) {
  return errno == ENOTSUP ? "unsupported" : errno == ENODATA ? "none" : errno == ERANGE ? "range" :
         errno == EACCES ? "denied" : "other";
}

int main(int argc, char **argv) {
  for (int i = 1; i < argc; i++) {
    const char *op = argv[i];
    if (!strcmp(op, "unlink") || !strcmp(op, "rmdir")) {
      const char *path = argv[++i];
      printf("%s\n", (op[0] == 'u' ? unlink(path) : rmdir(path)) == 0 ? "ok" : "failed");
      continue;
    }
    if (!strcmp(op, "remove-path")) {
      printf("%s\n", remove(argv[++i]) == 0 ? "ok" : "failed");
      continue;
    }
    if (!strcmp(op, "machine-id")) {
      FILE *f = fopen("/etc/machine-id", "r");
      printf("%s\n", f ? "opened" : "missing");
      if (f) fclose(f);
      continue;
    }
    if (!strcmp(op, "rename")) {
      const char *from = argv[++i], *to = argv[++i];
      printf("%s\n", rename(from, to) == 0 ? "ok" : "failed");
      continue;
    }
    int fd = open(argv[++i], O_RDONLY);
    char value[64];
    if (!strcmp(op, "set")) {
      const char *text = argv[++i];
      errno = 1234;
      int result = fsetxattr(fd, "user.wine.sd", text, strlen(text), 0);
      printf("%s\n", result == 0 ? errno == 1234 ? "ok" : "errno" : error());
    } else if (!strcmp(op, "sethex")) {
      const char *text = argv[++i];
      unsigned char bytes[4096];
      size_t n = 0;
      for (; text[2 * n] && text[2 * n + 1] && n < sizeof(bytes); n++)
        bytes[n] = (unsigned char)(hex_value(text[2 * n]) * 16 + hex_value(text[2 * n + 1]));
      int result = fsetxattr(fd, "user.wine.sd", bytes, n, 0);
      printf("%s\n", result == 0 ? "ok" : error());
    } else if (!strcmp(op, "gethex")) {
      unsigned char bytes[4096];
      ssize_t n = fgetxattr(fd, "user.wine.sd", bytes, sizeof(bytes));
      if (n < 0) printf("%s\n", error());
      else {
        printf("=");
        for (ssize_t k = 0; k < n; k++) printf("%02x", bytes[k]);
        printf("\n");
      }
    } else if (!strcmp(op, "get")) {
      ssize_t n = fgetxattr(fd, "user.wine.sd", value, sizeof(value));
      if (n < 0) printf("%s\n", error());
      else printf("=%.*s\n", (int)n, value);
    } else if (!strcmp(op, "size")) {
      ssize_t n = fgetxattr(fd, "user.wine.sd", NULL, 0);
      if (n < 0) printf("%s\n", error());
      else printf("%d\n", (int)n);
    } else if (!strcmp(op, "short")) {
      ssize_t n = fgetxattr(fd, "user.wine.sd", value, 2);
      printf("%s\n", n < 0 ? error() : "read");
    } else if (!strcmp(op, "remove")) {
      errno = 1234;
      int result = fremovexattr(fd, "user.wine.sd");
      printf("%s\n", result == 0 ? errno == 1234 ? "ok" : "errno" : error());
    } else if (!strcmp(op, "other")) {
      ssize_t n = fgetxattr(fd, "user.other", value, sizeof(value));
      printf("%s\n", n < 0 ? error() : "read");
    }
    close(fd);
  }
  return 0;
}
"""


def machine_id_head():
    try:
        with open("/etc/machine-id", "rb") as f:
            return f.read(16)
    except OSError:
        return b""


def sid(*subs, authority=5):
    return bytes([1, len(subs), 0, 0, 0, 0, 0, authority]) + b"".join(struct.pack("=I", s) for s in subs)


def ace(kind, who, mask=0x1F01FF):
    return struct.pack("=BBHI", kind, 3, 8 + len(who), mask) + who


def acl(*aces):
    body = b"".join(aces)
    return struct.pack("=BBHHH", 2, 0, 8 + len(body), len(aces), 0) + body


def descriptor(owner, group, dacl, sacl=b""):
    control = 0x4 | (0x10 if sacl else 0)
    return (b"\x01\x00" + struct.pack("=IIIII", control, len(owner), len(group), len(sacl), len(dacl)) +
            owner + group + sacl + dacl)


DEFAULT_USER = sid(21, 0, 0, 0, 1000)
OLD_USER = sid(21, 0x12345678, 0x9ABCDEF0, os.getuid(), 1000)
USERS = sid(21, 0, 0, 0, 513)
ADMINS = sid(32, 544)


@unittest.skipUnless(shutil.which("cc"), "needs a C compiler")
class DescriptorPreloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.build_dir = tempfile.TemporaryDirectory()
        build = Path(cls.build_dir.name)
        (build / "fake.c").write_text(FAKE)
        (build / "program.c").write_text(PROGRAM)
        cls.preload = build / "libddsession.so"
        cls.fake = build / "fake.so"
        subprocess.run(["cc", "-shared", "-fPIC", "-O2", "-Wall", "-pthread", "-o", str(cls.preload),
                        *map(str, sorted(PRELOAD.glob("*.c"))), "-ldl"], check=True)
        subprocess.run(["cc", "-shared", "-fPIC", "-O2", "-o", str(cls.fake), str(build / "fake.c")], check=True)
        cls.wineserver = build / "wineserver"
        subprocess.run(["cc", "-O2", "-o", str(cls.wineserver), str(build / "program.c")], check=True)
        cls.other = build / "wine64-preloader"
        shutil.copy(cls.wineserver, cls.other)

    @classmethod
    def tearDownClass(cls):
        cls.build_dir.cleanup()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.dir = Path(os.path.realpath(self.temp.name))
        self.prefix = self.dir / "prefix"
        self.prefix.mkdir()
        self.store = self.prefix / ".droiddeck-descriptors"
        for kind in ("card", "disk", "denied", "native"):
            (self.dir / kind / "game").mkdir(parents=True)
            (self.dir / kind / "game" / "file").write_text("x")

    def tearDown(self):
        self.temp.cleanup()

    def path(self, *parts):
        return str(self.dir.joinpath(*parts))

    def run_as(self, program, *args, prefix=True):
        env = dict(os.environ, LD_PRELOAD="%s:%s" % (self.preload, self.fake))
        env.pop("WINEPREFIX", None)
        if prefix:
            env["WINEPREFIX"] = str(self.prefix)
        result = subprocess.run([str(program), *args], env=env, capture_output=True, text=True, check=True)
        return result.stdout.split()

    def server(self, *args, **kwargs):
        return self.run_as(self.wineserver, *args, **kwargs)

    def test_a_descriptor_is_kept_where_the_volume_cannot_store_it(self):
        file, folder = self.path("card", "game", "file"), self.path("card", "game")
        self.assertEqual(["none", "ok", "ok"], self.server("get", file, "set", file, "one", "set", folder, "two"))
        self.assertEqual(["=one", "3", "=two"], self.server("get", file, "size", file, "get", folder))

    def test_a_descriptor_is_replaced_and_removed(self):
        file = self.path("card", "game", "file")
        self.assertEqual(["ok", "ok", "=longer"], self.server("set", file, "one", "set", file, "longer", "get", file))
        self.assertEqual(["range", "ok", "none", "none"], self.server("short", file, "remove", file, "get", file, "remove", file))

    def test_a_volume_that_stores_descriptors_is_left_alone(self):
        file = self.path("disk", "game", "file")
        self.assertEqual(["ok", "none", "none"], self.server("set", file, "one", "get", file, "remove", file))
        self.assertFalse(self.store.exists())

    def test_other_failures_keep_their_error(self):
        file = self.path("denied", "game", "file")
        self.assertEqual(["denied", "denied", "denied"], self.server("set", file, "one", "get", file, "remove", file))
        self.assertFalse(self.store.exists())

    def test_other_programs_and_other_attributes_are_unchanged(self):
        file = self.path("card", "game", "file")
        self.assertEqual(["unsupported", "unsupported", "unsupported"],
                         self.run_as(self.other, "set", file, "one", "get", file, "remove", file))
        self.assertEqual(["ok", "unsupported"], self.server("set", file, "one", "other", file))
        self.assertEqual(["unsupported"], self.run_as(self.other, "get", file))

    def test_nothing_is_kept_without_a_prefix(self):
        file = self.path("card", "game", "file")
        self.assertEqual(["unsupported", "unsupported", "unsupported"],
                         self.server("set", file, "one", "get", file, "remove", file, prefix=False))

    def test_a_deleted_file_loses_its_descriptor(self):
        file, folder = self.path("card", "game", "file"), self.path("card", "game")
        self.assertEqual(["ok", "ok", "ok"], self.server("set", file, "one", "set", folder, "two", "unlink", file))
        Path(file).write_text("x")
        self.assertEqual(["none", "ok", "ok"], self.server("get", file, "unlink", file, "rmdir", folder))
        Path(folder).mkdir()
        self.assertEqual(["none"], self.server("get", folder))

    def test_a_removed_file_or_folder_loses_its_descriptor(self):
        file, folder = self.path("card", "game", "file"), self.path("card", "game")
        self.assertEqual(["ok", "ok", "ok", "ok"],
                         self.server("set", file, "one", "set", folder, "two", "remove-path", file, "remove-path", folder))
        Path(folder).mkdir()
        Path(file).write_text("x")
        self.assertEqual(["none", "none"], self.server("get", folder, "get", file))

    def test_a_file_deleted_through_a_link_loses_its_descriptor(self):
        file = self.path("card", "game", "file")
        os.symlink(self.path("card"), self.path("drive"))
        self.assertEqual(["ok", "ok"], self.server("set", file, "one", "unlink", self.path("drive", "game", "file")))
        Path(file).write_text("x")
        self.assertEqual(["none"], self.server("get", file))

    def test_a_descriptor_follows_a_rename(self):
        file, moved = self.path("card", "game", "file"), self.path("card", "game", "moved")
        self.assertEqual(["ok", "ok", "=one"], self.server("set", file, "one", "rename", file, moved, "get", moved))
        Path(file).write_text("x")
        self.assertEqual(["none", "ok", "none"], self.server("get", file, "rename", file, moved, "get", moved))

    def test_descriptors_follow_a_renamed_folder(self):
        file, folder, moved = self.path("card", "game", "file"), self.path("card", "game"), self.path("card", "moved")
        self.assertEqual(["ok", "ok", "ok"], self.server("set", file, "one", "set", folder, "two", "rename", folder, moved))
        self.assertEqual(["=two", "=one"], self.server("get", moved, "get", self.path("card", "moved", "file")))
        Path(folder).mkdir()
        (Path(folder) / "file").write_text("x")
        self.assertEqual(["none", "none"], self.server("get", folder, "get", file))

    def test_deletes_outside_the_store_cost_nothing_before_it_exists(self):
        file = self.path("disk", "game", "file")
        self.assertEqual(["ok"], self.server("unlink", file))
        self.assertFalse(self.store.exists())

    def test_a_name_too_long_to_keep_reports_no_support(self):
        long_file = self.path("card", "game", "n" * 254)
        Path(long_file).write_text("x")
        self.assertEqual(["unsupported", "unsupported"], self.server("set", long_file, "one", "get", long_file))

    def keep_natively(self, file, value):
        Path(file + ".xattr").write_bytes(value)

    def assert_read_as(self, expected, *args, program=None):
        out = self.run_as(program or self.wineserver, *args)
        self.assertEqual("=" + expected.hex(), out[-1])

    def test_a_descriptor_written_for_an_earlier_user_id_is_read_for_the_current_user(self):
        written = descriptor(OLD_USER, USERS, acl(ace(0, OLD_USER), ace(0, ADMINS), ace(1, OLD_USER, 0x40000)),
                             acl(ace(2, OLD_USER)))
        current = written.replace(OLD_USER, DEFAULT_USER)
        stored = self.path("card", "game", "file")
        self.assertEqual(["ok"], self.server("sethex", stored, written.hex()))
        self.assert_read_as(current, "gethex", stored)
        native = self.path("native", "game", "file")
        self.keep_natively(native, written)
        self.assert_read_as(current, "gethex", native)
        self.assertEqual(written, Path(native + ".xattr").read_bytes())

    @unittest.skipUnless(re.fullmatch(rb"[0-9a-f]{16}", machine_id_head()), "needs a machine ID")
    def test_the_current_user_follows_the_machine_id_the_server_reads(self):
        value = int(machine_id_head(), 16)
        current = sid(21, value >> 32, value & 0xFFFFFFFF, os.getuid(), 1000)
        written = descriptor(DEFAULT_USER, USERS, acl(ace(0, DEFAULT_USER)))
        native = self.path("native", "game", "file")
        self.keep_natively(native, written)
        out = self.server("machine-id", "gethex", native)
        self.assertEqual(["opened", "=" + written.replace(DEFAULT_USER, current).hex()], out)

    def test_other_accounts_groups_and_unknown_entries_are_kept(self):
        others = [sid(21, 1, 2, os.getuid() + 1, 1000), sid(21, 1, 2, os.getuid(), 500),
                  sid(21, 1, 2, os.getuid(), 1000, 7), sid(0, authority=1), sid(18), USERS, ADMINS]
        written = descriptor(others[0], USERS, acl(*(ace(0, other) for other in others), ace(5, OLD_USER),
                                                     ace(0x11, OLD_USER)))
        native = self.path("native", "game", "file")
        self.keep_natively(native, written)
        self.assert_read_as(written, "gethex", native)

    def test_a_descriptor_in_the_current_user_and_other_programs_are_unchanged(self):
        native = self.path("native", "game", "file")
        current = descriptor(DEFAULT_USER, USERS, acl(ace(0, DEFAULT_USER)))
        self.keep_natively(native, current)
        self.assert_read_as(current, "gethex", native)
        written = descriptor(OLD_USER, USERS, acl(ace(0, OLD_USER)))
        self.keep_natively(native, written)
        self.assert_read_as(written, "gethex", native, program=self.other)

    def test_a_malformed_descriptor_is_returned_as_stored(self):
        good = descriptor(OLD_USER, USERS, acl(ace(0, OLD_USER)))
        broken = [
            b"\x02" + good[1:],
            good[:2] + struct.pack("=I", 4) + struct.pack("=I", 4096) + good[10:],
            good[:-4],
            good[:-len(acl(ace(0, OLD_USER)))] + acl(ace(0, OLD_USER))[:10] + struct.pack("=H", 200) +
            acl(ace(0, OLD_USER))[12:],
            good[:30],
        ]
        native = self.path("native", "game", "file")
        for value in broken:
            self.keep_natively(native, value)
            self.assert_read_as(value, "gethex", native)


if __name__ == "__main__":
    unittest.main()
