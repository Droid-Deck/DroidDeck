from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[2] / "app/src/main/cpp/fakeinput_steam.cpp"

LIBRARY = r"""
#include <sys/statfs.h>
#include <unistd.h>

extern volatile int loading;

__attribute__((constructor)) static void start(void) {
    struct statfs buf;
    loading = 1;
    usleep(300000);
    statfs("/", &buf);
}
"""

PROGRAM = r"""
#define _GNU_SOURCE
#include <dlfcn.h>
#include <pthread.h>
#include <sched.h>
#include <stdio.h>
#include <sys/statfs.h>

volatile int loading;

static void *load(void *path) {
    return dlopen(path, RTLD_NOW);
}

int main(int argc, char **argv) {
    pthread_t loader;
    struct statfs buf;
    pthread_create(&loader, NULL, load, argv[1]);
    while (!loading) sched_yield();
    statfs("/", &buf);
    void *handle = NULL;
    pthread_join(loader, &handle);
    puts(handle ? "ok" : dlerror());
    return 0;
}
"""


@unittest.skipUnless(sys.platform == "linux" and shutil.which("gcc") and shutil.which("g++"),
                     "needs Linux, gcc and g++")
class FakeinputLoaderLockTest(unittest.TestCase):
    def test_a_first_hooked_call_while_another_thread_loads_a_library_does_not_deadlock(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)
            (path / "program.c").write_text(PROGRAM)
            (path / "library.c").write_text(LIBRARY)
            subprocess.run(["gcc", "-rdynamic", "-pthread", "-o", str(path / "program"), str(path / "program.c"),
                            "-ldl"], check=True)
            subprocess.run(["gcc", "-shared", "-fPIC", "-o", str(path / "library.so"), str(path / "library.c")],
                           check=True)
            subprocess.run(["g++", "-shared", "-fPIC", "-O2", "-Wno-attributes", "-pthread",
                            "-std=c++17", "-static-libstdc++", "-static-libgcc",
                            "-Wl,--exclude-libs,ALL", "-o", str(path / "libfakeinput.so"),
                            str(SOURCE), "-ldl"], check=True)
            for _ in range(5):
                result = subprocess.run([str(path / "program"), str(path / "library.so")],
                                        env=dict(os.environ, LD_PRELOAD=str(path / "libfakeinput.so")),
                                        capture_output=True, text=True, timeout=20)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout, "ok\n")


if __name__ == "__main__":
    unittest.main()
