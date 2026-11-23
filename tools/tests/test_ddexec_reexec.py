import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from tools.tests.test_usr2_preload import runner

REPO = Path(__file__).resolve().parents[2]
SOURCE = REPO / "app/src/main/cpp/ddexec/ddexec.c"
SYSROOT = Path("/usr/aarch64-linux-gnu")

LIBRARY = r"""
int dd_probe_value(void) { return 7; }
"""

HOOK = r"""
#include <unistd.h>

__attribute__((constructor)) static void hooked(void) { write(1, "hooked\n", 7); }
"""

PROGRAM = r"""
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <sys/auxv.h>
#include <sys/syscall.h>

int dd_probe_value(void);

int main(int argc, char **argv, char **envp) {
  if (dd_probe_value() != 7) return 4;
  if (argc > 1 && strcmp(argv[1], "again") == 0) {
    for (int i = 0; i < argc; i++) printf("%s%s", i ? "|" : "", argv[i]);
    printf("\n%s\n", (char *)getauxval(AT_EXECFN));
    return 0;
  }
  char *next[] = {"first", "again", "x y", NULL};
  syscall(SYS_execveat, -100, "/proc/self/exe", next, envp, 0);
  return 3;
}
"""


def ndk_clang():
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or Path.home() / "Android/Sdk")
    for ndk in sorted((sdk / "ndk").glob("*"), reverse=True):
        clang = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
        if clang.exists():
            return str(clang)
    return shutil.which("clang")


class DdexecReexecTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cc, prefix = runner()
        clang = ndk_clang()
        if cc is None or clang is None or not shutil.which("bwrap") or not SYSROOT.is_dir():
            raise unittest.SkipTest("no aarch64 toolchain, runner or bwrap")
        cls.qemu = list(prefix[:3]) if prefix else []
        cls.tmp = tempfile.TemporaryDirectory()
        tmp = Path(cls.tmp.name)
        cls.loader = tmp / "libddexec.so"
        subprocess.run([clang, "--target=aarch64-linux-android29", "-shared", "-fPIC", "-O2", "-ffreestanding",
                        "-fno-builtin", "-fno-stack-protector", "-fno-asynchronous-unwind-tables", "-nostdlib",
                        "-Wl,-e,_start", "-Wl,-z,noexecstack", "-Wl,--no-undefined", "-Wl,-Bsymbolic",
                        "-Wl,-z,max-page-size=16384", "-o", str(cls.loader), str(SOURCE)], check=True)
        cls.libdir = tmp / "own-lib"
        cls.libdir.mkdir()
        (tmp / "lib.c").write_text(LIBRARY)
        subprocess.run([cc, "-shared", "-fPIC", "-O1", "-o", str(cls.libdir / "libddprobe.so"), str(tmp / "lib.c")],
                       check=True)
        (tmp / "prog.c").write_text(PROGRAM)
        cls.program = tmp / "prog"
        subprocess.run([cc, "-O1", "-o", str(cls.program), str(tmp / "prog.c"), "-L", str(cls.libdir), "-lddprobe"],
                       check=True)
        cls.root = tmp / "root"
        (cls.root / "usr/lib").mkdir(parents=True)
        (cls.root / "lib").mkdir()
        shutil.copy2(SYSROOT / "lib/ld-linux-aarch64.so.1", cls.root / "lib/ld-linux-aarch64.so.1")
        (tmp / "hook.c").write_text(HOOK)
        subprocess.run([cc, "-shared", "-fPIC", "-O1", "-o", str(cls.root / "usr/lib/libddnatv-hook.so"), str(tmp / "hook.c")],
                       check=True)
        probe = subprocess.run(["bwrap", "--dev-bind", "/", "/", "true"], capture_output=True)
        if probe.returncode != 0:
            raise unittest.SkipTest("bwrap cannot create a namespace here")

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def env(self, real_exe, library_path=None, root=SYSROOT):
        env = {k: v for k, v in os.environ.items() if not k.startswith(("DDNATV_", "LD_", "QEMU_"))}
        env["DDNATV_ROOT"] = str(root)
        env["LD_LIBRARY_PATH"] = f"{SYSROOT / 'lib'}:{self.libdir}" if library_path is None else str(SYSROOT / "lib")
        if real_exe:
            env["DDNATV_REAL_EXE"] = str(real_exe)
        if library_path:
            env["DDNATV_LIBRARY_PATH"] = library_path
        return env

    def reexec(self, execfn, real_exe, library_path=None, root=SYSROOT):
        jail = ["bwrap", "--dev-bind", "/", "/", "--tmpfs", "/proc"]
        parent = str(Path(execfn).parent)
        jail += ["--dir", parent, "--ro-bind", str(self.loader), execfn]
        start = self.qemu + ["-0", "first", execfn] if self.qemu else ["bash", "-c", 'exec -a first "$0" "$@"', execfn]
        cmd = jail + start + ["again", "x y"]
        return subprocess.run(cmd, env=self.env(real_exe, library_path, root), capture_output=True, text=True, timeout=120)

    def test_reexec_through_proc_self_exe_runs_the_guest_program(self):
        out = self.reexec("/proc/self/exe", self.program)
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertEqual(["first|again|x y", str(self.program)], out.stdout.splitlines())

    def test_reexec_through_thread_self_exe_runs_the_guest_program(self):
        out = self.reexec("/proc/thread-self/exe", self.program)
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertEqual(["first|again|x y", str(self.program)], out.stdout.splitlines())

    def test_reexec_finds_libraries_through_the_recorded_library_path(self):
        out = self.reexec("/proc/self/exe", self.program, f"{self.libdir}:{SYSROOT / 'lib'}")
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertEqual(["first|again|x y", str(self.program)], out.stdout.splitlines())

    def test_reexec_through_ld_so_preloads_the_hook_of_the_root(self):
        out = self.reexec("/proc/self/exe", self.program, f"{self.libdir}:{SYSROOT / 'lib'}", self.root)
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertEqual(["hooked", "first|again|x y", str(self.program)], out.stdout.splitlines())

    def test_reexec_without_a_known_program_fails_cleanly(self):
        out = self.reexec("/proc/self/exe", None)
        self.assertEqual(127, out.returncode)
        self.assertIn("DDNATV_REAL_EXE", out.stderr)

    def test_a_started_program_keeps_its_own_arguments(self):
        ldso = SYSROOT / "lib/ld-linux-aarch64.so.1"
        cmd = self.qemu + [str(self.loader), str(ldso), "ld.so", "--library-path", f"{SYSROOT / 'lib'}:{self.libdir}",
                           str(self.program), "again", "a"]
        out = subprocess.run(cmd, env=self.env(self.program), capture_output=True, text=True, timeout=120)
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertEqual([f"{self.program}|again|a", str(self.program)], out.stdout.splitlines())

    def test_the_usage_check_still_applies_outside_a_reexec(self):
        out = subprocess.run(self.qemu + [str(self.loader), str(self.program)], env=self.env(self.program),
                             capture_output=True, text=True, timeout=120)
        self.assertEqual(127, out.returncode)
        self.assertIn("usage", out.stderr)


if __name__ == "__main__":
    unittest.main()
