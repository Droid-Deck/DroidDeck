import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from tools.tests.test_usr2_preload import runner

HOOK_BUILD = Path(__file__).resolve().parents[1] / "nativeguest/build.sh"

PROBE = r"""
#define _GNU_SOURCE
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

static void show(const char *what, int fd) {
  printf("%s\t", what);
  if (fd < 0) {
    printf("-\n");
    return;
  }
  unsigned char buf[256];
  ssize_t n;
  int any = 0;
  while ((n = read(fd, buf, sizeof(buf))) > 0) {
    for (ssize_t i = 0; i < n; i++) printf("%02x", buf[i]);
    any = 1;
  }
  printf("%s\n", any ? "" : "-");
  close(fd);
}

int main(int argc, char **argv) {
  for (int i = 1; i < argc; i++) {
    if (strncmp(argv[i], "at:", 3) == 0) {
      char *name = strrchr(argv[i], '/');
      *name++ = 0;
      int root = open(argv[i] + 3, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
      int fd = root >= 0 ? openat(root, name, O_RDONLY | O_CLOEXEC) : -1;
      name[-1] = '/';
      show(argv[i], fd);
      if (root >= 0) close(root);
    } else if (strncmp(argv[i], "dup:", 4) == 0) {
      char *name = strrchr(argv[i], '/');
      *name++ = 0;
      int root = open(argv[i] + 4, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
      int copy = root >= 0 ? dup2(root, 50) : -1;
      if (root >= 0) close(root);
      int fd = copy >= 0 ? openat(copy, name, O_RDONLY | O_CLOEXEC) : -1;
      name[-1] = '/';
      show(argv[i], fd);
      if (copy >= 0) close(copy);
    } else if (strncmp(argv[i], "nfat:", 5) == 0 || strncmp(argv[i], "link:", 5) == 0) {
      char *name = strrchr(argv[i], '/');
      *name++ = 0;
      int how = argv[i][0] == 'l' ? O_PATH : O_RDONLY | O_DIRECTORY;
      int root = open(argv[i] + 5, how | O_NOFOLLOW | O_CLOEXEC);
      int fd = root >= 0 ? openat(root, name, O_RDONLY | O_CLOEXEC) : -1;
      name[-1] = '/';
      show(argv[i], fd);
      if (root >= 0) close(root);
    } else if (strncmp(argv[i], "nofollow:", 9) == 0) {
      int fd = open(argv[i] + 9, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
      printf("%s\t%s\n", argv[i], fd >= 0 ? "6f70656e" : "-");
      if (fd >= 0) close(fd);
    } else {
      show(argv[i], open(argv[i], O_RDONLY | O_CLOEXEC));
    }
  }
  return 0;
}
"""

SLEEPER = r"""
#include <fcntl.h>
#include <stdio.h>
#include <unistd.h>
int main(int argc, char **argv) {
  if (argc > 1) open(argv[1], O_RDONLY);
  puts("ready");
  fflush(stdout);
  sleep(60);
  return 0;
}
"""


