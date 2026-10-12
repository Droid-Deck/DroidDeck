import errno
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

PRELOAD = Path(__file__).resolve().parents[1] / "linuxfs/preload"

PROGRAM = r"""
#define _GNU_SOURCE
#include <errno.h>
#include <spawn.h>
#include <stdio.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

extern char **environ;

int main(int argc, char **argv) {
  if (argc < 3) return 2;
  const char *how = argv[1], *file = argv[2];
  char **args = argv + 2;
  if (!strcmp(how, "access")) {
    int result = access(file, X_OK);
    printf("access %d %d\n", result, result ? errno : 0);
    return 0;
  }
  if (!strcmp(how, "spawn") || !strcmp(how, "spawnp")) {
    pid_t pid;
    int error = how[5] ? posix_spawnp(&pid, file, NULL, NULL, args, environ) : posix_spawn(&pid, file, NULL, NULL, args, environ);
    if (error) { printf("errno %d\n", error); return 3; }
    int status;
    waitpid(pid, &status, 0);
    printf("exit %d\n", WEXITSTATUS(status));
    return 0;
  }
  if (!strcmp(how, "execve")) execve(file, args, environ);
  else if (!strcmp(how, "execv")) execv(file, args);
  else if (!strcmp(how, "execvp")) execvp(file, args);
  else if (!strcmp(how, "execvpe")) execvpe(file, args, environ);
  printf("errno %d\n", errno);
  return 3;
}
"""

# Shell builtins only: anything it ran would be an x86 program, handed back to it.
HANDLER = "#!/bin/sh\nprintf '%s\\n' \"$@\" > \"$HANDLER_LOG\"\nexit 7\n"


@unittest.skipUnless(shutil.which("gcc") and os.uname().machine == "x86_64", "needs gcc on an x86-64 host")
class BinfmtPreloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp())
        cls.handler = cls.tmp / "handler"
        cls.handler.write_text(HANDLER)
        cls.handler.chmod(0o755)
        cls.library = cls.tmp / "libbinfmt.so"
        subprocess.run(["gcc", "-shared", "-fPIC", "-O2", "-Wall", "-Wextra", "-Werror", "-DBINFMT_TEST",
                        '-DBINFMT_HANDLER="%s"' % cls.handler, "-o", str(cls.library),
                        "-pthread"] + [str(PRELOAD / name) for name in ("binfmt.c", "net.c", "udevmon.c", "pathcache.c")] + ["-ldl"],
                       check=True)
        source = cls.tmp / "program.c"
        source.write_text(PROGRAM)
        cls.program = cls.tmp / "program"
        subprocess.run(["gcc", "-O2", "-o", str(cls.program), str(source)], check=True)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, True)

    def setUp(self):
        self.dir = Path(tempfile.mkdtemp(dir=self.tmp))
        self.log = self.dir / "handler.log"

    def file(self, name, data, mode=0o755):
        path = self.dir / name
        path.write_bytes(data)
        path.chmod(mode)
        return path

    def x86(self, name="tool", mode=0o755):
        path = self.dir / name
        shutil.copyfile(shutil.which("true"), path)
        path.chmod(mode)
        return path

    def run_program(self, how, target, *args, desktop=True):
        env = {"PATH": "%s:/usr/bin:/bin" % self.dir, "LD_PRELOAD": str(self.library), "HANDLER_LOG": str(self.log)}
        if desktop:
            env["BL_DESKTOP"] = "1"
        done = subprocess.run([str(self.program), how, str(target)] + list(args), env=env,
                              stdout=subprocess.PIPE, text=True, timeout=30)
        handed = self.log.read_text().splitlines() if self.log.exists() else None
        return done.returncode, done.stdout.strip(), handed

    def test_an_x86_program_goes_to_the_handler_before_it_is_executed(self):
        tool = self.x86()
        for how in ("execve", "execv"):
            self.log.unlink(missing_ok=True)
            self.assertEqual(self.run_program(how, tool, "a", "b c"), (7, "", ["--exec", str(tool), "a", "b c"]))

    def test_outside_the_desktop_nothing_changes(self):
        self.assertEqual(self.run_program("execve", self.x86(), desktop=False), (0, "", None))

    def test_a_program_without_execute_permission_stays_refused(self):
        tool = self.x86(mode=0o644)
        self.assertEqual(self.run_program("execve", tool), (3, "errno %d" % errno.EACCES, None))

    def test_a_native_program_the_kernel_cannot_run_keeps_its_error(self):
        arm64 = bytearray(64)
        arm64[0:4], arm64[4], arm64[5], arm64[18] = b"\x7fELF", 2, 1, 183
        program = self.file("arm64", bytes(arm64))
        self.assertEqual(self.run_program("execve", program), (3, "errno %d" % errno.ENOEXEC, None))

    def test_a_script_runs_as_itself(self):
        script = self.file("script.sh", b"#!/bin/sh\nexit 5\n")
        self.assertEqual(self.run_program("execve", script), (5, "", None))

    def test_a_windows_program_found_on_path_goes_to_the_handler(self):
        program = self.file("setup.exe", b"MZ" + bytes(62))
        for how in ("execvp", "execvpe"):
            self.log.unlink(missing_ok=True)
            self.assertEqual(self.run_program(how, "setup.exe", "/S"), (7, "", ["--exec", str(program), "/S"]))

    def test_a_spawned_x86_program_goes_to_the_handler(self):
        tool = self.x86()
        self.assertEqual(self.run_program("spawn", tool, "x"), (0, "exit 7", ["--exec", str(tool), "x"]))
        self.log.unlink()
        self.assertEqual(self.run_program("spawnp", "tool", "y"), (0, "exit 7", ["--exec", str(tool), "y"]))

    def test_access_keeps_the_kernels_answer_off_noexec_storage(self):
        self.assertEqual(self.run_program("access", self.x86(mode=0o644)), (0, "access -1 %d" % errno.EACCES, None))
        self.assertEqual(self.run_program("access", self.x86()), (0, "access 0 0", None))

    def test_a_program_on_noexec_storage_passes_access(self):
        storage = next((d for d in (os.environ.get("XDG_RUNTIME_DIR"), "/dev/shm", "/run/user/%d" % os.getuid())
                        if d and os.path.isdir(d) and os.access(d, os.W_OK) and os.statvfs(d).f_flag & os.ST_NOEXEC), None)
        if storage is None:
            self.skipTest("no writable noexec mount here")
        folder = Path(tempfile.mkdtemp(dir=storage))
        self.addCleanup(shutil.rmtree, folder, True)
        tool = folder / "tool"
        shutil.copyfile(shutil.which("true"), tool)
        notes = folder / "notes.txt"
        notes.write_text("hello\n")
        self.assertEqual(self.run_program("access", tool), (0, "access 0 0", None))
        self.assertEqual(self.run_program("access", notes), (0, "access -1 %d" % errno.EACCES, None))
        self.assertEqual(self.run_program("access", tool, desktop=False), (0, "access -1 %d" % errno.EACCES, None))

    def test_a_spawned_native_script_is_left_alone(self):
        self.file("script.sh", b"#!/bin/sh\nexit 5\n")
        self.assertEqual(self.run_program("spawnp", "script.sh"), (0, "exit 5", None))


if __name__ == "__main__":
    unittest.main()
