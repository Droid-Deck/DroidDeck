import errno
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

PRELOAD = Path(__file__).resolve().parents[1] / "linuxfs/preload"
FASTPATH = Path(__file__).resolve().parents[1] / "proot/fastpath/fastpath.c"

# Opens each "<dirfd>\t<flags>\t<path>" case with openat2(RESOLVE_IN_ROOT) as FEX does: first through the kernel's own
# openat2 on argv[1] when it has one, then, with openat2 failing ENOSYS as under proot, through the preload on argv[2].
PROGRAM = r"""
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef SYS_openat2
#define SYS_openat2 437
#endif

struct how { uint64_t flags, mode, resolve; };
static char cases[256][512];
static int count;

static void run(long (*call)(long, ...), const char *tree) {
  int root = open(tree, O_PATH | O_DIRECTORY | O_CLOEXEC), outer = open("/", O_PATH | O_DIRECTORY | O_CLOEXEC);
  for (int i = 0; i < count; i++) {
    char *flags = strchr(cases[i], '\t') + 1, *path = strchr(flags, '\t') + 1;
    struct how h = {strtoull(flags, NULL, 0), 0, 0x10};
    if (h.flags & O_CREAT) h.mode = 0644;
    long fd = call(SYS_openat2, (long)(cases[i][0] == 'o' ? outer : root), (long)path, (long)&h, (long)sizeof(h));
    struct stat st;
    if (fd < 0) {
      printf("ERR %d\n", errno);
    } else {
      fstat((int)fd, &st);
      printf("OK %llu %llu\n", (unsigned long long)st.st_dev, (unsigned long long)st.st_ino);
      close((int)fd);
    }
  }
}

int main(int argc, char **argv) {
  if (argc < 3) return 2;
  while (count < 256 && fgets(cases[count], sizeof(cases[count]), stdin)) {
    cases[count][strcspn(cases[count], "\n")] = 0;
    count++;
  }
  long (*kernel)(long, ...) = (long (*)(long, ...))dlsym(dlopen("libc.so.6", RTLD_LAZY | RTLD_NOLOAD), "syscall");
  struct how probe = {O_RDONLY, 0, 0x10};
  long fd = kernel(SYS_openat2, (long)AT_FDCWD, (long)".", (long)&probe, (long)sizeof(probe));
  if (fd >= 0) {
    close((int)fd);
    printf("kernel\n");
    run(kernel, argv[1]);
    struct sock_filter filter[] = {
      BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
      BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_openat2, 0, 1),
      BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | ENOSYS),
      BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog prog = {sizeof(filter) / sizeof(filter[0]), filter};
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) || prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog)) {
      perror("seccomp");
      return 3;
    }
  }
  printf("preload\n");
  run(syscall, argv[2]);
  return 0;
}
"""

COUNTER = r"""
#define _GNU_SOURCE
#include <dlfcn.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <sys/stat.h>
#include <unistd.h>

static int opens, stats, links, statxs;

int openat(int dirfd, const char *path, int flags, ...) {
  va_list args;
  va_start(args, flags);
  mode_t mode = va_arg(args, int);
  va_end(args);
  opens++;
  return ((int (*)(int, const char *, int, ...))dlsym(RTLD_NEXT, "openat"))(dirfd, path, flags, mode);
}

int fstatat(int dirfd, const char *path, struct stat *st, int flags) {
  stats++;
  return ((int (*)(int, const char *, struct stat *, int))dlsym(RTLD_NEXT, "fstatat"))(dirfd, path, st, flags);
}

int fstatat64(int dirfd, const char *path, struct stat64 *st, int flags) {
  stats++;
  return ((int (*)(int, const char *, struct stat64 *, int))dlsym(RTLD_NEXT, "fstatat64"))(dirfd, path, st, flags);
}

ssize_t readlinkat(int dirfd, const char *path, char *buf, size_t size) {
  links++;
  return ((ssize_t (*)(int, const char *, char *, size_t))dlsym(RTLD_NEXT, "readlinkat"))(dirfd, path, buf, size);
}

int statx(int dirfd, const char *path, int flags, unsigned int mask, struct statx *out) {
  statxs++;
  return ((int (*)(int, const char *, int, unsigned int, struct statx *))dlsym(RTLD_NEXT, "statx"))(dirfd, path, flags, mask, out);
}

__attribute__((destructor)) static void report(void) {
  fprintf(stderr, "calls %d %d %d %d\n", opens, stats, links, statxs);
}
"""

