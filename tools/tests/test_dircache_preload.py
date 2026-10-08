import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

PRELOAD = Path(__file__).resolve().parents[1] / "linuxfs/preload"

FAKE = r"""
#define _GNU_SOURCE
#include <dirent.h>
#include <dlfcn.h>
#include <string.h>
#include <sys/statfs.h>

int real_opendirs;

int statfs(const char *path, struct statfs *out) {
  static int (*next)(const char *, struct statfs *);
  if (!next) next = dlsym(RTLD_NEXT, "statfs");
  int r = next(path, out);
  if (r == 0 && strstr(path, "/fuse/")) out->f_type = 0x65735546;
  return r;
}

DIR *opendir(const char *path) {
  static DIR *(*next)(const char *);
  if (!next) next = dlsym(RTLD_NEXT, "opendir");
  real_opendirs++;
  return next(path);
}
"""

PROGRAM = r"""
#define _GNU_SOURCE
#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

static int by_name(const void *a, const void *b) { return strcmp(*(char *const *)a, *(char *const *)b); }

static void print(char **names, int n) {
  qsort(names, n, sizeof(*names), by_name);
  for (int i = 0; i < n; i++) printf("%s%s", i ? "," : "", names[i]);
  printf("\n");
}

static void list(const char *dir) {
  char *names[256];
  int n = 0;
  DIR *d = opendir(dir);
  struct dirent *e;
  while (d && (e = readdir(d)))
    if (strcmp(e->d_name, ".") && strcmp(e->d_name, "..")) names[n++] = strdup(e->d_name);
  if (d) closedir(d);
  print(names, n);
}

static void raw(const char *dir) {
  char buf[8192], *names[256];
  int n = 0, fd = open(dir, O_RDONLY | O_DIRECTORY);
  long k;
  while ((k = syscall(SYS_getdents64, fd, buf, sizeof(buf))) > 0) {
    for (long off = 0; off < k;) {
      struct dirent64 *e = (struct dirent64 *)(buf + off);
      if (strcmp(e->d_name, ".") && strcmp(e->d_name, "..")) names[n++] = strdup(e->d_name);
      off += e->d_reclen;
    }
  }
  close(fd);
  print(names, n);
}

static const char *stress_dir;
static int stop;

static void *reader(void *arg) {
  (void)arg;
  while (!__atomic_load_n(&stop, __ATOMIC_ACQUIRE)) {
    DIR *d = opendir(stress_dir);
    struct dirent *e;
    while (d && (e = readdir(d)))
      if (e->d_reclen == 0) abort();
    if (d) closedir(d);
  }
  return NULL;
}

static void stress(const char *dir) {
  pthread_t t[4];
  char a[4096], b[4096];
  stress_dir = dir;
  for (int i = 0; i < 4; i++) pthread_create(&t[i], NULL, reader, NULL);
  for (int i = 0; i < 300; i++) {
    snprintf(a, sizeof(a), "%s/s%d", dir, i);
    snprintf(b, sizeof(b), "%s/t%d", dir, i);
    close(open(a, O_WRONLY | O_CREAT, 0644));
    rename(a, b);
    if (i % 3 == 0) unlink(b);
  }
  __atomic_store_n(&stop, 1, __ATOMIC_RELEASE);
  for (int i = 0; i < 4; i++) pthread_join(t[i], NULL);
}

int main(int argc, char **argv) {
  int *opens = dlsym(RTLD_DEFAULT, "real_opendirs");
  for (int i = 1; i < argc; i++) {
    const char *op = argv[i], *a = argv[i + 1];
    if (!strcmp(op, "list")) { list(a); i++; }
    else if (!strcmp(op, "raw")) { raw(a); i++; }
    else if (!strcmp(op, "create")) { close(open(a, O_WRONLY | O_CREAT, 0644)); i++; }
    else if (!strcmp(op, "sneak")) { close((int)syscall(SYS_openat, AT_FDCWD, a, O_WRONLY | O_CREAT, 0644)); i++; }
    else if (!strcmp(op, "sneak-unlink")) { syscall(SYS_unlinkat, AT_FDCWD, a, 0); i++; }
    else if (!strcmp(op, "unlink")) { unlink(a); i++; }
    else if (!strcmp(op, "remove")) { remove(a); i++; }
    else if (!strcmp(op, "mkdir")) { mkdir(a, 0755); i++; }
    else if (!strcmp(op, "rmdir")) { rmdir(a); i++; }
    else if (!strcmp(op, "rename")) { rename(a, argv[i + 2]); i += 2; }
    else if (!strcmp(op, "half")) {
      DIR *d = opendir(a);
      readdir(d);
      closedir(d);
      i++;
    }
    else if (!strcmp(op, "rewind")) {
      DIR *d = opendir(a);
      int n = 0, m = 0;
      while (readdir(d)) n++;
      rewinddir(d);
      while (readdir(d)) m++;
      struct stat st;
      printf("%d %d %d\n", n, m, fstat(dirfd(d), &st) == 0 && S_ISDIR(st.st_mode));
      closedir(d);
      i++;
    }
    else if (!strcmp(op, "seek")) {
      DIR *d = opendir(a);
      readdir(d);
      readdir(d);
      long at = telldir(d);
      char want[256];
      snprintf(want, sizeof(want), "%s", readdir(d)->d_name);
      readdir(d);
      seekdir(d, at);
      int back = strcmp(readdir(d)->d_name, want) == 0;
      seekdir(d, at + 1);
      struct dirent *e = readdir(d);
      printf("%d %d\n", back, e != NULL && e->d_reclen > 0);
      closedir(d);
      i++;
    }
    else if (!strcmp(op, "stress")) { stress(a); i++; }
    else if (!strcmp(op, "opens")) printf("%d\n", opens ? *opens : -1);
  }
  return 0;
}
"""


