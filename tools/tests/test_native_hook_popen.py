import os
import shutil
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
        # A dynamic shell, as the rootfs' dash is: the root gets the runner's loader and libc for it.
        libs = Path("/usr/aarch64-linux-gnu/lib") if cls.prefix else Path("/lib/aarch64-linux-gnu")
        loader = Path("/usr/aarch64-linux-gnu/lib/ld-linux-aarch64.so.1") if cls.prefix else Path("/lib/ld-linux-aarch64.so.1")
        if not (libs / "libc.so.6").is_file() or not loader.is_file():
            raise unittest.SkipTest("no aarch64 libc to put in the test root")
        for d in ("lib", "lib/aarch64-linux-gnu", "usr/lib/aarch64-linux-gnu"):
            (cls.root / d).mkdir(parents=True, exist_ok=True)
        shutil.copy2(loader, cls.root / "lib/ld-linux-aarch64.so.1")
        shutil.copy2(libs / "libc.so.6", cls.root / "lib/aarch64-linux-gnu/libc.so.6")
        shutil.copy2(libs / "libc.so.6", cls.root / "usr/lib/aarch64-linux-gnu/libc.so.6")
        subprocess.run([cls.cc, "-O1", "-o", str(cls.root / "bin/sh"), str(tmp / "sh.c")], check=True)
        (tmp / "prog.c").write_text(PROGRAM)
        cls.program = tmp / "prog"
        subprocess.run([cls.cc, "-O1", "-o", str(cls.program), str(tmp / "prog.c")], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def run_program(self, mode):
        hook_env = {"DDNATV_ROOT": str(self.root), "LD_PRELOAD": str(self.hook)}
        env = {k: v for k, v in os.environ.items() if k not in hook_env}
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
