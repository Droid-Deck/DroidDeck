import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import unittest

PRELOAD = Path(__file__).resolve().parents[1] / "linuxfs/preload"

FAKE = r"""
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/inotify.h>
#include <unistd.h>

int real_opendirs;
int real_accesses;

static void coarse(const char *path, struct stat *st) {
  if (getenv("COARSE") && strstr(path, "/fuse/") && S_ISDIR(st->st_mode)) {
    st->st_mtim = st->st_ctim = (struct timespec){1, 0};
    st->st_size = 4096;
    st->st_nlink = 1;
  }
}

int stat(const char *path, struct stat *st) {
  static int (*next)(const char *, struct stat *);
  if (!next) next = dlsym(RTLD_NEXT, "stat");
  char folded[4096];
  const char *fold = getenv("FOLD");
  const char *upper = fold ? strstr(path, "/STEAMAPPS") : NULL;
  if (upper && strlen(path) < sizeof(folded)) {
    strcpy(folded, path);
    memcpy(folded + (upper - path) + 1, "steamapps", 9);
    if (!strcmp(fold, "all"))
      for (char *c = folded + (upper - path); *c; c++) if (*c >= 'A' && *c <= 'Z') *c += 'a' - 'A';
    path = folded;
  }
  int r = next(path, st);
  if (r == 0) coarse(path, st);
  return r;
}

int fstat(int fd, struct stat *st) {
  static int (*next)(int, struct stat *);
  if (!next) next = dlsym(RTLD_NEXT, "fstat");
  char link[64], path[4096];
  snprintf(link, sizeof(link), "/proc/self/fd/%d", fd);
  ssize_t n = readlink(link, path, sizeof(path) - 1);
  path[n > 0 ? n : 0] = 0;
  int r = next(fd, st);
  if (r == 0) coarse(path, st);
  return r;
}

int fstatfs(int fd, struct statfs *out) {
  static int (*next)(int, struct statfs *);
  if (!next) next = dlsym(RTLD_NEXT, "fstatfs");
  char link[64], path[4096];
  snprintf(link, sizeof(link), "/proc/self/fd/%d", fd);
  ssize_t n = readlink(link, path, sizeof(path) - 1);
  path[n > 0 ? n : 0] = 0;
  int r = next(fd, out);
  if (r == 0 && strstr(path, "/fuse/")) out->f_type = 0x65735546;
  return r;
}

int inotify_add_watch(int fd, const char *path, uint32_t mask) {
  static int (*next)(int, const char *, uint32_t);
  if (!next) next = dlsym(RTLD_NEXT, "inotify_add_watch");
  if (getenv("NOFOLLOW_FAIL") && (mask & IN_DONT_FOLLOW)) {
    errno = ENOSPC;
    return -1;
  }
  return next(fd, path, mask);
}

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
  if (getenv("FASTPATH")) {
    int fd = open(path, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NONBLOCK);
    DIR *d = fd < 0 ? NULL : fdopendir(fd);
    if (d == NULL && fd >= 0) close(fd);
    return d;
  }
  real_opendirs++;
  return next(path);
}

int faccessat(int dirfd, const char *path, int mode, int flags) {
  static int (*next)(int, const char *, int, int);
  if (!next) next = dlsym(RTLD_NEXT, "faccessat");
  if (strstr(path, "/fuse/")) real_accesses++;
  return next(dirfd, path, mode, flags);
}

DIR *fdopendir(int fd) {
  static DIR *(*next)(int);
  if (!next) next = dlsym(RTLD_NEXT, "fdopendir");
  real_opendirs++;
  return next(fd);
}
"""