@unittest.skipUnless(shutil.which("cc"), "needs a C compiler")
class DirCachePreloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dir = Path(tempfile.mkdtemp())
        build = cls.dir / "build"
        build.mkdir()
        (build / "fake.c").write_text(FAKE)
        (build / "program.c").write_text(PROGRAM)
        cls.cache = build / "libblsession.so"
        cls.fake = build / "fake.so"
        subprocess.run(["cc", "-shared", "-fPIC", "-O2", "-Wall", "-pthread", "-o", str(cls.cache),
                        *map(str, sorted(PRELOAD.glob("*.c"))), "-ldl"], check=True)
        subprocess.run(["cc", "-shared", "-fPIC", "-O2", "-o", str(cls.fake), str(build / "fake.c"), "-ldl"], check=True)
        cls.steam = cls.dir / "steamrtarm64/steam"
        cls.steam.parent.mkdir()
        subprocess.run(["cc", "-O2", "-pthread", "-o", str(cls.steam), str(build / "program.c"), "-ldl"], check=True)
        cls.other = build / "steamwebhelper"
        shutil.copy(cls.steam, cls.other)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.dir, True)

    def setUp(self):
        self.lib = Path(tempfile.mkdtemp(dir=self.dir)) / "fuse/steamapps"
        self.staging = self.lib / "downloading/4992280"
        self.staging.mkdir(parents=True)
        for name in ("a.bundle", "b.bundle"):
            (self.staging / name).touch()

    def run_program(self, *args, program=None, env=None):
        environment = dict(os.environ, LD_PRELOAD="%s:%s" % (self.cache, self.fake))
        environment.update(env or {})
        result = subprocess.run([str(program or self.steam), *map(str, args)], env=environment,
                                capture_output=True, text=True, check=True, timeout=60)
        return result.stdout.splitlines()

    def test_repeated_listings_read_the_directory_once(self):
        out = self.run_program("list", self.staging, "list", self.staging, "list", self.staging, "opens")
        self.assertEqual(["a.bundle,b.bundle"] * 3 + ["1"], out)

    def test_own_changes_keep_the_listing_exact(self):
        s = self.staging
        out = self.run_program(
            "list", s,
            "create", s / "c.bundle", "list", s, "raw", s,
            "create", s / "a.bundle", "list", s, "raw", s,
            "mkdir", s / "sub", "list", s, "raw", s,
            "rename", s / "c.bundle", s / "d.bundle", "list", s, "raw", s,
            "unlink", s / "a.bundle", "list", s, "raw", s,
            "rmdir", s / "sub", "list", s, "raw", s,
            "remove", s / "b.bundle", "list", s, "raw", s,
            "opens")
        self.assertEqual(out[2:15:2], out[1:15:2])
        self.assertEqual("d.bundle", out[-2])
        self.assertEqual("1", out[-1])

    def test_moves_between_directories_update_both(self):
        common = self.lib / "common/Game"
        common.mkdir(parents=True)
        s = self.staging
        out = self.run_program("list", s, "list", common,
                               "rename", s / "a.bundle", common / "a.bundle",
                               "list", s, "raw", s, "list", common, "raw", common, "opens")
        self.assertEqual(["b.bundle", "b.bundle", "a.bundle", "a.bundle", "2"], out[2:])

    def test_changes_made_around_the_cache_are_noticed(self):
        s = self.staging
        out = self.run_program("list", s, "sneak", s / "x.bundle", "list", s,
                               "sneak-unlink", s / "a.bundle", "list", s, "opens")
        self.assertEqual(["a.bundle,b.bundle", "a.bundle,b.bundle,x.bundle", "b.bundle,x.bundle", "3"], out)

    def test_another_process_changing_the_directory_is_noticed(self):
        s = self.staging
        first = self.run_program("list", s)
        (s / "y.bundle").touch()
        self.assertEqual(["a.bundle,b.bundle"], first)
        self.assertEqual(["a.bundle,b.bundle,y.bundle"], self.run_program("list", s))

    def test_a_listing_read_halfway_is_not_kept(self):
        out = self.run_program("half", self.staging, "list", self.staging, "list", self.staging, "opens")
        self.assertEqual(["a.bundle,b.bundle", "a.bundle,b.bundle", "2"], out)

    def test_rewind_and_dirfd_work_on_a_cached_listing(self):
        out = self.run_program("list", self.staging, "rewind", self.staging, "opens")
        self.assertEqual(["4 4 1", "1"], out[1:])

    def test_seekdir_returns_to_a_told_position(self):
        for name in ("c.bundle", "d.bundle", "e.bundle"):
            (self.staging / name).touch()
        out = self.run_program("list", self.staging, "seek", self.staging, "opens")
        self.assertEqual(["1 1", "1"], out[1:])

    def test_readers_and_changes_on_other_threads(self):
        s = self.staging
        out = self.run_program("list", s, "stress", s, "list", s, "raw", s)
        self.assertEqual(out[-1], out[-2])
        self.assertEqual(202, len(out[-1].split(",")))

    def test_only_the_client_and_only_fuse_steamapps(self):
        other = self.run_program("list", self.staging, "list", self.staging, "opens", program=self.other)
        self.assertEqual("2", other[-1])
        native = Path(tempfile.mkdtemp(dir=self.dir)) / "steamapps/downloading/1"
        native.mkdir(parents=True)
        self.assertEqual("2", self.run_program("list", native, "list", native, "opens")[-1])
        outside = self.lib.parent / "elsewhere"
        outside.mkdir()
        self.assertEqual("2", self.run_program("list", outside, "list", outside, "opens")[-1])

    def test_can_be_turned_off(self):
        out = self.run_program("list", self.staging, "list", self.staging, "opens", env={"BL_NO_DIR_CACHE": "1"})
        self.assertEqual("2", out[-1])


if __name__ == "__main__":
    unittest.main()
