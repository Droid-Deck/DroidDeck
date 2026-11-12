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
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

int main(int argc, char **argv) {
  char cwd[4096];
  for (int i = 1; i < argc; i++) {
    struct stat st;
    if (strncmp(argv[i], "cd:", 3) == 0) {
      int ok = chdir(argv[i] + 3) == 0 && getcwd(cwd, sizeof(cwd)) != NULL;
      printf("%s\t%s\n", argv[i], ok ? cwd : "-");
      continue;
    }
    int fd = open(argv[i], O_RDONLY);
    char data[64] = "-";
    if (fd >= 0) {
      ssize_t n = read(fd, data, sizeof(data) - 1);
      data[n > 0 ? n : 0] = 0;
      close(fd);
    }
    printf("%s\t%d\t%s\n", argv[i], stat(argv[i], &st) == 0, data);
  }
  return 0;
}
"""

GAME = "/mnt/library/Some Game"


class NativeHookMountTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cc, cls.prefix = runner()
        if cls.cc is None:
            raise unittest.SkipTest("no aarch64 compiler and runner")
        cls.tmp = tempfile.TemporaryDirectory()
        tmp = Path(cls.tmp.name)
        cls.hook = tmp / "hook.so"
        subprocess.run(["bash", str(HOOK_BUILD), str(cls.hook), cls.cc], check=True, capture_output=True)
        (tmp / "probe.c").write_text(PROBE)
        subprocess.run([cls.cc, "-O1", "-o", str(tmp / "probe"), str(tmp / "probe.c")], check=True)
        cls.probe = tmp / "probe"
        cls.root = tmp / "root"
        cls.library = tmp / "storage/PC GAMES"
        (cls.root / "etc").mkdir(parents=True)
        (cls.root / "etc/hostname").write_text("rootfs")
        (cls.root / "mnt/library/Some Game").mkdir(parents=True)
        (cls.root / "mnt/library/Some Game/game.txt").write_text("mount point")
        (cls.library / "Some Game").mkdir(parents=True)
        (cls.library / "Some Game/game.txt").write_text("library")
        for name, target in (("native", str(cls.root)), ("guest", "/")):
            devices = cls.root / "pfx" / name / "dosdevices"
            devices.mkdir(parents=True)
            os.symlink(target, devices / "z:")

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def run_probe(self, *args):
        hook_env = {"DDNATV_ROOT": str(self.root), "DDNATV_BINDS": f"{self.library}:/mnt/library", "LD_PRELOAD": str(self.hook)}
        env = {k: v for k, v in os.environ.items() if k != "LD_PRELOAD"}
        if self.prefix:
            cmd = list(self.prefix) + [arg for key, value in hook_env.items() for arg in ("-E", f"{key}={value}")]
        else:
            cmd = []
            env.update(hook_env)
        out = subprocess.run(cmd + [str(self.probe), *args], env=env, capture_output=True, text=True, timeout=120,
                             cwd=self.tmp.name)
        self.assertEqual(out.returncode, 0, out.stderr)
        return {line.split("\t")[0]: line.split("\t")[1:] for line in out.stdout.splitlines()}

    def test_drive_link_stored_as_rootfs_path_reaches_the_mount(self):
        path = f"/pfx/native/dosdevices/z:{GAME}/game.txt"
        self.assertEqual(self.run_probe(path)[path], ["1", "library"])

    def test_working_directory_through_a_drive_link_is_the_mount(self):
        arg = f"cd:/pfx/native/dosdevices/z:{GAME}/"
        self.assertEqual(self.run_probe(arg)[arg], [GAME])

    def test_rootfs_path_under_a_mount_point_reaches_the_mount(self):
        path = f"{self.root}{GAME}/game.txt"
        self.assertEqual(self.run_probe(path)[path], ["1", "library"])

    def test_guest_drive_link_is_unchanged(self):
        path = f"/pfx/guest/dosdevices/z:{GAME}/game.txt"
        self.assertEqual(self.run_probe(path)[path], ["1", "library"])

    def test_rootfs_paths_outside_mounts_are_unchanged(self):
        paths = ["/pfx/native/dosdevices/z:/etc/hostname", f"{self.root}/etc/hostname", "/etc/hostname"]
        result = self.run_probe(*paths)
        for path in paths:
            self.assertEqual(result[path], ["1", "rootfs"], path)


if __name__ == "__main__":
    unittest.main()