PROGRAM = r"""
#define _GNU_SOURCE
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
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
  int *accesses = dlsym(RTLD_DEFAULT, "real_accesses");
  for (int i = 1; i < argc; i++) {
    const char *op = argv[i], *a = argv[i + 1];
    if (!strcmp(op, "list")) { list(a); i++; }
    else if (!strcmp(op, "raw")) { raw(a); i++; }
    else if (!strcmp(op, "create")) { close(open(a, O_WRONLY | O_CREAT, 0644)); i++; }
    else if (!strcmp(op, "sneak")) { close((int)syscall(SYS_openat, AT_FDCWD, a, O_WRONLY | O_CREAT, 0644)); i++; }
    else if (!strcmp(op, "sneak-unlink")) { syscall(SYS_unlinkat, AT_FDCWD, a, 0); i++; }
    else if (!strcmp(op, "sneak-rename")) { syscall(SYS_renameat, AT_FDCWD, a, AT_FDCWD, argv[i + 2]); i += 2; }
    else if (!strcmp(op, "sneak-mkdir")) { syscall(SYS_mkdirat, AT_FDCWD, a, 0755); i++; }
    else if (!strcmp(op, "pause")) {
      char line[16];
      printf("ready\n");
      fflush(stdout);
      if (!fgets(line, sizeof(line), stdin)) return 1;
    }
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
    else if (!strcmp(op, "drain")) {
      DIR *d = opendir(a);
      int rounds = 0, n;
      do {
        struct dirent *e;
        n = 0;
        while ((e = readdir(d)))
          if (strcmp(e->d_name, ".") && strcmp(e->d_name, "..") && unlinkat(dirfd(d), e->d_name, 0) == 0) n++;
        rewinddir(d);
      } while (n > 0 && ++rounds < 50);
      closedir(d);
      printf("%d\n", rounds);
      i++;
    }
    else if (!strcmp(op, "fork")) {
      pid_t child = fork();
      if (child == 0) {
        DIR *d = opendir(a);
        while (d && readdir(d)) {}
        if (d) closedir(d);
        close((int)syscall(SYS_openat, AT_FDCWD, argv[i + 2], O_WRONLY | O_CREAT, 0644));
        DIR *e = opendir(a);
        while (e && readdir(e)) {}
        if (e) closedir(e);
        _exit(0);
      }
      waitpid(child, NULL, 0);
      i += 2;
    }
    else if (!strcmp(op, "access")) {
      int r = access(a, F_OK);
      printf("%d %d\n", r, r ? errno : 0);
      i++;
    }
    else if (!strcmp(op, "accesses")) printf("%d\n", accesses ? *accesses : -1);
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

    def environment(self, env):
        environment = dict(os.environ, LD_PRELOAD="%s:%s" % (self.cache, self.fake))
        environment.update(env or {})
        return environment

    def run_program(self, *args, program=None, env=None):
        result = subprocess.run([str(program or self.steam), *map(str, args)], env=self.environment(env),
                                capture_output=True, text=True, check=True, timeout=60)
        return result.stdout.splitlines()

    def run_paused(self, *args, between, env=None):
        probe = subprocess.Popen([str(self.steam), *map(str, args)], env=self.environment(env), text=True,
                                 stdin=subprocess.PIPE, stdout=subprocess.PIPE)
        timer = threading.Timer(60, probe.kill)
        timer.start()
        try:
            before = []
            for line in probe.stdout:
                if line == "ready\n":
                    break
                before.append(line.rstrip("\n"))
            else:
                self.fail("the probe stopped before it was ready")
            between()
            after, _ = probe.communicate("go\n", timeout=60)
        finally:
            timer.cancel()
            if probe.poll() is None:
                probe.kill()
            probe.communicate()
        self.assertEqual(0, probe.returncode)
        return before + after.splitlines()

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

    def test_changes_are_noticed_when_directory_times_are_coarse(self):
        s = self.staging
        out = self.run_program("list", s, "sneak", s / "x.bundle", "list", s,
                               "sneak-unlink", s / "a.bundle", "list", s,
                               "create", s / "c.bundle", "list", s, "raw", s, env={"COARSE": "1"})
        self.assertEqual(["a.bundle,b.bundle", "a.bundle,b.bundle,x.bundle", "b.bundle,x.bundle"], out[:3])
        self.assertEqual(out[4], out[3])
        self.assertEqual("b.bundle,c.bundle,x.bundle", out[3])

    def test_own_changes_stay_cached_when_directory_times_are_coarse(self):
        s = self.staging
        out = self.run_program("list", s, "create", s / "c.bundle", "rename", s / "c.bundle", s / "d.bundle",
                               "unlink", s / "a.bundle", "list", s, "raw", s, "opens", env={"COARSE": "1"})
        self.assertEqual(["b.bundle,d.bundle", "b.bundle,d.bundle", "1"], out[1:])

    def test_rewinddir_sees_what_changed_since(self):
        s = self.staging
        out = self.run_program("list", s, "drain", s, "list", s, "raw", s, env={"COARSE": "1"})
        self.assertEqual(["1", "", ""], out[1:])

    def test_directories_opened_through_fdopendir_underneath(self):
        s = self.staging
        out = self.run_program("list", s, "list", s, "sneak", s / "x.bundle", "list", s, "rewind", s,
                               "create", s / "c.bundle", "list", s, "raw", s, "opens",
                               env={"FASTPATH": "1", "COARSE": "1"})
        self.assertEqual(["a.bundle,b.bundle", "a.bundle,b.bundle", "a.bundle,b.bundle,x.bundle"], out[:3])
        self.assertEqual(out[5], out[4])
        self.assertEqual("a.bundle,b.bundle,c.bundle,x.bundle", out[4])
        self.assertEqual("2", out[-1])

    def test_a_forked_child_leaves_the_parent_cache_alone(self):
        s = self.staging
        out = self.run_program("list", s, "fork", s, s / "f.bundle", "list", s, "raw", s, env={"COARSE": "1"})
        self.assertEqual("a.bundle,b.bundle,f.bundle", out[1])
        self.assertEqual(out[2], out[1])

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

    def test_missing_names_are_answered_from_the_listing(self):
        s = self.staging
        out = self.run_program("list", s, "access", s / "x.bundle", "access", s / "y/z.bundle",
                               "access", s / "c.bundle", "accesses", "access", s / "a.bundle", "accesses", "opens")
        self.assertEqual(["-1 2", "-1 2", "-1 2", "1", "0 0", "2", "1"], out[1:])

    def test_a_name_that_differs_only_in_case_is_asked_of_the_filesystem(self):
        s = self.staging
        out = self.run_program("list", s, "access", s / "A.BUNDLE", "accesses")
        self.assertEqual(["-1 2", "1"], out[1:])

    def test_misses_before_the_client_lists_a_directory_reach_the_filesystem(self):
        s = self.staging
        out = self.run_program("access", s / "x.bundle", "access", s / "y.bundle", "accesses", "opens")
        self.assertEqual(["-1 2", "-1 2", "2", "0"], out)

    def test_names_created_after_a_miss_are_found(self):
        s = self.staging
        out = self.run_program("list", s, "access", s / "x.bundle", "create", s / "x.bundle", "access", s / "x.bundle",
                               "access", s / "y.bundle", "sneak", s / "y.bundle", "access", s / "y.bundle",
                               "rename", s / "y.bundle", s / "w.bundle", "access", s / "w.bundle",
                               "mkdir", s / "sub", "access", s / "sub",
                               env={"COARSE": "1"})
        self.assertEqual(["-1 2", "0 0", "-1 2", "0 0", "0 0", "0 0"], out[1:])

    def test_a_differently_cased_directory_reaches_the_filesystem_when_case_matters(self):
        s = self.staging
        upper = Path(str(s).replace("downloading", "DOWNLOADING"))
        out = self.run_program("list", s, "access", upper / "x.bundle", "accesses")
        self.assertEqual(["-1 2", "1"], out[1:])

    def test_a_differently_cased_directory_uses_the_listing_when_the_filesystem_folds_case(self):
        s = self.staging
        upper = Path(str(s).replace("downloading", "DOWNLOADING"))
        out = self.run_program("list", s, "access", upper / "x.bundle", "access", upper / "A.BUNDLE", "accesses",
                               env={"FOLD": "all"})
        self.assertEqual(["-1 2", "-1 2", "1"], out[1:])

    def test_a_case_sensitive_folder_below_a_folding_steamapps_reaches_the_filesystem(self):
        s = self.staging
        upper = self.lib / "DOWNLOADING" / s.name
        upper.mkdir(parents=True)
        (upper / "x.bundle").write_bytes(b"")
        out = self.run_program("list", s, "access", upper / "x.bundle", "access", upper / "y.bundle", "accesses",
                               env={"FOLD": "1"})
        self.assertEqual(["0 0", "-1 2", "2"], out[1:])

    def test_a_differently_cased_folder_above_steamapps_reaches_the_filesystem(self):
        s = self.staging
        top = self.lib.parent.parent
        other = top.parent / top.name.swapcase() / s.relative_to(top)
        self.assertNotEqual(top.name, top.name.swapcase())
        other.mkdir(parents=True)
        (other / "x.bundle").write_bytes(b"")
        out = self.run_program("list", s, "access", other / "x.bundle", "accesses", env={"FOLD": "all"})
        self.assertEqual(["0 0", "1"], out[1:])

    def test_a_listing_is_dropped_when_a_folder_above_it_is_renamed(self):
        s = self.staging
        parent = s.parent
        moved = parent.parent / "downloading.old"
        out = self.run_program("list", s, "rename", parent, moved, "mkdir", parent, "mkdir", s,
                               "sneak", s / "x.bundle", "access", s / "x.bundle", "accesses")
        self.assertEqual(["0 0", "1"], out[1:])

    def test_a_listing_is_dropped_when_another_process_renames_a_folder_above_it(self):
        s = self.staging
        parent = s.parent

        def swap():
            parent.rename(parent.parent / "downloading.old")
            s.mkdir(parents=True)
            (s / "x.bundle").touch()

        out = self.run_paused("list", s, "pause", "access", s / "x.bundle", "accesses", between=swap)
        self.assertEqual(["0 0", "1"], out[1:])

    def test_a_listing_is_dropped_when_another_process_renames_the_library_above_steamapps(self):
        s = self.staging
        root = self.lib.parent

        def swap():
            root.rename(root.parent / "fuse.old")
            s.mkdir(parents=True)
            (s / "x.bundle").touch()

        out = self.run_paused("list", s, "pause", "access", s / "x.bundle", "accesses", between=swap)
        self.assertEqual(["0 0", "1"], out[1:])

    def test_a_repointed_symlink_above_a_listing_does_not_hide_files(self):
        s = self.staging
        real = self.lib / "dl-a"
        s.parent.rename(real)
        s.parent.symlink_to(real.name)
        other = self.lib / "dl-b" / s.name
        other.mkdir(parents=True)
        (other / "x.bundle").touch()

        def repoint():
            link = self.lib / "downloading.new"
            link.symlink_to("dl-b")
            link.replace(s.parent)

        out = self.run_paused("list", s, "pause", "access", s / "x.bundle", between=repoint)
        self.assertEqual("0 0", out[1])

    def test_a_symlink_check_that_cannot_be_watched_does_not_hide_files(self):
        s = self.staging
        real = self.lib / "dl-a"
        s.parent.rename(real)
        s.parent.symlink_to(real.name)
        other = self.lib / "dl-b" / s.name
        other.mkdir(parents=True)
        (other / "x.bundle").touch()

        def repoint():
            link = self.lib / "downloading.new"
            link.symlink_to("dl-b")
            link.replace(s.parent)

        out = self.run_paused("list", s, "pause", "access", s / "x.bundle", between=repoint, env={"NOFOLLOW_FAIL": "1"})
        self.assertEqual("0 0", out[1])

    def test_an_unreadable_folder_above_steamapps_does_not_hide_files(self):
        s = self.staging
        root = self.lib.parent
        moved = root.parent / "fuse.old"
        root.chmod(0o311)
        self.addCleanup(lambda: moved.chmod(0o755) if moved.exists() else root.chmod(0o755))
        if os.access(root, os.R_OK):
            self.skipTest("folder permissions are not enforced here")

        def swap():
            root.rename(moved)
            s.mkdir(parents=True)
            (s / "x.bundle").touch()

        out = self.run_paused("list", s, "pause", "access", s / "x.bundle", between=swap)
        self.assertEqual("0 0", out[1])

    def test_a_listing_is_dropped_when_another_process_removes_a_folder_above_it(self):
        deep = self.staging / "deep"
        deep.mkdir()
        parent = self.staging.parent

        def swap():
            self.staging.rename(self.lib / "moved")
            parent.rmdir()
            deep.mkdir(parents=True)
            (deep / "x.bundle").touch()

        out = self.run_paused("list", deep, "pause", "access", deep / "x.bundle", "accesses", between=swap)
        self.assertEqual(["", "0 0", "1"], out)

    def test_a_listing_is_dropped_when_a_folder_above_it_is_renamed_around_the_cache(self):
        s = self.staging
        parent = s.parent
        out = self.run_program("list", s, "sneak-rename", parent, parent.parent / "downloading.old",
                               "sneak-mkdir", parent, "sneak-mkdir", s, "sneak", s / "x.bundle",
                               "access", s / "x.bundle", "accesses")
        self.assertEqual(["0 0", "1"], out[1:])

    def test_changes_in_a_cached_folder_above_keep_the_listing_below(self):
        s = self.staging
        parent = s.parent

        def churn():
            (parent / "z").touch()
            (parent / "z").unlink()
            (parent / "sibling").mkdir()
            (parent / "sibling").rmdir()

        out = self.run_paused("list", parent, "list", s, "pause", "list", parent, "list", s,
                              "access", s / "x.bundle", "accesses", "opens", between=churn)
        self.assertEqual([s.name, "a.bundle,b.bundle", s.name, "a.bundle,b.bundle", "-1 2", "0", "3"], out)

    def test_misses_outside_the_client_or_fuse_steamapps_reach_the_filesystem(self):
        s = self.staging
        other = self.run_program("list", s, "access", s / "x.bundle", "access", s / "y.bundle", "accesses",
                                 program=self.other)
        self.assertEqual(["-1 2", "-1 2", "2"], other[1:])
        outside = self.lib.parent / "elsewhere"
        outside.mkdir()
        out = self.run_program("list", outside, "access", outside / "x", "access", outside / "y", "accesses")
        self.assertEqual(["-1 2", "-1 2", "2"], out[1:])

    def test_misses_reach_the_filesystem_when_turned_off(self):
        s = self.staging
        out = self.run_program("list", s, "access", s / "x.bundle", "access", s / "y.bundle", "accesses",
                               env={"BL_NO_DIR_CACHE": "1"})
        self.assertEqual(["-1 2", "-1 2", "2"], out[1:])

    def test_can_be_turned_off(self):
        out = self.run_program("list", self.staging, "list", self.staging, "opens", env={"BL_NO_DIR_CACHE": "1"})
        self.assertEqual("2", out[-1])


if __name__ == "__main__":
    unittest.main()
