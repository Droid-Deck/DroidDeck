import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

from tools.tests.test_usr2_preload import runner

HOOK_BUILD = Path(__file__).resolve().parents[1] / "nativeguest/build.sh"

PROBE = r"""
#include <dirent.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

int main(int argc, char **argv) {
  char cwd[4096];
  for (int i = 1; i < argc; i++) {
    struct stat st;
    if (strncmp(argv[i], "env:", 4) == 0) {
      const char *value = getenv(argv[i] + 4);
      printf("%s\t%s\n", argv[i], value ? value : "-");
      continue;
    }
    if (strncmp(argv[i], "parent:", 7) == 0) {
      char dir[4096];
      snprintf(dir, sizeof(dir), "%s", argv[i] + 7);
      char *rel = strchr(dir, '|'), *want = NULL;
      if (rel) *rel++ = 0;
      if (rel && (want = strchr(rel, '|'))) *want++ = 0;
      int fd = open(dir, O_RDONLY | O_DIRECTORY);
      struct stat expected;
      int ok = fd >= 0 && fstatat(fd, rel ? rel : "..", &st, 0) == 0 && stat(want ? want : "/", &expected) == 0;
      printf("%s\t%d\n", argv[i], ok && st.st_dev == expected.st_dev && st.st_ino == expected.st_ino);
      if (fd >= 0) close(fd);
      continue;
    }
    if (strncmp(argv[i], "fork:", 5) == 0) {
      fflush(stdout);
      pid_t pid = fork();
      if (pid == 0) {
        int p[2];
        char path[64], data[8] = "-";
        if (pipe(p) == 0 && write(p[1], "x", 1) == 1 && dup2(p[0], 63) == 63) {
          snprintf(path, sizeof(path), "%s/63", argv[i] + 5);
          int fd = open(path, O_RDONLY);
          ssize_t n = fd >= 0 ? read(fd, data, sizeof(data) - 1) : -1;
          if (n > 0) data[n] = 0;
          if (fd >= 0) close(fd);
        }
        printf("%s\t%s\n", argv[i], data);
        fflush(stdout);
        _exit(0);
      }
      waitpid(pid, NULL, 0);
      continue;
    }
    if (strncmp(argv[i], "cd:", 3) == 0) {
      int ok = chdir(argv[i] + 3) == 0 && getcwd(cwd, sizeof(cwd)) != NULL;
      printf("%s\t%s\n", argv[i], ok ? cwd : "-");
      continue;
    }
    if (strncmp(argv[i], "lstat:", 6) == 0) {
      int ok = lstat(argv[i] + 6, &st) == 0;
      printf("%s\t%d\t%d\n", argv[i], ok, ok && S_ISLNK(st.st_mode));
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
        (cls.root / "deep/er/est").mkdir(parents=True)
        (cls.root / "linked").symlink_to("deep/er/est")
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
        cls.links = tmp / "links"
        (cls.links / "real/sub").mkdir(parents=True)
        (cls.links / "real/sub/file.txt").write_text("deep")
        (cls.links / "real/sub/deeper/more").mkdir(parents=True)
        (cls.links / "real/sub/deeper/more/leaf.txt").write_text("leaf")
        os.symlink("real", cls.links / "dir")
        os.symlink(str(cls.links / "real"), cls.links / "hostdir")
        os.symlink("/etc/hostname", cls.links / "guest")
        os.symlink("real/sub/file.txt", cls.links / "file")
        os.symlink("/mnt/links/real", cls.links / "real/up")
        (cls.root / "pfx/card/dosdevices").mkdir(parents=True)
        os.symlink("/mnt/links", cls.root / "pfx/card/dosdevices/d:")
        cls.eqlen_src = tmp / "eqsrcdir"
        cls.eqlen_dst = "/" + "d" * (len(str(cls.eqlen_src)) - 1)
        eqlen_pfx = cls.eqlen_src / "123/pfx/dosdevices"
        eqlen_pfx.mkdir(parents=True)
        os.symlink(os.path.relpath("/", f"{cls.eqlen_dst}/123/pfx/dosdevices"), eqlen_pfx / "z:")

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def run_probe(self, *args, binds=None, mounts=None, syscalls=False):
        if binds is None:
            binds = f"{self.library}:/mnt/library|{self.private}:/mnt/private|{self.eqlen_src}:{self.eqlen_dst}"
        hook_env = {"DDNATV_ROOT": str(self.root), "DDNATV_BINDS": binds, "LD_PRELOAD": str(self.hook)}
        if mounts is not None:
            table = Path(self.tmp.name) / f"mountinfo-{len(list(Path(self.tmp.name).glob('mountinfo-*')))}"
            table.write_text("".join(f"{i + 20} 1 0:{i + 40} / {point} rw - {kind} {source} rw\n"
                                     for i, (point, kind, source) in enumerate(mounts)))
            hook_env["DDNATV_MOUNTINFO"] = str(table)
        env = {k: v for k, v in os.environ.items() if k not in ("LD_PRELOAD", "LOCPATH", "GCONV_PATH")}
        if self.prefix:
            cmd = list(self.prefix[:1]) + (["-strace"] if syscalls else []) + list(self.prefix[1:])
            cmd += [arg for key, value in hook_env.items() for arg in ("-E", f"{key}={value}")]
        else:
            if syscalls and not shutil.which("strace"):
                self.skipTest("no strace")
            if syscalls:
                cmd = ["strace", "-f", "-qq", "-e", "trace=readlinkat"]
                cmd += [arg for key, value in hook_env.items() for arg in ("-E", f"{key}={value}")]
            else:
                cmd = []
                env.update(hook_env)
        out = subprocess.run(cmd + [str(self.probe), *args], env=env, capture_output=True, text=True, timeout=120,
                             cwd=self.tmp.name)
        self.assertEqual(out.returncode, 0, out.stderr)
        result = {line.split("\t")[0]: line.split("\t")[1:] for line in out.stdout.splitlines()}
        return (result, out.stderr) if syscalls else result

    def readlinks_of(self, log, host):
        return [line for line in log.splitlines()
                if (m := re.search(r'readlinkat\([^,"]*,\s*"([^"]*)"', line)) and m.group(1) == host]

    def test_readlink_lines_match_the_queried_path_only(self):
        host = "/tmp/x/links/guest"
        log = (f'readlinkat(AT_FDCWD, "/proc/self/fd/7", "{host}", 4096) = 18\n'
               f'readlinkat(AT_FDCWD, "{host}", "/etc/hostname", 4095) = 13\n'
               f'123 readlinkat(-100,"{host}",0x7f00,4095) = -1 errno=22\n')
        self.assertEqual(len(self.readlinks_of(log, host)), 2)

    def test_dev_fd_in_a_forked_child_names_the_child_descriptors(self):
        out = self.run_probe("fork:/dev/fd", binds="/proc/self/fd:/dev/fd")
        self.assertEqual(["x"], out["fork:/dev/fd"])

    def test_a_mount_named_through_proc_self_root_reports_its_guest_path(self):
        arg = f"cd:{GAME}/"
        self.assertEqual(self.run_probe(arg, binds=f"/proc/self/root{self.library}:/mnt/library")[arg], [GAME])

    def test_a_proc_mount_that_names_no_process_reports_its_guest_path(self):
        arg = "cd:/mnt/sys/kernel/"
        self.assertEqual(self.run_probe(arg, binds="/proc/self/root/proc/sys:/mnt/sys")[arg], ["/mnt/sys/kernel"])

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

    def test_glibc_locale_and_iconv_data_come_from_the_rootfs(self):
        result = self.run_probe("env:LOCPATH", "env:GCONV_PATH")
        self.assertEqual(result["env:LOCPATH"], [f"{self.root}/usr/lib/locale"])
        self.assertEqual(result["env:GCONV_PATH"], [f"{self.root}/usr/lib/aarch64-linux-gnu/gconv"])

    def test_parent_of_root_stays_at_root(self):
        self.assertEqual(self.run_probe("parent:/")["parent:/"], ["1"])

    def test_parent_of_root_without_mounts_stays_at_root(self):
        self.assertEqual(self.run_probe("parent:/", binds="")["parent:/"], ["1"])

    def test_parent_of_mount_ancestor_is_guest_root(self):
        self.assertEqual(self.run_probe("parent:/mnt")["parent:/mnt"], ["1"])

    def test_parent_walks_from_any_directory_stay_at_root(self):
        cases = ["parent:/etc|../..", "parent:/mnt/library|../../..", "parent:/|../etc/.."]
        self.assertEqual(self.run_probe(*cases), {case: ["1"] for case in cases})

    def test_parent_walk_from_a_linked_mount_ancestor_follows_the_real_directory(self):
        binds = f"{self.library}:/linked/library"
        case = "parent:/linked|../..|/deep"
        self.assertEqual(self.run_probe(case, binds=binds)[case], ["1"])

    def test_absolute_paths_above_root_stay_at_root(self):
        result = self.run_probe("cd:/..", "cd:/etc/../..", "/../etc/hostname")
        self.assertEqual(result["cd:/.."], [str(self.root)])
        self.assertEqual(result["cd:/etc/../.."], [str(self.root)])
        self.assertEqual(result["/../etc/hostname"], ["1", "rootfs"])

    def test_relative_parent_of_cwd_stays_in_guest(self):
        result = self.run_probe("cd:/", "cd:..", "cd:./../..", "../etc/hostname", f"cd:{GAME}", "cd:../..")
        self.assertEqual(result["cd:.."], [str(self.root)])
        self.assertEqual(result["cd:./../.."], [str(self.root)])
        self.assertEqual(result["cd:../.."], [str(self.root / "mnt")])
        self.assertEqual(result["../etc/hostname"], ["1", "rootfs"])

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

    def test_plain_paths_in_a_mount_reach_their_files(self):
        binds = f"{self.links}:/mnt/links"
        paths = ["/mnt/links/real/sub/file.txt", "/mnt/links/real/sub/../sub/file.txt", "/mnt/links//real/sub/file.txt"]
        result = self.run_probe(*paths, binds=binds)
        for path in paths:
            self.assertEqual(result[path], ["1", "deep"], path)

    def test_links_inside_a_mount_are_followed_in_the_guest_view(self):
        binds = f"{self.links}:/mnt/links"
        paths = {"/mnt/links/dir/sub/file.txt": ["1", "deep"], "/mnt/links/hostdir/sub/file.txt": ["1", "deep"],
                 "/mnt/links/file": ["1", "deep"], "/mnt/links/guest": ["1", "rootfs"],
                 "/mnt/links/real/up/sub/file.txt": ["1", "deep"], "/mnt/links/dir/up/up/sub/file.txt": ["1", "deep"]}
        result = self.run_probe(*paths, binds=binds)
        for path, want in paths.items():
            self.assertEqual(result[path], want, path)

    def test_last_link_in_a_mount_is_seen_by_lstat(self):
        binds = f"{self.links}:/mnt/links"
        cases = {"lstat:/mnt/links/guest": ["1", "1"], "lstat:/mnt/links/dir": ["1", "1"],
                 "lstat:/mnt/links/dir/sub/file.txt": ["1", "0"], "lstat:/mnt/links/real/sub": ["1", "0"],
                 "lstat:/mnt/links/dir/missing": ["0", "0"]}
        result = self.run_probe(*cases, binds=binds)
        for case, want in cases.items():
            self.assertEqual(result[case], want, case)

    def test_links_above_missing_folders_in_a_mount_are_still_followed(self):
        binds = f"{self.links}:/mnt/links"
        paths = {"/mnt/links/real/up/sub/deeper/more/leaf.txt": ["1", "leaf"],
                 "/mnt/links/dir/up/sub/deeper/more/leaf.txt": ["1", "leaf"],
                 "/mnt/links/real/up/no/such/folder/file.txt": ["0", "-"],
                 "/mnt/links/dir/up/sub/no/such/file.txt": ["0", "-"]}
        result = self.run_probe(*paths, binds=binds)
        for path, want in paths.items():
            self.assertEqual(result[path], want, path)

    def test_paths_below_missing_folders_in_a_mount_stay_missing(self):
        binds = f"{self.links}:/mnt/links"
        paths = ["/mnt/links/real/sub/no/such/folder/file.txt", "/mnt/links/real/sub/deeper/no/file.txt",
                 "/mnt/links/no/such/folder", "/mnt/links/real/sub/file.txt/more/deeper",
                 "/mnt/links/real/sub/deeper/more/leaf.txt/x/y"]
        result = self.run_probe(*paths, binds=binds)
        for path in paths:
            self.assertEqual(result[path], ["0", "-"], path)
        self.assertEqual(self.run_probe("/mnt/links/real/sub/deeper/more/leaf.txt", binds=binds)["/mnt/links/real/sub/deeper/more/leaf.txt"], ["1", "leaf"])

    def test_missing_paths_in_a_mount_stay_missing(self):
        binds = f"{self.links}:/mnt/links"
        paths = ["/mnt/links/real/missing", "/mnt/links/dir/missing", "/mnt/links/missing/file.txt",
                 "/mnt/links/real/sub/file.txt/more"]
        result = self.run_probe(*paths, binds=binds)
        for path in paths:
            self.assertEqual(result[path], ["0", "-"], path)

    def links_volume(self):
        real = os.path.realpath(self.links)
        return real, os.path.dirname(real), os.path.basename(real)

    def test_links_in_a_mount_on_a_link_capable_filesystem_are_followed(self):
        real, parent, _ = self.links_volume()
        binds = f"{self.links}:/mnt/links"
        paths = {"/mnt/links/guest": ["1", "rootfs"], "/mnt/links/real/up/sub/file.txt": ["1", "deep"],
                 "/mnt/links/dir/sub/file.txt": ["1", "deep"]}
        tables = [[("/", "ext4", "/dev/root")],
                  [("/", "ext4", "/dev/root"), (parent, "vfat", "/dev/sdb1"), (real, "f2fs", "/dev/sda2")],
                  [("/", "ext4", "/dev/root"), (real, "exfat", "/dev/sdb1"), (f"{real}/real", "ext4", "/dev/sda3")],
                  [("/", "ext4", "/dev/root"), (real, "fuse", "/dev/fuse")],
                  [("/", "ext4", "/dev/root"), ("/mnt/media_rw/other", "exfat", "/dev/sdb1"), (real, "fuse", "/dev/fuse")],
                  [("/", "ext4", "/dev/root"), ("/mnt/media_rw/" + os.path.basename(real), "exfat", "/dev/sdb1"),
                   (real, "fuse", "/dev/sdb9")],
                  [("/", "ext4", "/dev/root"), ("/mnt/media_rw/" + os.path.basename(real), "exfat", "/dev/sdb1"),
                   (real, "fuse", "/dev/fuse")],
                  [("/", "ext4", "/dev/root"), (real, "exfat", "/dev/sdb1"),
                   (f"{real}/real", "ext4", "/dev/" + "x" * 5000)],
                  [("/", "ext4", "/dev/root"), (real, "ext4", "/dev/sda2"), (real, "exfat", "/dev/sdb1")]]
        for table in tables:
            result = self.run_probe(*paths, binds=binds, mounts=table)
            for path, want in paths.items():
                self.assertEqual(result[path], want, (path, table))

    def test_mounts_on_filesystems_without_links_skip_the_link_walk(self):
        real, parent, _ = self.links_volume()
        binds = f"{self.links}:/mnt/links"
        tables = [[("/", "ext4", "/dev/root"), (real, "exfat", "/dev/sdb1")],
                  [("/", "ext4", "/dev/root"), (parent, "vfat", "/dev/sdb1")],
                  [("/", "ext4", "/dev/root"), (real, "vfat", "/dev/sdb1"), (f"{real}/real", "exfat", "/dev/sdc1")]]
        for table in tables:
            result = self.run_probe("/mnt/links/guest", "/mnt/links/real/up/sub/file.txt",
                                    "/mnt/links/real/sub/file.txt", "/mnt/links/real/missing",
                                    "lstat:/mnt/links/real/sub", binds=binds, mounts=table)
            self.assertNotEqual(result["/mnt/links/guest"], ["1", "rootfs"], table)
            self.assertEqual(result["/mnt/links/real/up/sub/file.txt"], ["0", "-"], table)
            self.assertEqual(result["/mnt/links/real/sub/file.txt"], ["1", "deep"], table)
            self.assertEqual(result["/mnt/links/real/missing"], ["0", "-"], table)
            self.assertEqual(result["lstat:/mnt/links/real/sub"], ["1", "0"], table)

    def test_a_mount_table_without_the_mount_keeps_the_link_walk(self):
        binds = f"{self.links}:/mnt/links"
        paths = {"/mnt/links/guest": ["1", "rootfs"], "/mnt/links/real/up/sub/file.txt": ["1", "deep"]}
        result = self.run_probe(*paths, binds=binds, mounts=[])
        for path, want in paths.items():
            self.assertEqual(result[path], want, path)

    def test_drive_links_into_mounts_without_links_skip_the_last_link_check(self):
        real, parent, name = self.links_volume()
        binds = f"{self.links}:/mnt/links"
        tables = [[("/", "ext4", "/dev/root"), (real, "exfat", "/dev/sdb1")],
                  [("/", "ext4", "/dev/root"), (f"/mnt/media_rw/{name}", "exfat", "/dev/sdb1"), (real, "fuse", "/dev/fuse")]]
        for table in tables:
            result, log = self.run_probe("/pfx/card/dosdevices/d:/real/sub/file.txt", "/pfx/card/dosdevices/d:/real/missing",
                                         "lstat:/pfx/card/dosdevices/d:/real/sub", binds=binds, mounts=table, syscalls=True)
            self.assertEqual(result["/pfx/card/dosdevices/d:/real/sub/file.txt"], ["1", "deep"], table)
            self.assertEqual(result["/pfx/card/dosdevices/d:/real/missing"], ["0", "-"], table)
            self.assertEqual(result["lstat:/pfx/card/dosdevices/d:/real/sub"], ["1", "0"], table)
            for host in (f"{self.links}/real/sub/file.txt", f"{self.links}/real/missing"):
                self.assertEqual(self.readlinks_of(log, host), [], (host, table))

    def test_drive_links_into_mounts_with_links_still_check_the_last_link(self):
        real, _, _ = self.links_volume()
        binds = f"{self.links}:/mnt/links"
        paths = {"/pfx/card/dosdevices/d:/guest": ["1", "rootfs"], "/pfx/card/dosdevices/d:/file": ["1", "deep"],
                 "/pfx/card/dosdevices/d:/real/sub/file.txt": ["1", "deep"],
                 "/pfx/native/dosdevices/z:/etc/hostname": ["1", "rootfs"]}
        for table in (None, [("/", "ext4", "/dev/root"), (real, "ext4", "/dev/sda2")]):
            result, log = self.run_probe(*paths, binds=binds, mounts=table, syscalls=True)
            for path, want in paths.items():
                self.assertEqual(result[path], want, (path, table))
            self.assertNotEqual(self.readlinks_of(log, f"{self.links}/guest"), [], table)
            self.assertNotEqual(self.readlinks_of(log, f"{self.root}/etc/hostname"), [], table)


if __name__ == "__main__":
    unittest.main()
