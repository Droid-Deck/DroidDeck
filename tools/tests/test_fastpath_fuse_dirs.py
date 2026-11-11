import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

USERNS = shutil.which("unshare") is not None and subprocess.run(["unshare", "-Urm", "true"], capture_output=True).returncode == 0

FASTPATH = Path(__file__).resolve().parents[1] / "proot/fastpath/fastpath.c"

PROGRAM = r"""
#include "FASTPATH"
#include <sys/mount.h>

static long host_sys(long nr, long a, long b, long c, long d, long e, long f) {
  long r = syscall(nr, a, b, c, d, e, f);
  return r < 0 ? -errno : r;
}

static void show(long r) {
  if (r == FP_SLOW) puts("SLOW");
  else if (r < 0) printf("E%ld\n", -r);
  else puts("ok");
}

static int high_fds(void) {
  int n = 0;
  for (int fd = DIR_FD_FLOOR; fd < DIR_FD_FLOOR + 4 * DIRS; fd++)
    if (fcntl(fd, F_GETFD) >= 0) n++;
  return n;
}

int main(int argc, char **argv) {
  static char hosts[MAX_BINDS][PATH_MAX], guests[MAX_BINDS][512];
  static int taken[DIRS];
  int ntaken = 0;
  fp_sys = host_sys;
  snprintf(root, sizeof root, "%s", argv[1]);
  rootlen = strlen(root);
  int i = 2;
  for (; i + 1 < argc && strcmp(argv[i], "--"); i += 2) {
    struct bind *bd = &binds[nbinds];
    bd->host = hosts[nbinds];
    bd->guest = guests[nbinds];
    snprintf(bd->host, PATH_MAX, "%s", argv[i]);
    snprintf(bd->guest, sizeof guests[0], "%s", argv[i + 1]);
    bd->hlen = strlen(bd->host);
    bd->glen = strlen(bd->guest);
    nbinds++;
  }
  pthread_once(&init_once, fp_init);
  fp_on = 1;
  for (i++; i < argc; i++) {
    const char *op = argv[i];
    if (!strcmp(op, "fuse")) {
      for (int b = 0; b < nbinds; b++) binds[b].fuse = 1;
    } else if (!strcmp(op, "touch")) {
      long fd = fp_open(AT_FDCWD, argv[++i], O_CREAT | O_WRONLY | O_CLOEXEC, 0644);
      if (fd >= 0) close((int)fd);
      show(fd);
    } else if (!strcmp(op, "stat")) {
      struct stat st;
      show(fp_stat(AT_FDCWD, argv[++i], AT_SYMLINK_NOFOLLOW, &st));
    } else if (!strcmp(op, "mkdir")) {
      show(fp_mkdir(AT_FDCWD, argv[++i], 0755));
    } else if (!strcmp(op, "mv")) {
      show(fp_rename(AT_FDCWD, argv[i + 1], AT_FDCWD, argv[i + 2], 0));
      i += 2;
    } else if (!strcmp(op, "rm")) {
      show(fp_unlink(AT_FDCWD, argv[++i], 0));
    } else if (!strcmp(op, "host-mv")) {
      show(host_sys(SYS_renameat, AT_FDCWD, (long)argv[i + 1], AT_FDCWD, (long)argv[i + 2], 0, 0));
      i += 2;
    } else if (!strcmp(op, "host-rmdir")) {
      show(host_sys(SYS_unlinkat, AT_FDCWD, (long)argv[++i], AT_REMOVEDIR, 0, 0, 0));
    } else if (!strcmp(op, "host-mkdir")) {
      show(host_sys(SYS_mkdirat, AT_FDCWD, (long)argv[++i], 0755, 0, 0, 0));
    } else if (!strcmp(op, "clobber")) {
      long null = host_sys(SYS_openat, AT_FDCWD, (long)"/dev/null", O_RDONLY, 0, 0, 0);
      for (int fd = DIR_FD_FLOOR; fd < DIR_FD_FLOOR + DIRS; fd++) host_sys(SYS_dup3, null, fd, 0, 0, 0, 0);
      host_sys(SYS_close, null, 0, 0, 0, 0, 0);
      puts("ok");
    } else if (!strcmp(op, "first-fd")) {
      char link[40], out[PATH_MAX];
      snprintf(link, sizeof link, "/proc/self/fd/%d", DIR_FD_FLOOR);
      long n = host_sys(SYS_readlinkat, AT_FDCWD, (long)link, (long)out, sizeof out - 1, 0, 0);
      out[n > 0 ? n : 0] = 0;
      puts(out);
    } else if (!strcmp(op, "fds")) {
      printf("%d\n", high_fds());
    } else if (!strcmp(op, "ttl")) {
      ttl_ns = atoll(argv[++i]) * 1000000LL;
    } else if (!strcmp(op, "sleep")) {
      usleep(atoi(argv[++i]) * 1000);
    } else if (!strcmp(op, "chmod")) {
      show(fp_chmod(AT_FDCWD, argv[i + 1], strtol(argv[i + 2], NULL, 8), 0));
      i += 2;
    } else if (!strcmp(op, "host-mount")) {
      show(mount("tmpfs", argv[++i], "tmpfs", 0, NULL) == 0 ? 0 : -errno);
    } else if (!strcmp(op, "take-over")) {
      for (int fd = DIR_FD_FLOOR; fd < DIR_FD_FLOOR + DIRS; fd++) {
        char link[40], out[PATH_MAX];
        snprintf(link, sizeof link, "/proc/self/fd/%d", fd);
        long n = host_sys(SYS_readlinkat, AT_FDCWD, (long)link, (long)out, sizeof out - 1, 0, 0);
        if (n <= 0) continue;
        out[n] = 0;
        long mine = host_sys(SYS_openat, AT_FDCWD, (long)out, O_RDONLY | O_DIRECTORY, 0, 0, 0);
        host_sys(SYS_dup3, mine, fd, 0, 0, 0, 0);
        host_sys(SYS_close, mine, 0, 0, 0, 0, 0);
        taken[ntaken++] = fd;
      }
      puts("ok");
    } else if (!strcmp(op, "taken")) {
      int open_fds = 0;
      for (int t = 0; t < ntaken; t++)
        if (lseek(taken[t], 0, SEEK_CUR) == 0) open_fds++;
      printf("%d/%d\n", open_fds, ntaken);
    }
  }
  return 0;
}
"""


class FastPathFuseDirsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if shutil.which("gcc") is None:
            raise unittest.SkipTest("gcc is not available")
        cls.build = tempfile.TemporaryDirectory()
        source = Path(cls.build.name) / "fuse_dirs.c"
        source.write_text(PROGRAM.replace("FASTPATH", str(FASTPATH)))
        cls.program = Path(cls.build.name) / "fuse_dirs"
        subprocess.run(["gcc", "-O1", "-Wall", "-Wextra", "-Werror", "-Wno-format-truncation", "-pthread", "-o", str(cls.program), str(source), "-ldl"], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.build.cleanup()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        base = Path(self.temp.name)
        self.root = base / "root"
        (self.root / "mnt").mkdir(parents=True)
        self.card = base / "card"
        (self.card / "Games/steamapps/common/Game/Data").mkdir(parents=True)

    def ask(self, *ops, wrap=()):
        argv = [*wrap, str(self.program), str(self.root), str(self.card), "/mnt/card", "--", *ops]
        return subprocess.run(argv, capture_output=True, text=True, timeout=20, check=True).stdout.split()

    def test_files_deep_in_a_fuse_library_are_created_renamed_and_removed(self):
        data = "/mnt/card/Games/steamapps/common/Game/Data"
        out = self.ask("fuse", "touch", f"{data}/a.tmp", "mv", f"{data}/a.tmp", f"{data}/a.idx",
                       "stat", f"{data}/a.idx", "stat", f"{data}/a.tmp", "rm", f"{data}/a.idx",
                       "stat", f"{data}/a.idx", "fds")
        self.assertEqual(["ok", "ok", "ok", "E2", "ok", "E2"], out[:-1])
        self.assertGreater(int(out[-1]), 0)
        self.assertEqual([], list((self.card / "Games/steamapps/common/Game/Data").iterdir()))

    def test_a_folder_renamed_behind_the_cache_is_not_reused(self):
        game = self.card / "Games/steamapps/common/Game"
        (game / "Data/keep").write_text("x")
        out = self.ask("fuse", "stat", "/mnt/card/Games/steamapps/common/Game/Data/keep",
                       "host-mv", str(game), str(game.parent / "Moved"),
                       "stat", "/mnt/card/Games/steamapps/common/Game/Data/keep",
                       "stat", "/mnt/card/Games/steamapps/common/Moved/Data/keep")
        self.assertEqual(["ok", "ok", "E2", "ok"], out)

    def test_a_folder_deleted_and_made_again_is_looked_up_fresh(self):
        data = self.card / "Games/steamapps/common/Game/Data"
        out = self.ask("fuse", "touch", "/mnt/card/Games/steamapps/common/Game/Data/old",
                       "rm", "/mnt/card/Games/steamapps/common/Game/Data/old",
                       "host-rmdir", str(data), "host-mkdir", str(data),
                       "touch", "/mnt/card/Games/steamapps/common/Game/Data/new")
        self.assertEqual(["ok"] * 5, out)
        self.assertEqual(["new"], [p.name for p in data.iterdir()])

    def test_folders_deleted_and_made_again_many_times_keep_descriptors_bounded(self):
        data = self.card / "Games/steamapps/common/Game/Data"
        ops = ["fuse"]
        for _ in range(200):
            ops += ["touch", "/mnt/card/Games/steamapps/common/Game/Data/x",
                    "rm", "/mnt/card/Games/steamapps/common/Game/Data/x",
                    "host-rmdir", str(data), "host-mkdir", str(data)]
        out = self.ask(*ops, "fds")
        self.assertEqual(["ok"] * 800, out[:-1])
        self.assertLessEqual(int(out[-1]), 64)

    def test_descriptors_the_program_takes_over_are_left_alone(self):
        data = "/mnt/card/Games/steamapps/common/Game/Data"
        out = self.ask("fuse", "touch", f"{data}/a", "clobber", "touch", f"{data}/b",
                       "stat", f"{data}/a", "first-fd")
        self.assertEqual(["ok", "ok", "ok", "ok", "/dev/null"], out)
        self.assertEqual({"a", "b"}, {p.name for p in (self.card / "Games/steamapps/common/Game/Data").iterdir()})

    def test_a_folder_the_program_opened_itself_stays_open_when_the_cache_moves_on(self):
        data = self.card / "Games/steamapps/common/Game/Data"
        ops = ["fuse", "touch", "/mnt/card/Games/steamapps/common/Game/Data/a", "take-over"]
        for n in range(200):
            (data / f"d{n}").mkdir()
            ops += ["touch", f"/mnt/card/Games/steamapps/common/Game/Data/d{n}/x"]
        out = self.ask(*ops, "taken")
        self.assertEqual(["ok"] * 202, out[:-1])
        opened, total = map(int, out[-1].split("/"))
        self.assertGreater(total, 0)
        self.assertEqual(total, opened)
        self.assertEqual(200, len(list(data.glob("d*/x"))))

    @unittest.skipIf(os.geteuid() == 0, "root passes folder permissions")
    def test_a_parent_folder_closed_by_the_program_stops_cached_access_at_once(self):
        game = self.card / "Games/steamapps/common/Game"
        (game / "Data/keep").write_text("x")
        self.addCleanup(os.chmod, game, 0o755)
        out = self.ask("fuse", "stat", "/mnt/card/Games/steamapps/common/Game/Data/keep",
                       "chmod", "/mnt/card/Games/steamapps/common/Game", "000",
                       "stat", "/mnt/card/Games/steamapps/common/Game/Data/keep",
                       "touch", "/mnt/card/Games/steamapps/common/Game/Data/new")
        self.assertEqual(["ok", "ok"], out[:2])
        self.assertNotIn("ok", out[2:])
        os.chmod(game, 0o755)
        self.assertEqual(["keep"], [p.name for p in (game / "Data").iterdir()])

    @unittest.skipUnless(USERNS, "mount namespaces are not available")
    def test_a_mount_placed_over_a_cached_folder_receives_later_writes(self):
        data = self.card / "Games/steamapps/common/Game/Data"
        out = self.ask("fuse", "ttl", "1", "touch", "/mnt/card/Games/steamapps/common/Game/Data/under",
                       "host-mount", str(data), "sleep", "20",
                       "touch", "/mnt/card/Games/steamapps/common/Game/Data/over",
                       "stat", "/mnt/card/Games/steamapps/common/Game/Data/under", wrap=("unshare", "-Urm"))
        self.assertEqual(["ok", "ok", "ok", "E2"], out)
        self.assertEqual(["under"], [p.name for p in data.iterdir()])

    def test_other_filesystems_keep_absolute_paths_and_open_no_folders(self):
        data = "/mnt/card/Games/steamapps/common/Game/Data"
        out = self.ask("touch", f"{data}/a", "mv", f"{data}/a", f"{data}/b", "rm", f"{data}/b", "fds")
        self.assertEqual(["ok", "ok", "ok", "0"], out)


if __name__ == "__main__":
    unittest.main()