RAW_OPEN = r"""
#define _GNU_SOURCE
#include <fcntl.h>
#include <stdio.h>
#include <sys/syscall.h>
#include <unistd.h>

int main(int argc, char **argv) {
  char text[16] = "";
  long fd = syscall(SYS_openat, AT_FDCWD, argv[1], O_RDONLY | O_CLOEXEC, 0);
  if (fd < 0 || read((int)fd, text, sizeof(text) - 1) < 0) return 1;
  printf("%s\n", text);
  return syscall(SYS_openat, AT_FDCWD, argv[2], O_RDONLY | O_CLOEXEC, 0) == -1 ? 0 : 1;
}
"""

WRAPPER = r"""
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef SYS_openat2
#define SYS_openat2 437
#endif

struct how { uint64_t flags, mode, resolve; };

int main(int argc, char **argv) {
  struct sock_filter filter[] = {
    BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_openat2, 0, 1),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | ENOSYS),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
  };
  struct sock_fprog prog = {sizeof(filter) / sizeof(filter[0]), filter};
  if (argc < 3 || prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) || prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog)) return 3;
  int (*call)(int, const char *, struct how *, size_t) = (int (*)(int, const char *, struct how *, size_t))dlsym(RTLD_DEFAULT, "openat2");
  if (!call) return 4;
  int dir = open(argv[1], O_PATH | O_DIRECTORY | O_CLOEXEC);
  struct how h = {O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0644, 0x08};
  int fd = call(dir, argv[2], &h, sizeof(h));
  if (fd < 0) {
    printf("ERR %d\n", errno);
    return 1;
  }
  printf("OK\n");
  return close(fd);
}
"""

RAW_LOOKUP = r"""
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifdef SYS_newfstatat
#define RAW_FSTATAT SYS_newfstatat
#else
#define RAW_FSTATAT SYS_fstatat64
#endif

int main(int argc, char **argv) {
  struct stat st;
  struct statx sx;
  char link[64] = "";
  if (syscall(RAW_FSTATAT, AT_FDCWD, argv[1], &st, 0) != 0) return 1;
  if (syscall(RAW_FSTATAT, AT_FDCWD, argv[2], &st, 0) != -1 || errno != ENOENT) return 2;
  long n = syscall(SYS_readlinkat, AT_FDCWD, argv[3], link, sizeof(link) - 1);
  if (n <= 0) return 3;
  sx.stx_size = 0;
  long found = syscall(SYS_statx, AT_FDCWD, argv[1], 0, STATX_SIZE, &sx);
  int have_statx = !(found == -1 && errno == ENOSYS);
  if (have_statx && found != 0) return 4;
  long missing = syscall(SYS_statx, AT_FDCWD, argv[2], 0, STATX_SIZE, &sx);
  if (have_statx && (missing != -1 || errno != ENOENT)) return 5;
  printf("%lld %.*s %llu\n", (long long)st.st_size, (int)n, link, (unsigned long long)sx.stx_size);
  return 0;
}
"""

O_PATH = getattr(os, "O_PATH", 0o10000000)

