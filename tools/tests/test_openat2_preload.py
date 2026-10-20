import errno
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

PRELOAD = Path(__file__).resolve().parents[1] / "linuxfs/preload"

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
                        str(PRELOAD / "robust.c"), str(PRELOAD / "fsync.c"), str(PRELOAD / "noexec.c"), "-ldl"], check=True)
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

    def test_the_kernel_agrees(self):
        if self.kernel is None:
            self.skipTest("this kernel or sandbox has no openat2")
        for case, got in zip(CASES, self.kernel):
            self.assertEqual(got, case[3], case)


if __name__ == "__main__":
    unittest.main()
