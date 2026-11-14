from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[2] / "app/src/main/cpp/fakeinput_steam.cpp"

# Stands in for an allocator like mimalloc that calls access() while it starts up (Claude Code
# probes /sys/devices/system/node/node1 on its first allocation, which comes from fakeinput's
# constructor when it is preloaded).
PROGRAM = r"""
#define _GNU_SOURCE
#include <stddef.h>
#include <stdio.h>
#include <unistd.h>

extern void *__libc_malloc(size_t);
static int started;

void *malloc(size_t size) {
    if (!started) {
        started = 1;
        access("/sys/devices/system/node/node1", R_OK);
    }
    return __libc_malloc(size);
}

int main(void) {
    puts("ok");
    return 0;
}
"""


@unittest.skipUnless(sys.platform == "linux" and shutil.which("gcc") and shutil.which("g++"),
                     "needs Linux, gcc and g++")
class FakeinputReentryTest(unittest.TestCase):
    def test_allocator_calling_hooks_during_first_allocation_survives_preload(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)
            (path / "program.c").write_text(PROGRAM)
            subprocess.run(["gcc", "-rdynamic", "-o", str(path / "program"), str(path / "program.c")], check=True)
            subprocess.run(["g++", "-shared", "-fPIC", "-O2", "-Wno-attributes", "-pthread",
                            "-std=c++17", "-static-libstdc++", "-static-libgcc",
                            "-Wl,--exclude-libs,ALL", "-o", str(path / "libfakeinput.so"),
                            str(SOURCE), "-ldl"], check=True)
            result = subprocess.run([str(path / "program")],
                                    env=dict(os.environ, LD_PRELOAD=str(path / "libfakeinput.so")),
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout, "ok\n")


if __name__ == "__main__":
    unittest.main()
