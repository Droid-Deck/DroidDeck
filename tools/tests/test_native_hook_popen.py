import os
import subprocess
import tempfile
import unittest
from pathlib import Path

from tools.tests.test_usr2_preload import runner

HOOK_BUILD = Path(__file__).resolve().parents[1] / "nativeguest/build.sh"

# The rootfs' /bin/sh for these tests: "sh -c 'echo WORDS'" prints WORDS, "sh -c 'exit N'" exits N.
SHELL = r"""
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main(int argc, char **argv) {
  if (argc < 3 || strcmp(argv[1], "-c") != 0) return 2;
  if (strncmp(argv[2], "echo ", 5) == 0) {
    puts(argv[2] + 5);
    return 0;
  }
  if (strncmp(argv[2], "exit ", 5) == 0) return atoi(argv[2] + 5);
  return 127;
}
"""

# posix_spawn() of argv[1] -c "exit 3", without the hook: prints the exit status it got
SPAWN_PROBE = r"""
#include <spawn.h>
#include <stdio.h>
#include <sys/wait.h>

extern char **environ;

int main(int argc, char **argv) {
  pid_t pid;
  char *args[] = {"sh", "-c", "exit 3", NULL};
  int status = 0;
  if (posix_spawn(&pid, argv[1], NULL, NULL, args, environ) != 0 || waitpid(pid, &status, 0) != pid) return 1;
  printf("%d\n", WIFEXITED(status) ? WEXITSTATUS(status) : -1);
  return 0;
}
"""

# popen() and system() through the hook (which starts the shell with posix_spawn())
PROGRAM = r"""
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>

int main(int argc, char **argv) {
  if (strcmp(argv[1], "system") == 0) {
    int status = system("exit 3");
    printf("system\t%d\n", WIFEXITED(status) ? WEXITSTATUS(status) : -1);
    return 0;
  }
  // a second stream open while the first is read: the shell of the first must not hold it
  FILE *other = popen("echo other", "r");
  FILE *f = popen("echo from the shell", "r");
  char line[64] = "";
  if (f && !fgets(line, sizeof(line), f)) line[0] = 0;
  line[strcspn(line, "\n")] = 0;
  int status = f ? pclose(f) : -1;
  if (other) pclose(other);
  printf("popen\t%s\n", line);
  printf("pclose\t%d\n", WIFEXITED(status) ? WEXITSTATUS(status) : -1);
  return 0;
}
"""


class NativeHookPopenTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cc, cls.prefix = runner()
        if cls.cc is None:
            raise unittest.SkipTest("no aarch64 compiler and runner")
        cls.tmp = tempfile.TemporaryDirectory(dir="/tmp")
        tmp = Path(cls.tmp.name)
        cls.hook = tmp / "hook.so"
        subprocess.run(["bash", str(HOOK_BUILD), str(cls.hook), cls.cc], check=True, capture_output=True)
        cls.root = tmp / "root"
        (cls.root / "bin").mkdir(parents=True)
        (tmp / "sh.c").write_text(SHELL)
        subprocess.run([cls.cc, "-static", "-O1", "-o", str(cls.root / "bin/sh"), str(tmp / "sh.c")], check=True)
        # posix_spawn()'s child execs the shell itself. Under qemu-user that exec works only where the
        # kernel runs aarch64 programs (binfmt_misc), which a plain x86 runner does not.
        (tmp / "spawn.c").write_text(SPAWN_PROBE)
        subprocess.run([cls.cc, "-static", "-O1", "-o", str(tmp / "spawn"), str(tmp / "spawn.c")], check=True)
        probe = subprocess.run(list(cls.prefix or []) + [str(tmp / "spawn"), str(cls.root / "bin/sh")],
                               capture_output=True, text=True, timeout=60)
        if probe.stdout.strip() != "3":
            cls.tmp.cleanup()
            raise unittest.SkipTest("this runner cannot exec an aarch64 program from a posix_spawn() child")
        (tmp / "prog.c").write_text(PROGRAM)
        cls.program = tmp / "prog"
        subprocess.run([cls.cc, "-O1", "-o", str(cls.program), str(tmp / "prog.c")], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def run_program(self, mode):
        hook_env = {"DDNATV_ROOT": str(self.root), "LD_PRELOAD": str(self.hook)}
        # Only what the program needs: other test modules in the same run change os.environ
        env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin")}
        if self.prefix:
            cmd = list(self.prefix) + [arg for key, value in hook_env.items() for arg in ("-E", f"{key}={value}")]
        else:
            cmd = []
            env.update(hook_env)
        out = subprocess.run(cmd + [str(self.program), mode], env=env, capture_output=True, text=True, timeout=60)
        self.assertEqual(0, out.returncode, out.stderr)
        return dict(line.split("\t", 1) for line in out.stdout.splitlines() if "\t" in line), out

    def test_popen_reads_the_shells_output(self):
        result, out = self.run_program("popen")
        self.assertEqual("from the shell", result.get("popen"), out.stdout + out.stderr)
        self.assertEqual("0", result.get("pclose"), out.stdout)

    def test_system_returns_the_shells_status(self):
        result, out = self.run_program("system")
        self.assertEqual("3", result.get("system"), out.stdout + out.stderr)


if __name__ == "__main__":
    unittest.main()