# (dirfd: r = the tree, o = "/"), flags, path, expected: a path in the tree, an errno name, or "proc" for a file outside it.
CASES = [
    ("r", os.O_RDONLY, "etc/alternatives/hn", "etc/hostname"),
    ("r", os.O_RDONLY, "/lib64/x86_64-linux-gnu/libfoo.so.1", "usr/lib/x86_64-linux-gnu/libfoo.so.1.2"),
    ("r", os.O_RDONLY, "lib/x86_64-linux-gnu/libfoo.so.1", "usr/lib/x86_64-linux-gnu/libfoo.so.1.2"),
    ("r", os.O_RDONLY, "etc/escape", "etc/hostname"),
    ("r", os.O_RDONLY, "dirlink/../etc/hostname", "etc/hostname"),
    ("r", os.O_RDONLY, "rootlink/rootlink//etc/./hostname", "etc/hostname"),
    ("r", os.O_RDONLY, "../../etc/hostname", "etc/hostname"),
    ("r", os.O_RDONLY | os.O_DIRECTORY, "/", "."),
    ("r", os.O_RDONLY | os.O_DIRECTORY, "usr/../..", "."),
    ("r", os.O_RDONLY, "chain/l2", "chain/end"),
    ("r", os.O_RDONLY, "chain/l1", "ELOOP"),
    ("r", os.O_RDONLY, "loop/a/x", "ELOOP"),
    ("r", os.O_RDONLY | os.O_NOFOLLOW, "etc/alternatives/hn", "ELOOP"),
    ("r", O_PATH | os.O_NOFOLLOW, "etc/alternatives/hn", "etc/alternatives/hn"),
    ("r", os.O_RDONLY | os.O_NOFOLLOW, "lib/x86_64-linux-gnu/libfoo.so.1.2", "usr/lib/x86_64-linux-gnu/libfoo.so.1.2"),
    ("r", os.O_RDONLY | os.O_NOFOLLOW | os.O_DIRECTORY, "dirlink/", "usr"),
    ("r", os.O_RDONLY, "file/", "ENOTDIR"),
    ("r", os.O_RDONLY, "file/../etc/hostname", "ENOTDIR"),
    ("r", os.O_RDONLY, "nonexist/../etc/hostname", "ENOENT"),
    ("r", os.O_RDWR | os.O_CREAT | os.O_EXCL, "dangling", "EEXIST"),
    ("r", os.O_RDWR | os.O_CREAT, "dangling", "usr/newfile"),
    ("r", os.O_RDWR | os.O_CREAT, "missing/newfile", "ENOENT"),
    ("r", os.O_RDWR | os.O_CREAT, "newdir/", "EISDIR"),
    ("o", os.O_RDONLY | os.O_NONBLOCK, "proc/self/fd/0", "EXDEV"),
    ("o", os.O_RDONLY, "proc/self/exe", "EXDEV"),
    ("o", os.O_RDONLY, "proc/self/cwd/x", "EXDEV"),
    ("o", os.O_RDONLY, "proc/self/status", "proc"),
]

# Only the preload: an absolute link to a path missing in the tree but present outside it, as droiddeck-fex's rootfs has.
HOST_CASES = [
    ("r", os.O_RDONLY, "lib/x86_64-linux-gnu/libbar.so.2", "host:libbar.so.2"),
    ("r", os.O_RDONLY, "hostdir/sub/data", "host:sub/data"),
    ("r", os.O_RDONLY | os.O_DIRECTORY, "hostdir/sub", "host:sub"),
    ("r", os.O_RDONLY, "etc/alternatives/hn", "etc/hostname"),
    ("r", os.O_RDONLY, "hostdir/missing", "ENOENT"),
]


def make_tree(root, outside):
    for folder in ("usr/lib/x86_64-linux-gnu", "etc/alternatives", "loop", "chain"):
        (root / folder).mkdir(parents=True)
    (root / "usr/lib/x86_64-linux-gnu/libfoo.so.1.2").write_text("x86")
    (root / "etc/hostname").write_text("inside")
    (root / "file").write_text("f")
    (root / "chain/end").write_text("end")
    links = {"usr/lib/x86_64-linux-gnu/libfoo.so.1": "libfoo.so.1.2", "lib": "usr/lib", "lib64": "/usr/lib",
             "etc/alternatives/hn": "/etc/hostname", "etc/escape": "../../../../../../etc/hostname", "loop/a": "b",
             "loop/b": "a", "dirlink": "/usr", "rootlink": "/", "dangling": "/usr/newfile"}
    links.update({"chain/l%d" % i: ("l%d" % (i + 1) if i < 41 else "end") for i in range(1, 42)})
    links.update({"usr/lib/x86_64-linux-gnu/libbar.so.2": str(outside / "libbar.so.2"), "hostdir": str(outside)})
    for link, target in links.items():
        os.symlink(target, root / link)


def make_outside(outside):
    (outside / "sub").mkdir(parents=True)
    (outside / "libbar.so.2").write_text("host")
    (outside / "sub/data").write_text("host")


def labels(lines, root, outside):
    inodes = {(os.lstat(root).st_dev, os.lstat(root).st_ino): "."}
    for base, prefix in ((root, ""), (outside, "host:")):
        for folder, dirs, files in os.walk(base):
            for name in dirs + files:
                st = os.lstat(os.path.join(folder, name))
                inodes[(st.st_dev, st.st_ino)] = prefix + os.path.relpath(os.path.join(folder, name), base)
    out = []
    for line in lines:
        kind, *values = line.split()
        if kind == "OK":
            out.append(inodes.get((int(values[0]), int(values[1])), "proc"))
        else:
            out.append(errno.errorcode[int(values[0])])
    return out


