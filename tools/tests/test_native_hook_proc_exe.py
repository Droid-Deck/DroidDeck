import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest

from tools.tests.test_usr2_preload import runner

HOOK_BUILD = Path(__file__).resolve().parents[1] / "nativeguest/build.sh"

PROBE = r"""
#include <stdio.h>
#include <unistd.h>

int main(int argc, char **argv) {
  for (int i = 1; i < argc; i++) {
    char buf[4096];
    ssize_t n = readlink(argv[i], buf, sizeof(buf) - 1);
    buf[n > 0 ? n : 0] = 0;
    printf("%s\t%s\n", argv[i], n > 0 ? buf : "-");
  }
  return 0;
}
"""

SLEEPER = r"""
#include <unistd.h>
int main(void) { sleep(60); return 0; }
"""


class NativeHookProcExeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cc, cls.prefix = runner()
        if cls.cc is None:
            raise unittest.SkipTest("no aarch64 compiler and runner")
        cls.tmp = tempfile.TemporaryDirectory()
        tmp = Path(cls.tmp.name)
        cls.hook = tmp / "hook.so"
        subprocess.run(["bash", str(HOOK_BUILD), str(cls.hook), cls.cc], check=True, capture_output=True)
        for name, source in (("probe", PROBE), ("sleeper", SLEEPER)):
            (tmp / f"{name}.c").write_text(source)
            subprocess.run([cls.cc, "-O1", "-o", str(tmp / name), str(tmp / f"{name}.c")], check=True)
        cls.root = tmp / "root"
        (cls.root / "usr/bin").mkdir(parents=True)
        # The child's /proc/<pid>/exe must name the loader, as DroidDeck's libddexec.so does.
        cls.loader = tmp / "lib/libddexec.so"
        cls.loader.parent.mkdir()
        if cls.prefix:
            shutil.copy(shutil.which(cls.prefix[0]), cls.loader)
            cls.loader_args = list(cls.prefix[1:])
        else:
            ld = next(p for p in ("/lib/ld-linux-aarch64.so.1", "/lib64/ld-linux-aarch64.so.1") if os.path.exists(p))
            shutil.copy(ld, cls.loader)
            cls.loader_args = []

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def start(self, loader, real_exe=None):
        env = {k: v for k, v in os.environ.items() if k not in ("LD_PRELOAD", "DDNATV_REAL_EXE")}
        if real_exe:
            env["DDNATV_REAL_EXE"] = real_exe
        cmd = ([str(self.loader), *self.loader_args] if loader else list(self.prefix)) + [str(Path(self.tmp.name) / "sleeper")]
        child = subprocess.Popen(cmd, env=env)
        self.addCleanup(child.wait)
        self.addCleanup(child.kill)
        for _ in range(250):
            if b"sleeper" in Path(f"/proc/{child.pid}/cmdline").read_bytes():
                break
            time.sleep(0.02)
        return child.pid

    def readlink(self, *paths):
        hook_env = {"DDNATV_ROOT": str(self.root), "LD_PRELOAD": str(self.hook)}
        env = {k: v for k, v in os.environ.items() if k != "LD_PRELOAD"}
        if self.prefix:
            cmd = list(self.prefix) + [arg for key, value in hook_env.items() for arg in ("-E", f"{key}={value}")]
        else:
            cmd = []
            env.update(hook_env)
        out = subprocess.run(cmd + [str(Path(self.tmp.name) / "probe"), *paths], env=env, capture_output=True,
                             text=True, timeout=120)
        self.assertEqual(out.returncode, 0, out.stderr)
        return {line.split("\t")[0]: line.split("\t")[1] for line in out.stdout.splitlines()}

    def test_another_guest_program_names_the_program_its_loader_runs(self):
        pid = self.start(loader=True, real_exe=f"{self.root}/usr/bin/kwin_wayland")
        link = f"/proc/{pid}/exe"
        self.assertEqual("/usr/bin/kwin_wayland", self.readlink(link)[link])

    def test_a_process_not_started_by_the_loader_is_unchanged(self):
        pid = self.start(loader=False, real_exe=f"{self.root}/usr/bin/kwin_wayland")
        link = f"/proc/{pid}/exe"
        self.assertNotEqual("/usr/bin/kwin_wayland", self.readlink(link)[link])

    def test_a_loader_without_a_known_program_keeps_the_kernel_link(self):
        pid = self.start(loader=True)
        link = f"/proc/{pid}/exe"
        self.assertEqual(os.readlink(link), self.readlink(link)[link])


if __name__ == "__main__":
    unittest.main()
