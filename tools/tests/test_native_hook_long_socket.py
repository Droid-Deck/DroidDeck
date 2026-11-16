import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from tools.tests.test_usr2_preload import runner

ROOT = Path(__file__).resolve().parents[2]
HOOK_BUILD = ROOT / "tools/nativeguest/build.sh"
LISTEN_SRC = ROOT / "app/src/main/cpp/waylandcomp/src/unix_listen.c"
SUN_PATH = 108
GUEST_SOCKET = "/run/droiddeck/wayland-0"

HOOKED = r"""
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

int main(int argc, char **argv) {
  struct sockaddr_un addr = { .sun_family = AF_UNIX };
  strncpy(addr.sun_path, argv[2], sizeof(addr.sun_path) - 1);
  int fd = socket(AF_UNIX, SOCK_STREAM, 0);
  if (strcmp(argv[1], "serve") == 0) {
    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) != 0 || listen(fd, 4) != 0) {
      perror("serve");
      return 1;
    }
    puts("ready");
    fflush(stdout);
    int peer = accept(fd, NULL, NULL);
    char got = 0;
    if (peer < 0 || read(peer, &got, 1) != 1) return 1;
    printf("got %c\n", got);
    return 0;
  }
  if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
    perror("connect");
    return 1;
  }
  return write(fd, "x", 1) == 1 ? 0 : 1;
}
"""

PLAIN_LISTENER = r"""
#include <stdio.h>
#include <unistd.h>
#include <sys/socket.h>
#include "unix_listen.h"

int main(int argc, char **argv) {
  int fd = droiddeck_unix_listen(argv[1], "wayland-0", 4);
  if (fd < 0) {
    perror("listen");
    return 1;
  }
  puts("ready");
  fflush(stdout);
  int peer = accept(fd, NULL, NULL);
  char got = 0;
  if (peer < 0 || read(peer, &got, 1) != 1) return 1;
  printf("got %c\n", got);
  return 0;
}
"""


class LongSocketPathTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cc, cls.prefix = runner()
        if cls.cc is None:
            raise unittest.SkipTest("no aarch64 compiler and runner")
        cls.tmp = tempfile.TemporaryDirectory()
        tmp = Path(cls.tmp.name)
        cls.hook = tmp / "hook.so"
        subprocess.run(["bash", str(HOOK_BUILD), str(cls.hook), cls.cc], check=True, capture_output=True)
        (tmp / "hooked.c").write_text(HOOKED)
        (tmp / "listener.c").write_text(PLAIN_LISTENER)
        subprocess.run([cls.cc, "-O1", "-o", str(tmp / "hooked"), str(tmp / "hooked.c")], check=True)
        subprocess.run([cls.cc, "-O1", "-D_GNU_SOURCE", "-I", str(LISTEN_SRC.parent), "-o", str(tmp / "listener"),
                        str(tmp / "listener.c"), str(LISTEN_SRC)], check=True)
        cls.hooked = tmp / "hooked"
        cls.listener = tmp / "listener"
        cls.root = tmp / "root"
        cls.root.mkdir()

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def runtime_dir(self, long):
        directory = Path(tempfile.mkdtemp(dir=self.tmp.name))
        while long and len(str(directory)) + len("/wayland-0") < SUN_PATH + 10:
            directory = directory / ("e" * 30)
        directory.mkdir(parents=True, exist_ok=True)
        return directory

    def start(self, argv, hooked, directory):
        env = {k: v for k, v in os.environ.items() if k != "LD_PRELOAD"}
        hook_env = {"DDNATV_ROOT": str(self.root), "DDNATV_BINDS": f"{directory}:/run/droiddeck", "LD_PRELOAD": str(self.hook)}
        cmd = list(self.prefix)
        if hooked:
            if self.prefix:
                cmd += [arg for key, value in hook_env.items() for arg in ("-E", f"{key}={value}")]
            else:
                env.update(hook_env)
        return subprocess.Popen(cmd + [str(a) for a in argv], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)

    def exchange(self, directory, server_hooked):
        server = self.start([self.hooked, "serve", GUEST_SOCKET] if server_hooked else [self.listener, directory], server_hooked, directory)
        try:
            self.assertEqual("ready", server.stdout.readline().strip(), server.stderr.read() if server.poll() is not None else "")
            client = self.start([self.hooked, "connect", GUEST_SOCKET], True, directory)
            _, client_err = client.communicate(timeout=30)
            self.assertEqual(0, client.returncode, client_err)
            out, err = server.communicate(timeout=30)
            self.assertEqual(0, server.returncode, err)
            self.assertIn("got x", out)
        finally:
            server.kill()
            server.communicate()

    def test_hooked_client_reaches_a_listener_at_a_path_longer_than_sun_path(self):
        directory = self.runtime_dir(True)
        self.assertGreaterEqual(len(str(directory)) + len("/wayland-0"), SUN_PATH)
        self.exchange(directory, False)

    def test_hooked_server_and_client_share_a_path_longer_than_sun_path(self):
        directory = self.runtime_dir(True)
        self.exchange(directory, True)
        self.assertTrue((directory / "wayland-0").exists())

    def test_a_path_that_fits_is_unchanged(self):
        directory = self.runtime_dir(False)
        self.assertLess(len(str(directory)) + len("/wayland-0"), SUN_PATH)
        self.exchange(directory, True)
        self.assertTrue((directory / "wayland-0").exists())


if __name__ == "__main__":
    unittest.main()
