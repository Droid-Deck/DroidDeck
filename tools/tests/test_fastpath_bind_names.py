import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

FASTPATH = Path(__file__).resolve().parents[1] / "proot/fastpath/fastpath.c"

PROGRAM = r"""
#include "FASTPATH"

static long host_sys(long nr, long a, long b, long c, long d, long e, long f) {
  long r = syscall(nr, a, b, c, d, e, f);
  return r < 0 ? -errno : r;
}

int main(int argc, char **argv) {
  static char hosts[MAX_BINDS][PATH_MAX], guests[MAX_BINDS][512];
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
    char text[PATH_MAX];
    if (!strcmp(argv[i], "name")) {
      if (guest_text(argv[++i], text) < 0) strcpy(text, "SLOW");
      puts(text);
    } else if (!strcmp(argv[i], "link")) {
      snprintf(text, sizeof text, "%s", argv[i + 2]);
      if (detranslated(text, argv[i + 1]) == FP_SLOW) strcpy(text, "SLOW");
      i += 2;
      puts(text);
    } else if (!strcmp(argv[i], "read")) {
      long fd = fp_open(AT_FDCWD, argv[++i], O_RDONLY, 0);
      if (fd == FP_SLOW) puts("SLOW");
      else if (fd < 0) printf("E%ld\n", -fd);
      else {
        char data[64] = "";
        ssize_t n = read((int)fd, data, sizeof data - 1);
        close((int)fd);
        data[n > 0 ? n : 0] = 0;
        puts(data);
      }
    } else if (!strcmp(argv[i], "chown")) {
      int flags = !strcmp(argv[i + 1], "nofollow") ? AT_SYMLINK_NOFOLLOW : 0;
      long r = fp_chown(AT_FDCWD, argv[i + 2], getuid(), getgid(), flags);
      i += 2;
      if (r == FP_SLOW) puts("SLOW");
      else if (r < 0) printf("E%ld\n", -r);
      else puts("ok");
    } else if (!strcmp(argv[i], "lstat")) {
      struct stat st;
      long r = fstatat(AT_FDCWD, argv[++i], &st, AT_SYMLINK_NOFOLLOW);
      puts(r < 0 ? "error" : S_ISDIR(st.st_mode) ? "dir" : S_ISLNK(st.st_mode) ? "link" : "other");
    }
  }
  return 0;
}
"""


class FastPathBindNamesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if shutil.which("gcc") is None:
            raise unittest.SkipTest("gcc is not available")
        cls.work = Path(tempfile.mkdtemp())
        source = cls.work / "names.c"
        source.write_text(PROGRAM.replace("FASTPATH", str(FASTPATH)))
        cls.program = cls.work / "names"
        subprocess.run(["gcc", "-O1", "-w", "-pthread", "-o", str(cls.program), str(source), "-ldl"], check=True)
        t = cls.work / "tree"
        for d in ["root/etc", "root/mnt", "root/opt", "root/d/real", "lib/common/game", "fonts", "a", "b",
                  "root/sys/class/thermal", "root/sys/devices/zone28", "bound"]:
            (t / d).mkdir(parents=True, exist_ok=True)
        for f in ["root/etc/x", "lib/f", "lib/common/game/f", "fonts/f", "a/f", "b/f"]:
            (t / f).touch()
        os.symlink(t / "lib/f", t / "lib/l2")
        os.symlink(t / "lib/common", t / "lib/l")
        os.symlink("real", t / "root/d/link")
        os.symlink("../../devices/zone28", t / "root/sys/class/thermal/zone28")
        (t / "bound/temp").write_text("bound")
        (t / "root/sys/devices/zone28/other").write_text("other")
        cls.tree = t
        cls.binds = [
            (t / "lib", "/mnt/droiddeck-sd"),
            (t / "lib", "/mnt/bannerlator-sd"),
            (t / "lib", "/mnt/DroidDeck-SD"),
            (t / "lib/common", "/opt/common"),
            (t / "fonts", "/usr/share/fonts/android"),
            (t / "a", "/opt/g"),
            (t / "b", "/opt/g"),
            (t / "fonts", "/mnt/x"),
            (t / "bound/temp", "/sys/class/thermal/zone28/temp"),
            (t / "bound/temp", "/dev/stdin/x"),
        ]

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.work, ignore_errors=True)

    def ask(self, *queries):
        argv = [str(self.program), str(self.tree / "root")]
        for host, guest in self.binds:
            argv += [str(host), guest]
        argv.append("--")
        for q in queries:
            argv += [str(x) for x in q]
        out = subprocess.run(argv, capture_output=True, text=True, timeout=20, check=True).stdout
        return out.splitlines()

    def test_a_host_file_takes_the_name_proot_gives_it(self):
        t = self.tree
        self.assertEqual(self.ask(
            ("name", t / "lib/f"),
            ("name", t / "lib/common/game/f"),
            ("name", t / "fonts/f"),
            ("name", t / "b/f"),
            ("name", t / "a/f"),
            ("name", t / "root/etc/x"),
        ), [
            "/mnt/droiddeck-sd/f",
            "/opt/common/game/f",
            "/usr/share/fonts/android/f",
            "/opt/g/f",
            str(t / "a/f"),
            "/etc/x",
        ])

    def test_a_link_inside_a_library_reads_back_like_proot(self):
        t = self.tree
        self.assertEqual(self.ask(
            ("link", t / "lib/l2", t / "lib/f"),
            ("link", t / "lib/l", t / "lib/common"),
        ), [
            "/mnt/droiddeck-sd/f",
            str(t / "lib/common"),
        ])

    def test_a_final_dot_follows_the_link(self):
        self.assertEqual(self.ask(
            ("lstat", "/d/link"),
            ("lstat", "/d/link/."),
            ("lstat", "/d/link/./"),
            ("lstat", "/d/link/"),
        ), ["link", "dir", "dir", "dir"])

    def test_a_bound_file_under_a_link_is_found_by_its_spelled_name(self):
        self.assertEqual(self.ask(
            ("read", "/sys/class/thermal/zone28/temp"),
            ("read", "//sys/class/thermal/./zone28//temp"),
            ("read", "/sys/class/thermal/zone28/other"),
            ("read", "/sys/class/thermal/zone28/temp/"),
            ("read", "/sys/class/thermal/zone28/../zone28/temp"),
            ("read", "/sys/class/thermal/zone28/missing"),
            ("read", "/dev/stdin/x"),
        ), ["bound", "bound", "SLOW", "SLOW", "SLOW", "SLOW", "SLOW"])

    def test_chown_answers_like_the_kernel(self):
        t = self.tree
        self.assertEqual(self.ask(
            ("chown", "follow", "/etc/x"),
            ("chown", "follow", "/d/link"),
            ("chown", "nofollow", "/d/link"),
            ("chown", "follow", "/nope"),
            ("chown", "follow", "/etc/x/y"),
            ("chown", "follow", "/mnt/droiddeck-sd/f"),
            ("chown", "follow", "/sys/class/thermal/zone28/temp"),
            ("chown", "nofollow", "/sys/class/thermal/zone28/temp"),
            ("chown", "follow", "/sys/class/thermal/zone28/other"),
        ), ["ok", "ok", "ok", "E2", "E20", "ok", "ok", "ok", "SLOW"])


if __name__ == "__main__":
    unittest.main()