@unittest.skipUnless(shutil.which("cc") and os.path.isdir("/proc/self"), "needs cc and procfs")
class Openat2PreloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp())
        cls.lib = cls.tmp / "libopenat2.so"
        subprocess.run(["cc", "-O2", "-Wall", "-Werror", "-fPIC", "-shared", "-pthread", "-o", str(cls.lib),
                        str(PRELOAD / "robust.c"), str(PRELOAD / "fsync.c"), str(PRELOAD / "noexec.c"), str(PRELOAD / "writewatch.c"), "-ldl"], check=True)
        source = cls.tmp / "openat2.c"
        source.write_text(PROGRAM)
        subprocess.run(["cc", "-O2", "-Wall", "-Werror", "-o", str(cls.tmp / "openat2"), str(source), "-ldl"], check=True)
        cls.trees = (cls.tmp / "kernel", cls.tmp / "preload")
        cls.outside = cls.tmp / "outside"
        make_outside(cls.outside)
        for tree in cls.trees:
            make_tree(tree, cls.outside)
        every = CASES + HOST_CASES
        cases = "".join("%s\t%d\t%s\n" % case[:3] for case in every)
        result = subprocess.run([str(cls.tmp / "openat2"), *map(str, cls.trees)], input=cases, capture_output=True, text=True,
                                env=dict(os.environ, LD_PRELOAD=str(cls.lib)), timeout=60)
        if result.returncode != 0:
            raise AssertionError(result.stdout + result.stderr)
        lines = result.stdout.splitlines()
        cls.kernel = labels(lines[1:1 + len(CASES)], cls.trees[0], cls.outside) if lines[0] == "kernel" else None
        cls.preload = labels(lines[-len(every):], cls.trees[1], cls.outside)
        counter = cls.tmp / "counter.c"
        counter.write_text(COUNTER)
        (cls.tmp / "fp").mkdir()
        subprocess.run(["cc", "-O2", "-Wall", "-Werror", "-fPIC", "-shared", "-o", str(cls.tmp / "fp/libblfastpath.so"),
                        str(counter), "-ldl"], check=True)
        tree = cls.tmp / "counted"
        make_tree(tree, cls.outside)
        counted = subprocess.run([str(cls.tmp / "openat2"), str(tree), str(tree)], input=cases, capture_output=True,
                                 text=True, env=dict(os.environ, LD_PRELOAD="%s:%s" % (cls.lib, cls.tmp / "fp/libblfastpath.so")),
                                 timeout=60)
        if counted.returncode != 0:
            raise AssertionError(counted.stdout + counted.stderr)
        cls.counted = labels(counted.stdout.splitlines()[-len(every):], tree, cls.outside)
        cls.calls = [int(n) for n in counted.stderr.split("calls ")[-1].split()]
        raw = cls.tmp / "raw_open.c"
        raw.write_text(RAW_OPEN)
        subprocess.run(["cc", "-O2", "-Wall", "-Werror", "-o", str(cls.tmp / "raw_open"), str(raw)], check=True)
        cls.raw = subprocess.run([str(cls.tmp / "raw_open"), str(tree / "etc/hostname"), str(tree / "missing")],
                                 capture_output=True, text=True, timeout=60,
                                 env=dict(os.environ, LD_PRELOAD="%s:%s" % (cls.lib, cls.tmp / "fp/libblfastpath.so")))
        lookup = cls.tmp / "raw_lookup.c"
        lookup.write_text(RAW_LOOKUP)
        subprocess.run(["cc", "-O2", "-Wall", "-Werror", "-o", str(cls.tmp / "raw_lookup"), str(lookup)], check=True)
        cls.lookup_args = [str(tree / "etc/hostname"), str(tree / "missing"), str(tree / "etc/alternatives/hn")]
        cls.lookup_plain = subprocess.run([str(cls.tmp / "raw_lookup"), *cls.lookup_args], capture_output=True, text=True,
                                          timeout=60, env=dict(os.environ, LD_PRELOAD=str(cls.lib)))
        cls.lookup = subprocess.run([str(cls.tmp / "raw_lookup"), *cls.lookup_args], capture_output=True, text=True, timeout=60,
                                    env=dict(os.environ, LD_PRELOAD="%s:%s" % (cls.lib, cls.tmp / "fp/libblfastpath.so")))
        (cls.tmp / "real").mkdir()
        subprocess.run(["cc", "-O2", "-Wall", "-Werror", "-fPIC", "-shared", "-pthread", "-o",
                        str(cls.tmp / "real/libblfastpath.so"), str(FASTPATH), "-ldl"], check=True)
        shutil.copy(cls.tmp / "raw_open", cls.tmp / "FEX")
        try:
            cls.fex = subprocess.run([str(cls.tmp / "FEX"), str(tree / "etc/hostname"), str(tree / "missing")],
                                     capture_output=True, text=True, timeout=20,
                                     env=dict(os.environ, PROOT_FP_ROOT="/", PROOT_FP_KEY="test",
                                              LD_PRELOAD="%s:%s" % (cls.lib, cls.tmp / "real/libblfastpath.so")))
        except subprocess.TimeoutExpired:
            cls.fex = None
        wrapper = cls.tmp / "wrapper.c"
        wrapper.write_text(WRAPPER)
        subprocess.run(["cc", "-O2", "-Wall", "-Werror", "-o", str(cls.tmp / "wrapper"), str(wrapper), "-ldl"], check=True)
        (cls.tmp / "extract").mkdir()
        cls.wrapper = subprocess.run([str(cls.tmp / "wrapper"), str(cls.tmp / "extract"), "file"], capture_output=True,
                                     text=True, timeout=20, env=dict(os.environ, LD_PRELOAD=str(cls.lib)))
        (cls.tmp / "fex-lookup").mkdir()
        shutil.copy(cls.tmp / "raw_lookup", cls.tmp / "fex-lookup/FEX")
        try:
            cls.fex_lookup = subprocess.run([str(cls.tmp / "fex-lookup/FEX"), *cls.lookup_args], capture_output=True, text=True,
                                            timeout=20, env=dict(os.environ, PROOT_FP_ROOT="/", PROOT_FP_KEY="test",
                                                                 LD_PRELOAD="%s:%s" % (cls.lib, cls.tmp / "real/libblfastpath.so")))
        except subprocess.TimeoutExpired:
            cls.fex_lookup = None

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def test_preload_resolves_in_the_root_without_openat2(self):
        for case, got in zip(CASES, self.preload):
            self.assertEqual(got, case[3], case)
        self.assertTrue((self.trees[1] / "usr/newfile").exists())

    def test_preload_finishes_a_link_to_a_path_only_outside_the_root_there(self):
        for case, got in zip(HOST_CASES, self.preload[len(CASES):]):
            self.assertEqual(got, case[3], case)

    def test_the_walk_asks_the_fast_path_when_it_is_loaded(self):
        self.assertEqual(self.counted, self.preload)
        self.assertTrue(all(n > 0 for n in self.calls[:3]), self.calls)

    def test_a_raw_openat_syscall_asks_the_fast_path_too(self):
        self.assertEqual(self.raw.returncode, 0, self.raw.stderr)
        self.assertEqual(self.raw.stdout, "inside\n")
        self.assertEqual(self.raw.stderr.split("calls ")[-1].split(), ["2", "0", "0", "0"])

    def test_raw_stat_readlink_and_statx_syscalls_ask_the_fast_path(self):
        self.assertEqual(self.lookup_plain.returncode, 0, self.lookup_plain.stderr)
        self.assertEqual(self.lookup.returncode, 0, self.lookup.stderr)
        self.assertEqual(self.lookup.stdout, self.lookup_plain.stdout)
        self.assertEqual(self.lookup.stderr.split("calls ")[-1].split(), ["0", "2", "1", "2"])

    def test_raw_lookups_in_fex_answer_through_the_real_fast_path(self):
        self.assertIsNotNone(self.fex_lookup, "hung")
        self.assertEqual(self.fex_lookup.returncode, 0, self.fex_lookup.stderr)
        self.assertEqual(self.fex_lookup.stdout, self.lookup_plain.stdout)

    def test_a_first_raw_openat_in_fex_does_not_wait_on_the_fast_path_starting(self):
        self.assertIsNotNone(self.fex, "hung")
        self.assertEqual(self.fex.returncode, 0, self.fex.stderr)
        self.assertEqual(self.fex.stdout, "inside\n")

    def test_the_libc_openat2_wrapper_creates_files_without_openat2(self):
        self.assertEqual(self.wrapper.returncode, 0, self.wrapper.stdout + self.wrapper.stderr)
        self.assertTrue((self.tmp / "extract/file").exists())

    def test_the_kernel_agrees(self):
        if self.kernel is None:
            self.skipTest("this kernel or sandbox has no openat2")
        for case, got in zip(CASES, self.kernel):
            self.assertEqual(got, case[3], case)


if __name__ == "__main__":
    unittest.main()
