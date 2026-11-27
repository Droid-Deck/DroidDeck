import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from tools.tests.test_usr2_preload import runner

HOOK_BUILD = Path(__file__).resolve().parents[1] / "nativeguest/build.sh"

RECORDER = r"""
#include <stdio.h>
#include <string.h>
#include <unistd.h>

int execve(const char *path, char *const argv[], char *const envp[]) {
  const char *exe = "-";
  for (int i = 0; envp && envp[i]; i++)
    if (strncmp(envp[i], "DDNATV_REAL_EXE=", 16) == 0) exe = envp[i] + 16;
  printf("exe\t%s\n", exe);
  fflush(stdout);
  _exit(0);
}
"""

PROBE = r"""
#include <stdio.h>
#include <unistd.h>

int main(int argc, char **argv) {
  if (argc < 2) return 0;
  char *args[] = {argv[1], NULL};
  execv(argv[1], args);
  perror("execv");
  return 1;
}
"""


class NativeHookExecTargetTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cc, cls.prefix = runner()
        if cls.cc is None:
            raise unittest.SkipTest("no aarch64 compiler and runner")
        cls.tmp = tempfile.TemporaryDirectory()
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
        opt = cls.root / "opt"
        (opt / "real").mkdir(parents=True)
        (opt / "nest").mkdir()
        (cls.root / "lib").mkdir()
        (cls.root / "lib/ld-linux-aarch64.so.1").write_bytes(b"")
        shutil.copy(cls.probe, opt / "real/prog")
        shutil.copy(cls.probe, opt / "real/.l2s.prog0001")
        os.symlink("./real", opt / "rel")
        os.symlink("/opt/real", opt / "abs")
        os.symlink("../rel", opt / "nest/sub")
        os.symlink("rel/prog", opt / "link")
        os.symlink(".l2s.prog0001", opt / "real/hard")
        cls.real = str(opt / "real/prog")

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def exe(self, program):
        hook_env = {"DDNATV_ROOT": str(self.root), "LD_PRELOAD": f"{self.hook}:{self.recorder}"}
        env = {k: v for k, v in os.environ.items() if k not in ("LD_PRELOAD", "DDNATV_ROOT", "DDNATV_REAL_EXE")}
        if self.prefix:
            cmd = list(self.prefix) + [arg for key, value in hook_env.items() for arg in ("-E", f"{key}={value}")]
        else:
            cmd = []
            env.update(hook_env)
        out = subprocess.run(cmd + [str(self.probe), program], env=env, capture_output=True, text=True, timeout=120)
        for line in out.stdout.splitlines():
            if line.startswith("exe\t"):
                return line.split("\t", 1)[1]
        self.fail(out.stdout + out.stderr)

    def test_a_program_reached_through_a_relative_directory_link_is_named_by_its_real_path(self):
        self.assertEqual(self.real, self.exe("/opt/rel/prog"))

    def test_a_program_reached_through_a_guest_absolute_directory_link_is_named_by_its_real_path(self):
        self.assertEqual(self.real, self.exe("/opt/abs/prog"))

    def test_links_that_climb_with_dot_dot_resolve_like_the_kernel(self):
        self.assertEqual(self.real, self.exe("/opt/nest/sub/./prog"))

    def test_a_link_to_the_program_itself_still_resolves(self):
        self.assertEqual(self.real, self.exe("/opt/link"))

    def test_a_plain_path_is_unchanged(self):
        self.assertEqual(self.real, self.exe("/opt/real/prog"))

    def test_a_missing_directory_is_not_skipped_by_dot_dot(self):
        self.assertNotEqual(self.real, self.exe("/opt/missing/../real/prog"))

    def test_a_file_is_not_used_as_a_directory(self):
        self.assertNotEqual(self.real, self.exe("/opt/real/prog/../prog"))

    def test_a_trailing_slash_after_a_program_is_not_dropped(self):
        self.assertNotEqual(self.real, self.exe("/opt/real/prog/"))

    def test_a_trailing_slash_after_a_link_to_a_program_is_not_dropped(self):
        self.assertNotEqual(self.real, self.exe("/opt/link/"))

    def test_a_proot_hard_link_keeps_its_own_name(self):
        self.assertEqual(str(self.root / "opt/real/hard"), self.exe("/opt/real/hard"))


if __name__ == "__main__":
    unittest.main()
