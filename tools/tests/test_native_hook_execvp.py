import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from tools.tests.test_usr2_preload import runner

HOOK_BUILD = Path(__file__).resolve().parents[1] / "nativeguest/build.sh"

RECORDER = r"""
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>

int execve(const char *path, char *const argv[], char *const envp[]) {
  if (access(path, F_OK) != 0) { errno = ENOENT; return -1; }
  if (access(path, X_OK) != 0) { errno = EACCES; return -1; }
  char head[2] = {0};
  int fd = open(path, O_RDONLY);
  if (fd >= 0) { if (read(fd, head, 2) < 0) head[0] = 0; close(fd); }
  if (memcmp(head, "#!", 2) != 0 && memcmp(head, "\177E", 2) != 0) {
    errno = ENOEXEC;
    return -1;
  }
  printf("call");
  for (int i = 0; argv[i]; i++) printf("\t%s", argv[i]);
  printf("\n");
  fflush(stdout);
  _exit(0);
}
"""

PROBE = r"""
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

extern char **environ;

int main(int argc, char **argv) {
  int ret;
  if (strcmp(argv[1], "sys") == 0) {
    ret = (int)syscall(SYS_execve, argv[2], argv + 2, environ);
  } else if (strcmp(argv[1], "sysat") == 0) {
    ret = (int)syscall(SYS_execveat, AT_FDCWD, argv[2], argv + 2, environ, 0);
  } else if (strcmp(argv[1], "sysfd") == 0) {
    ret = (int)syscall(SYS_execveat, open("/", O_RDONLY | O_DIRECTORY), argv[2], argv + 2, environ, 0);
  } else if (strcmp(argv[1], "vpe") == 0) {
    char path[4096];
    snprintf(path, sizeof(path), "PATH=%s", argv[2]);
    char *envp[] = {path, NULL};
    ret = execvpe(argv[3], argv + 3, envp);
  } else {
    ret = execvp(argv[2], argv + 2);
  }
  printf("ret\t%d\t%s\n", ret, strerror(errno));
  return 1;
}
"""


class NativeHookExecvpTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cc, cls.prefix = runner()
        if cls.cc is None:
            raise unittest.SkipTest("no aarch64 compiler and runner")
        cls.tmp = tempfile.TemporaryDirectory(dir="/tmp")
        tmp = Path(cls.tmp.name)
        cls.hook = tmp / "hook.so"
        subprocess.run(["bash", str(HOOK_BUILD), str(cls.hook), cls.cc], check=True, capture_output=True)
        (tmp / "recorder.c").write_text(RECORDER)
        cls.recorder = tmp / "recorder.so"
        subprocess.run([cls.cc, "-shared", "-fPIC", "-O1", "-o", str(cls.recorder), str(tmp / "recorder.c")], check=True)
        (tmp / "probe.c").write_text(PROBE)
        cls.probe = tmp / "probe"
        subprocess.run([cls.cc, "-O1", "-o", str(cls.probe), str(tmp / "probe.c")], check=True)
        cls.root = tmp / "root"
        cls.dirs = Path("/opt/probe")
        for name in ("bin", "opt/probe/locked", "opt/probe/open", "opt/probe/work"):
            (cls.root / name).mkdir(parents=True)
        (tmp / "sh.c").write_text("int main(void) { return 0; }\n")
        subprocess.run([cls.cc, "-static", "-O1", "-o", str(cls.root / "bin/sh"), str(tmp / "sh.c")], check=True)
        cls.script("locked/tool", 0o644)
        cls.script("open/tool", 0o755, "#!/bin/sh\n")
        cls.script("open/plain", 0o755)
        cls.script("work/here", 0o755)

    @classmethod
    def script(cls, name, mode, text="echo plain\n"):
        path = cls.root / "opt/probe" / name
        path.write_text(text)
        path.chmod(mode)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def call(self, *argv, cwd=None, hook=True):
        hook_env = {"DDNATV_ROOT": str(self.root), "LD_PRELOAD": f"{self.hook}:{self.recorder}"} if hook else {}
        env = {k: v for k, v in os.environ.items() if k not in ("LD_PRELOAD", "DDNATV_ROOT")}
        if self.prefix:
            cmd = list(self.prefix) + [arg for key, value in hook_env.items() for arg in ("-E", f"{key}={value}")]
        else:
            cmd = []
            env.update(hook_env)
        out = subprocess.run(cmd + [str(self.probe), *argv], env=env, cwd=cwd, capture_output=True, text=True,
                             timeout=120)
        for line in out.stdout.splitlines():
            if line.startswith("call\t"):
                return line.split("\t")[1:]
            if line.startswith("ret\t"):
                return line.split("\t")[1:]
        self.fail(out.stdout + out.stderr)

    def test_a_script_without_a_shebang_runs_through_sh(self):
        plain = str(self.dirs / "open/plain")
        self.assertEqual(["/bin/sh", plain, "a"], self.call("vp", plain, "a"))

    def test_path_search_runs_a_script_without_a_shebang_through_sh(self):
        plain = str(self.dirs / "open/plain")
        self.assertEqual(["/bin/sh", plain, "a"], self.call("vpe", str(self.dirs / "open"), "plain", "a"))

    def test_a_denied_entry_earlier_in_path_is_skipped(self):
        path = f"{self.dirs / 'locked'}:{self.dirs / 'open'}"
        call = self.call("vpe", path, "tool", "x")
        self.assertTrue(call[-2].endswith("/opt/probe/open/tool"), call)
        self.assertEqual("x", call[-1])

    def test_only_denied_entries_report_permission_denied(self):
        self.assertEqual(["-1", os.strerror(13)], self.call("vpe", str(self.dirs / "locked"), "tool"))

    def test_an_empty_path_entry_means_the_current_directory(self):
        here = self.call("vpe", f"{self.dirs / 'open'}:", "here", cwd=self.root / "opt/probe/work")
        self.assertEqual(["/bin/sh", "here"], here)

    def test_a_bare_name_is_not_run_from_the_current_directory_without_an_empty_entry(self):
        missing = self.call("vpe", str(self.dirs / "open"), "here", cwd=self.root / "opt/probe/work")
        self.assertEqual(["-1", os.strerror(2)], missing)

    def test_a_guest_path_through_the_execve_syscall_is_run_from_the_root(self):
        tool = str(self.dirs / "open/tool")
        for mode in ("sys", "sysat", "sysfd"):
            with self.subTest(mode=mode):
                call = self.call(mode, tool, "x")
                self.assertTrue(call[-2].endswith("/opt/probe/open/tool"), call)
                self.assertEqual("x", call[-1])

    def test_a_missing_guest_path_through_the_execve_syscall_reports_not_found(self):
        for mode in ("sys", "sysat"):
            with self.subTest(mode=mode):
                self.assertEqual(["-1", os.strerror(2)], self.call(mode, str(self.dirs / "open/missing")))

    def test_without_the_hook_the_execve_syscall_does_not_see_the_root(self):
        self.assertEqual(["-1", os.strerror(2)], self.call("sys", str(self.dirs / "open/tool"), hook=False))


if __name__ == "__main__":
    unittest.main()