class NativeHookProcRootTest(unittest.TestCase):
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
        cls.session = tmp / "session"
        (cls.session / "etc").mkdir(parents=True)
        (cls.session / "etc/os-release").write_text("session")
        cls.sandbox = tmp / "sandbox"
        (cls.sandbox / "etc").mkdir(parents=True)
        (cls.sandbox / "etc/os-release").write_text("sandbox")
        (cls.sandbox / ".flatpak-info").write_text("app")
        cls.app = tmp / "app"
        cls.app.mkdir()
        (cls.app / "manifest.json").write_text("bound")
        cls.view = tmp / "view"
        (cls.app / "link").symlink_to(cls.view / "manifest.json")
        cls.info = tmp / "proxy-info"
        cls.info.write_text("proxy")

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def start(self, env, opened=None):
        full = {k: v for k, v in os.environ.items() if k not in ("LD_PRELOAD",) and not k.startswith("DDNATV_")}
        full.update(env)
        cmd = list(self.prefix) + [str(Path(self.tmp.name) / "sleeper")] + ([str(opened)] if opened else [])
        child = subprocess.Popen(cmd, env=full, stdout=subprocess.PIPE, text=True)
        self.addCleanup(child.stdout.close)
        self.addCleanup(child.wait)
        self.addCleanup(child.kill)
        self.assertEqual("ready", child.stdout.readline().strip())
        return child.pid

    def probe(self, *paths, binds="/proc:/proc"):
        hook_env = {"DDNATV_ROOT": str(self.session), "LD_PRELOAD": str(self.hook), "DDNATV_BINDS": binds}
        env = {k: v for k, v in os.environ.items() if k != "LD_PRELOAD" and not k.startswith("DDNATV_")}
        if self.prefix:
            cmd = list(self.prefix) + [arg for key, value in hook_env.items() for arg in ("-E", f"{key}={value}")]
        else:
            cmd = []
            env.update(hook_env)
        out = subprocess.run(cmd + [str(Path(self.tmp.name) / "probe"), *paths], env=env, capture_output=True,
                             text=True, timeout=120)
        self.assertEqual(out.returncode, 0, out.stderr)
        return {name: value if value == "-" else bytes.fromhex(value).decode()
                for name, value in (line.split("\t") for line in out.stdout.splitlines())}

    def sandboxed(self):
        return self.start({"DDNATV_ROOT": str(self.sandbox), "DDNATV_BINDS": f"/proc:/proc|{self.app}:/app"})

    def test_a_sandbox_root_shows_its_own_flatpak_info(self):
        pid = self.sandboxed()
        at, path = f"at:/proc/{pid}/root/.flatpak-info", f"/proc/{pid}/root/.flatpak-info"
        got = self.probe(at, path)
        self.assertEqual("app", got[at])
        self.assertEqual("app", got[path])

    def test_a_sandbox_root_shows_its_files_and_mounts(self):
        pid = self.sandboxed()
        paths = (f"/proc/{pid}/root/etc/os-release", f"/proc/{pid}/root/app/manifest.json",
                 f"at:/proc/{pid}/root/etc/os-release")
        got = self.probe(*paths)
        self.assertEqual(["sandbox", "bound", "sandbox"], [got[p] for p in paths])

    def test_a_program_in_the_session_with_a_bound_flatpak_info_shows_it(self):
        pid = self.start({"DDNATV_ROOT": str(self.session), "DDNATV_BINDS": f"/proc:/proc|{self.info}:/.flatpak-info"})
        at, path = f"at:/proc/{pid}/root/.flatpak-info", f"/proc/{pid}/root/.flatpak-info"
        got = self.probe(at, path)
        self.assertEqual("proxy", got[at])
        self.assertEqual("proxy", got[path])

    def test_a_file_bound_from_an_open_descriptor_is_read(self):
        holder = self.start({}, opened=self.info)
        fd = next(n for n in os.listdir(f"/proc/{holder}/fd") if os.path.realpath(f"/proc/{holder}/fd/{n}") == str(self.info))
        pid = self.start({"DDNATV_ROOT": str(self.session),
                          "DDNATV_BINDS": f"/proc:/proc|/proc/{holder}/fd/{fd}:/.flatpak-info"})
        at = f"at:/proc/{pid}/root/.flatpak-info"
        self.assertEqual("proxy", self.probe(at)[at])

    def test_a_program_in_the_same_session_is_not_a_flatpak(self):
        pid = self.start({"DDNATV_ROOT": str(self.session), "DDNATV_BINDS": "/proc:/proc"})
        at, path, etc = (f"at:/proc/{pid}/root/.flatpak-info", f"/proc/{pid}/root/.flatpak-info",
                         f"/proc/{pid}/root/etc/os-release")
        got = self.probe(at, path, etc)
        self.assertEqual("-", got[at])
        self.assertEqual("-", got[path])
        self.assertEqual("session", got[etc])

    def test_a_program_without_the_hook_is_looked_at_as_the_kernel_shows_it(self):
        pid = self.start({})
        path, at = f"/proc/{pid}/root{self.sandbox}/etc/os-release", f"at:/proc/{pid}/root/.flatpak-info"
        got = self.probe(path, at)
        self.assertEqual("sandbox", got[path])
        self.assertEqual(Path("/.flatpak-info").read_text() if Path("/.flatpak-info").exists() else "-", got[at])

    def test_a_link_is_followed_in_the_sandbox_view(self):
        pid = self.start({"DDNATV_ROOT": str(self.sandbox),
                          "DDNATV_BINDS": f"/proc:/proc|{self.app}:/app|{self.app}:{self.view}"})
        path = f"/proc/{pid}/root/app/link"
        self.assertEqual("bound", self.probe(path)[path])

    def test_a_duplicated_root_descriptor_keeps_the_sandbox_view(self):
        pid = self.sandboxed()
        dup = f"dup:/proc/{pid}/root/.flatpak-info"
        self.assertEqual("app", self.probe(dup)[dup])

    def test_a_bare_root_is_not_followed_with_nofollow(self):
        pid = self.sandboxed()
        bare, below = f"nofollow:/proc/{pid}/root", f"nofollow:/proc/{pid}/root/etc"
        got = self.probe(bare, below)
        self.assertEqual("-", got[bare])
        self.assertEqual("open", got[below])

    def test_a_root_with_a_trailing_slash_keeps_the_sandbox_view_with_nofollow(self):
        pid = self.sandboxed()
        path = f"nfat:/proc/{pid}/root//.flatpak-info"
        self.assertEqual("app", self.probe(path)[path])

    def test_a_descriptor_for_the_root_link_itself_is_not_a_directory(self):
        pid = self.sandboxed()
        path = f"link:/proc/{pid}/root/.flatpak-info"
        self.assertEqual("-", self.probe(path)[path])

    def test_a_sandbox_cannot_be_climbed_out_of(self):
        pid = self.sandboxed()
        path = f"/proc/{pid}/root/../../etc/os-release"
        self.assertEqual("sandbox", self.probe(path)[path])

if __name__ == "__main__":
    unittest.main()
