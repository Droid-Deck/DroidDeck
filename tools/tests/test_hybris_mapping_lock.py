"""Compile the exact mapping lock patch and exercise its thread exclusion."""
import ctypes
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
PATCH = ROOT / "tools/hybris/patches/0004-mali-mapping-lock.patch"

PROGRAM = r"""
#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <pthread.h>
#include <sched.h>
#include <stdatomic.h>
#include <stdio.h>
#include <sys/wait.h>
#include <signal.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>
static atomic_int active, violations, ready, completed;
static pthread_t owner;
static void observe(void) {
    if (atomic_load(&active) && !pthread_equal(owner, pthread_self()))
        atomic_fetch_add(&violations, 1);
}
static void *checked_mmap(void *a, size_t n, int p, int f, int d, off_t o) {
    observe(); return (void *)syscall(SYS_mmap, a, n, p, f, d, o);
}
static void *checked_mmap64(void *a, size_t n, int p, int f, int d, off64_t o) {
    observe(); return (void *)syscall(SYS_mmap, a, n, p, f, d, o);
}
static int checked_munmap(void *a, size_t n) {
    observe(); return syscall(SYS_munmap, a, n);
}
#define mmap checked_mmap
#define mmap64 checked_mmap64
#define munmap checked_munmap
#include "mali_mapping.c"
#undef mmap
#undef mmap64
#undef munmap
static void *fork_locker(void *unused) {
    (void)unused;
    assert(!hybris_mali_mapping_begin());
    atomic_store(&ready, 1);
    usleep(30000);
    hybris_mali_mapping_end();
    return NULL;
}
static void *worker(void *unused) {
    (void)unused;
    atomic_store(&ready, 1);
    for (int i = 0; i < 2000; ++i) {
        void *p = i & 1
            ? hybris_mali_mmap(NULL, 4096, PROT_READ|PROT_WRITE,
                              MAP_PRIVATE|MAP_ANONYMOUS, -1, 0)
            : hybris_mali_mmap64(NULL, 4096, PROT_READ|PROT_WRITE,
                                MAP_PRIVATE|MAP_ANONYMOUS, -1, 0);
        assert(p != MAP_FAILED);
        *(volatile unsigned *)p = 0x12345678;
        assert(!hybris_mali_munmap(p, 4096));
    }
    atomic_fetch_add(&completed, 1);
    return NULL;
}
int main(void) {
    owner = pthread_self();
    assert(!hybris_mali_mapping_begin());
    assert(!hybris_mali_mapping_begin());
    atomic_store(&active, 1);
    /* Re-entry is required when preload interposition shares this lock. */
    void *p = hybris_mali_mmap(NULL, 4096, PROT_NONE,
                             MAP_PRIVATE|MAP_ANONYMOUS, -1, 0);
    assert(p != MAP_FAILED);
    assert(!hybris_mali_munmap(p, 4096));
    /* Forking while recursively held preserves the caller's nesting. */
    pid_t child = fork();
    assert(child >= 0);
    if (!child) {
        alarm(3);
        hybris_mali_mapping_end();
        hybris_mali_mapping_end();
        assert(!hybris_mali_mapping_begin());
        hybris_mali_mapping_end();
        _exit(0);
    }
    int status;
    assert(waitpid(child, &status, 0) == child);
    assert(WIFEXITED(status) && WEXITSTATUS(status) == 0);
    pthread_t threads[3];
    for (unsigned i = 0; i < 3; ++i)
        assert(!pthread_create(&threads[i], NULL, worker, NULL));
    while (!atomic_load(&ready)) sched_yield();
    usleep(20000);
    assert(!atomic_load(&completed));
    hybris_mali_mapping_end();
    usleep(20000);
    assert(!atomic_load(&completed));
    atomic_store(&active, 0);
    hybris_mali_mapping_end();
    for (unsigned i = 0; i < 1000; ++i) {
        assert(!hybris_mali_mapping_begin());
        atomic_store(&active, 1);
        sched_yield();
        atomic_store(&active, 0);
        hybris_mali_mapping_end();
    }
    for (unsigned i = 0; i < 3; ++i) assert(!pthread_join(threads[i], NULL));
    assert(atomic_load(&completed) == 3);
    assert(!atomic_load(&violations));
    /* Fork from a different thread cannot inherit an orphaned lock. */
    atomic_store(&ready, 0);
    pthread_t locker;
    assert(!pthread_create(&locker, NULL, fork_locker, NULL));
    while (!atomic_load(&ready)) sched_yield();
    child = fork();
    assert(child >= 0);
    if (!child) {
        alarm(3);
        void *q = hybris_mali_mmap64(NULL, 4096, PROT_NONE,
                                  MAP_PRIVATE|MAP_ANONYMOUS, -1, 0);
        assert(q != MAP_FAILED);
        assert(!hybris_mali_munmap(q, 4096));
        _exit(0);
    }
    assert(waitpid(child, &status, 0) == child);
    assert(WIFEXITED(status) && WEXITSTATUS(status) == 0);
    assert(!pthread_join(locker, NULL));
    errno = 0;
    assert(hybris_mali_mmap(NULL, 0, PROT_NONE,
                          MAP_PRIVATE|MAP_ANONYMOUS, -1, 0) == MAP_FAILED);
    assert(errno == EINVAL);
    errno = 0;
    assert(hybris_mali_mmap64(NULL, 0, PROT_NONE,
                            MAP_PRIVATE|MAP_ANONYMOUS, -1, 0) == MAP_FAILED);
    assert(errno == EINVAL);
    errno = 0;
    assert(hybris_mali_munmap((void *)1, 4096) == -1);
    assert(errno == EINVAL);
    puts("PASS mapping lock: recursive, 6000 concurrent mappings, fork, errno");
    return 0;
}
"""


