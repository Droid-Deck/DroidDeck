import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from tools.tests.test_usr2_preload import runner

HOOK_BUILD = Path(__file__).resolve().parents[1] / "nativeguest/build.sh"

PROBE = r"""
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static void show(const char *name, ssize_t len, const char *buf) {
  printf("%s\t%zd\t%.*s\n", name, len, len > 0 ? (int)len : 0, buf);
}

int main(int argc, char **argv) {
  const char *link = argv[1];
  struct stat st;
  if (lstat(link, &st) != 0) return 2;
  char *exact = malloc((size_t)st.st_size + 1);
  show("sized", readlink(link, exact, (size_t)st.st_size), exact);
  char full[4096];
  show("full", readlink(link, full, sizeof(full)), full);
  char small[4];
  show("small", readlink(link, small, sizeof(small)), small);
  show("sized-at", readlinkat(AT_FDCWD, link, exact, (size_t)st.st_size), exact);
  return 0;
}
"""


class NativeHookReadlinkTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cc, cls.prefix = runner()
        if cls.cc is None:
            raise unittest.SkipTest("no aarch64 compiler and runner")
        cls.tmp = tempfile.TemporaryDirectory(dir="/tmp")
        tmp = Path(cls.tmp.name)
        cls.hook = tmp / "hook.so"
        subprocess.run(["bash", str(HOOK_BUILD), str(cls.hook), cls.cc], check=True, capture_output=True)
        (tmp / "probe.c").write_text(PROBE)
        cls.probe = tmp / "probe"
        subprocess.run([cls.cc, "-O1", "-o", str(cls.probe), str(tmp / "probe.c")], check=True)
        cls.root = tmp / "root"
        (cls.root / "bin").mkdir(parents=True)
        (cls.root / "etc/alternatives").mkdir(parents=True)
        (cls.root / "bin/nano").write_text("")
        os.symlink(cls.root / "bin/nano", cls.root / "etc/alternatives/editor")
        os.symlink("/bin/nano", cls.root / "etc/alternatives/guest")

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def read(self, link):
        hook_env = {"DDNATV_ROOT": str(self.root), "LD_PRELOAD": str(self.hook)}
        env = {k: v for k, v in os.environ.items() if k not in ("LD_PRELOAD", "DDNATV_ROOT")}
        if self.prefix:
            cmd = list(self.prefix) + [arg for key, value in hook_env.items() for arg in ("-E", f"{key}={value}")]
        else:
            cmd = []
            env.update(hook_env)
        out = subprocess.run(cmd + [str(self.probe), link], env=env, capture_output=True, text=True, timeout=120)
        self.assertEqual(0, out.returncode, out.stdout + out.stderr)
        return {line.split("\t")[0]: line.split("\t")[1:] for line in out.stdout.splitlines()}

    def test_a_buffer_sized_from_lstat_gets_the_guest_target(self):
        result = self.read("/etc/alternatives/editor")
        self.assertEqual(["9", "/bin/nano"], result["sized"])
        self.assertEqual(["9", "/bin/nano"], result["sized-at"])
        self.assertEqual(["9", "/bin/nano"], result["full"])

    def test_a_short_buffer_gets_the_start_of_the_guest_target(self):
        self.assertEqual(["4", "/bin"], self.read("/etc/alternatives/editor")["small"])

    def test_a_guest_target_is_unchanged(self):
        result = self.read("/etc/alternatives/guest")
        self.assertEqual(["9", "/bin/nano"], result["sized"])
        self.assertEqual(["4", "/bin"], result["small"])


if __name__ == "__main__":
    unittest.main()
