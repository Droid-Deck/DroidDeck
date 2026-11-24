import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from tools.tests.test_usr2_preload import runner

HOOK_BUILD = Path(__file__).resolve().parents[1] / "nativeguest/build.sh"

MAIN = r"""
package main

import (
	"fmt"
	"probe/quiet"
	"syscall"
)

func main() {
	_, _, errno := syscall.RawSyscall(syscall.SYS_SETREUID, ^uintptr(0), 0, 0)
	euid, _ := quiet.Call(syscall.SYS_GETEUID, 0, 0, 0)
	fmt.Println(syscall.Getuid(), syscall.Getgid(), int(euid), errno == 0)
}
"""

QUIET = """#include "textflag.h"

TEXT ·Call(SB),NOSPLIT,$0-48
\tMOVD\ta1+8(FP), R0
\tMOVD\ta2+16(FP), R1
\tMOVD\ta3+24(FP), R2
\tMOVD\t$0, R3
\tMOVD\t$0, R4
\tMOVD\t$0, R5
\tMOVD\ttrap+0(FP), R8
\tSVC
\tMOVD\tR0, r1+32(FP)
\tMOVD\tR1, r2+40(FP)
\tRET
"""

QUIET_GO = """package quiet

func Call(trap, a1, a2, a3 uintptr) (r1, r2 uintptr)
"""

CGO = """package main

import "C"
"""

LAUNCHER = r"""
#include <unistd.h>

int main(int argc, char **argv) {
  execv(argv[1], argv + 1);
  return 0;
}
"""


class NativeHookGoIdentityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cc, cls.prefix = runner()
        if cls.cc is None:
            raise unittest.SkipTest("no aarch64 compiler and runner")
        if not shutil.which("go"):
            raise unittest.SkipTest("no Go toolchain")
        cls.tmp = tempfile.TemporaryDirectory(dir="/tmp")
        tmp = Path(cls.tmp.name)
        cls.hook = tmp / "hook.so"
        subprocess.run(["bash", str(HOOK_BUILD), str(cls.hook), cls.cc], check=True, capture_output=True)
        (tmp / "launcher.c").write_text(LAUNCHER)
        cls.launcher = tmp / "launcher"
        subprocess.run([cls.cc, "-O1", "-o", str(cls.launcher), str(tmp / "launcher.c")], check=True)
        src = tmp / "src"
        src.mkdir()
        (src / "go.mod").write_text("module probe\n\ngo 1.19\n")
        (src / "main.go").write_text(MAIN)
        (src / "quiet").mkdir()
        (src / "quiet/quiet.go").write_text(QUIET_GO)
        (src / "quiet/quiet_arm64.s").write_text(QUIET)
        (src / "cgo.go").write_text(CGO)
        cls.root = tmp / "root"
        (cls.root / "usr/bin").mkdir(parents=True)
        cls.go_env = {**os.environ, "GOOS": "linux", "GOARCH": "arm64", "GOCACHE": str(tmp / "gocache"),
                      "GOPATH": str(tmp / "gopath"), "GOFLAGS": "-buildvcs=false", "GOTOOLCHAIN": "local"}
        cls.static = cls.root / "usr/bin/go-static"
        subprocess.run(["go", "build", "-o", str(cls.static), "."], cwd=src, check=True, capture_output=True,
                       env={**cls.go_env, "CGO_ENABLED": "0"})
        cls.dynamic = cls.root / "usr/bin/go-dynamic"
        built = subprocess.run(["go", "build", "-o", str(cls.dynamic), "."], cwd=src, capture_output=True,
                               env={**cls.go_env, "CGO_ENABLED": "1", "CC": cls.cc})
        if built.returncode != 0:
            cls.dynamic = None

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def prepare(self, program):
        env = {k: v for k, v in os.environ.items() if k not in ("LD_PRELOAD", "DDNATV_ROOT")}
        hook_env = {"DDNATV_ROOT": str(self.root), "LD_PRELOAD": str(self.hook)}
        if self.prefix:
            cmd = list(self.prefix) + [arg for key, value in hook_env.items() for arg in ("-E", f"{key}={value}")]
        else:
            cmd = []
            env.update(hook_env)
        subprocess.run(cmd + [str(self.launcher), str(program)], env=env, capture_output=True, timeout=120)
        return b"CXGOSYS1" in program.read_bytes()

    def run_go(self, program):
        cmd = list(self.prefix) + [str(program)]
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout.split()

    def check_identity(self, program):
        before = self.run_go(program)
        if os.getuid() != 0:
            self.assertNotEqual(before[0], "0")
            self.assertEqual(before[3], "false")
        self.assertTrue(self.prepare(program))
        self.assertEqual(self.run_go(program), ["0", "0", "0", "true"])
        moved = self.root.with_name("root-away")
        self.root.rename(moved)
        try:
            away = self.run_go(moved / program.relative_to(self.root))
        finally:
            moved.rename(self.root)
        if os.getuid() != 0:
            self.assertEqual(away, before)

    def test_static_go_program_sees_the_session_identity(self):
        self.check_identity(self.static)

    def test_dynamic_go_program_sees_the_session_identity(self):
        if self.dynamic is None:
            self.skipTest("no cgo cross build")
        self.check_identity(self.dynamic)


if __name__ == "__main__":
    unittest.main()