class HybrisMappingLockTest(unittest.TestCase):
    def checked(self, command):
        done = subprocess.run(command, capture_output=True, text=True, timeout=45)
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        return done

    def test_recursive_lock_and_android_mapping_exclusion(self):
        compiler = shutil.which(os.environ.get("CC", "cc"))
        self.assertIsNotNone(compiler, "a C compiler is required")
        with tempfile.TemporaryDirectory(prefix="droiddeck-mapping-lock-") as temporary:
            root = Path(temporary)
            self.checked(["git", "-C", str(root), "apply",
                          "--include=hybris/common/mali_mapping.*", str(PATCH)])
            source = root / "hybris/common"
            program = root / "test.c"
            program.write_text(PROGRAM)
            binary = root / "mapping-lock-test"
            flags = [compiler, "-std=gnu11", "-Wall", "-Wextra", "-Werror",
                     "-pthread", "-I", str(source)]
            self.checked([*flags, str(program), "-o", str(binary)])
            ran = self.checked([str(binary)])
            self.assertIn("PASS mapping lock:", ran.stdout)
            library = root / "mapping-lock.so"
            self.checked([*flags, "-fPIC", "-shared", "-fvisibility=hidden",
                          "-Wl,--no-undefined", str(source / "mali_mapping.c"),
                          "-o", str(library)])
            loaded = ctypes.CDLL(str(library))
            self.assertEqual(loaded.hybris_mali_mapping_begin(), 0)
            loaded.hybris_mali_mapping_end()

    def test_normal_and_debug_calls_use_same_wrappers(self):
        # HOOK_DIRECT bypasses its wrapper outside debug mode.
        patch = "\n".join(line[1:] for line in PATCH.read_text().splitlines()
                          if line.startswith(("+", " ")) and not line.startswith("+++"))
        for name in ("mmap", "mmap64", "munmap"):
            self.assertIn(f"    HOOK_INDIRECT({name}),", patch)
            self.assertIn(f"return hybris_mali_{name}(", patch)


if __name__ == "__main__":
    unittest.main()
