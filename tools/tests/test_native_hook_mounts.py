import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from tools.tests.test_usr2_preload import runner

HOOK_BUILD = Path(__file__).resolve().parents[1] / "nativeguest/build.sh"

PROBE = r"""
#include <dirent.h>
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
    if (strncmp(argv[i], "at:", 3) == 0) {
      int dfd = open(argv[i] + 3, O_RDONLY | O_DIRECTORY);
      printf("%s\t%d\t%d\n", argv[i], fstatat(AT_FDCWD, argv[i] + 3, &st, 0) == 0, dfd >= 0);
      if (dfd >= 0) close(dfd);
      continue;
    }
    if (strncmp(argv[i], "ls:", 3) == 0) {
      DIR *dir = opendir(argv[i] + 3);
      char names[1024] = "";
      struct dirent *de;
      while (dir && (de = readdir(dir)) != NULL) {
        if (de->d_name[0] == '.') continue;
        if (names[0]) strncat(names, ",", sizeof(names) - strlen(names) - 1);
        strncat(names, de->d_name, sizeof(names) - strlen(names) - 1);
      }
      if (dir) closedir(dir);
      printf("%s\t%s\n", argv[i], dir ? names : "-");
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
        relinked = cls.root / "pfx/relinked"
        (relinked / "dosdevices").mkdir(parents=True)
        (relinked / "drive_c").mkdir()
        (relinked / "drive_c/c.txt").write_text("drive c")
        os.symlink(os.path.relpath(cls.root, relinked / "dosdevices"), relinked / "dosdevices/z:")
        os.symlink("../drive_c", relinked / "dosdevices/c:")
        copied = cls.root / "pfx/copied/dosdevices"
        copied.mkdir(parents=True)
        os.symlink("../" * 12 + "..", copied / "z:")
        elsewhere = tmp / "elsewhere"
        (elsewhere / "outside/pfx/dosdevices").mkdir(parents=True)
        (elsewhere / "mnt/library/Some Game").mkdir(parents=True)
        (elsewhere / "mnt/library/Some Game/game.txt").write_text("elsewhere")
        os.symlink("../../..", elsewhere / "outside/pfx/dosdevices/z:")
        os.symlink(str(elsewhere / "outside/pfx"), cls.root / "pfx/outside")
        cls.private = tmp / "private"
        private_pfx = cls.private / "123/pfx/dosdevices"
        private_pfx.mkdir(parents=True)
        os.symlink(os.path.relpath("/", "/mnt/private/123/pfx/dosdevices"), private_pfx / "z:")
        private_copy = cls.private / "456/pfx/dosdevices"
        private_copy.mkdir(parents=True)
        os.symlink("../" * 12 + "..", private_copy / "z:")
        cls.eqlen_src = tmp / "eqsrcdir"
        cls.eqlen_dst = "/" + "d" * (len(str(cls.eqlen_src)) - 1)
        eqlen_pfx = cls.eqlen_src / "123/pfx/dosdevices"
        eqlen_pfx.mkdir(parents=True)
        os.symlink(os.path.relpath("/", f"{cls.eqlen_dst}/123/pfx/dosdevices"), eqlen_pfx / "z:")

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def run_probe(self, *args):
        binds = f"{self.library}:/mnt/library|{self.private}:/mnt/private|{self.eqlen_src}:{self.eqlen_dst}"
        hook_env = {"DDNATV_ROOT": str(self.root), "DDNATV_BINDS": binds, "LD_PRELOAD": str(self.hook)}
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

    def test_wine_lookup_steps_reach_the_mount(self):
        for pfx in ("/pfx/native", "/pfx/guest", "/pfx/relinked", f"{self.root}/pfx/native", f"{self.root}/pfx/guest",
                    f"{self.root}/pfx/relinked"):
            drive = f"{pfx}/dosdevices/z:"
            args = [f"at:{drive}{GAME}/", f"at:{drive}{GAME}", f"ls:{drive}/mnt/library", f"ls:{drive}{GAME}"]
            result = self.run_probe(*args)
            self.assertEqual(result[args[0]], ["1", "1"], pfx)
            self.assertEqual(result[args[1]], ["1", "1"], pfx)
            self.assertEqual(result[args[2]], ["Some Game"], pfx)
            self.assertEqual(result[args[3]], ["game.txt"], pfx)

    def test_relative_drive_link_reaches_the_mount(self):
        path = f"/pfx/relinked/dosdevices/z:{GAME}/game.txt"
        cd = f"cd:/pfx/relinked/dosdevices/z:{GAME}/"
        result = self.run_probe(path, cd)
        self.assertEqual(result[path], ["1", "library"])
        self.assertEqual(result[cd], [GAME])

    def test_relative_links_outside_mounts_are_unchanged(self):
        paths = ["/pfx/relinked/dosdevices/z:/etc/hostname", "/pfx/relinked/dosdevices/c:/c.txt",
                 f"{self.root}/pfx/relinked/dosdevices/c:/c.txt"]
        result = self.run_probe(*paths)
        self.assertEqual(result[paths[0]], ["1", "rootfs"])
        self.assertEqual(result[paths[1]], ["1", "drive c"])
        self.assertEqual(result[paths[2]], ["1", "drive c"])

    def test_suffix_after_a_relative_link_is_not_collapsed(self):
        path = "/pfx/relinked/dosdevices/z:/mnt/library/missing/../Some Game/game.txt"
        self.assertEqual(self.run_probe(path)[path], ["0", "-"])

    def test_relative_drive_link_climbing_past_the_root_reaches_the_mount(self):
        path = f"/pfx/copied/dosdevices/z:{GAME}/game.txt"
        cd = f"cd:/pfx/copied/dosdevices/z:{GAME}/"
        result = self.run_probe(path, cd)
        self.assertEqual(result[path], ["1", "library"])
        self.assertEqual(result[cd], [GAME])

    def test_relative_link_climbing_past_the_root_stays_in_the_rootfs(self):
        path = "/pfx/copied/dosdevices/z:/etc/hostname"
        self.assertEqual(self.run_probe(path)[path], ["1", "rootfs"])

    def test_relative_link_is_resolved_from_its_real_directory(self):
        path = f"/pfx/outside/dosdevices/z:{GAME}/game.txt"
        self.assertEqual(self.run_probe(path)[path], ["1", "elsewhere"])

    def test_drive_link_in_a_bound_prefix_reaches_the_mount(self):
        path = f"/mnt/private/123/pfx/dosdevices/z:{GAME}/game.txt"
        cd = f"cd:/mnt/private/123/pfx/dosdevices/z:{GAME}/"
        result = self.run_probe(path, cd)
        self.assertEqual(result[path], ["1", "library"])
        self.assertEqual(result[cd], [GAME])

    def test_drive_link_copied_into_a_bound_prefix_reaches_the_mount(self):
        path = f"/mnt/private/456/pfx/dosdevices/z:{GAME}/game.txt"
        cd = f"cd:/mnt/private/456/pfx/dosdevices/z:{GAME}/"
        result = self.run_probe(path, cd)
        self.assertEqual(result[path], ["1", "library"])
        self.assertEqual(result[cd], [GAME])

    def test_drive_link_in_equal_length_bound_prefix_reaches_the_mount(self):
        path = f"{self.eqlen_dst}/123/pfx/dosdevices/z:{GAME}/game.txt"
        cd = f"cd:{self.eqlen_dst}/123/pfx/dosdevices/z:{GAME}/"
        result = self.run_probe(path, cd)
        self.assertEqual(result[path], ["1", "library"])
        self.assertEqual(result[cd], [GAME])


if __name__ == "__main__":
    unittest.main()
