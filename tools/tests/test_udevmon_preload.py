import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

PRELOAD = Path(__file__).resolve().parents[1] / "linuxfs/preload"

PROGRAM = r"""
#define _GNU_SOURCE
#include <dlfcn.h>
#include <fcntl.h>
#include <linux/netlink.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

int main(int argc, char **argv) {
  unsigned groups = argc > 1 ? (unsigned)atoi(argv[1]) : 2;
  const char *mode = argc > 2 ? argv[2] : "";
  if (!strcmp(mode, "churn")) {
    for (int i = 0; i < 16; i++) {
      int closed = socket(AF_NETLINK, SOCK_RAW, NETLINK_KOBJECT_UEVENT);
      struct sockaddr_nl unsubscribed = {.nl_family = AF_NETLINK};
      if (closed < 0 || bind(closed, (struct sockaddr *)&unsubscribed, sizeof(unsubscribed)) != 0) return 3;
      close(closed);
      if (open("/dev/null", O_RDONLY) != closed) return 4;
    }
  }
  int fd = socket(AF_NETLINK, SOCK_RAW | SOCK_CLOEXEC | SOCK_NONBLOCK, NETLINK_KOBJECT_UEVENT);
  if (fd < 0) { printf("socket failed\n"); return 2; }
  struct sockaddr_nl nl = {.nl_family = AF_NETLINK, .nl_groups = groups};
  printf("bind %d\n", bind(fd, (struct sockaddr *)&nl, sizeof(nl)));
  if (!strcmp(mode, "rebind")) {
    nl.nl_pid = (unsigned)getpid() + 1;
    printf("rebind %d\n", bind(fd, (struct sockaddr *)&nl, sizeof(nl)));
  }
  printf("cloexec %d nonblock %d\n", (fcntl(fd, F_GETFD) & FD_CLOEXEC) != 0, (fcntl(fd, F_GETFL) & O_NONBLOCK) != 0);
  int (*inject)(const char *, const char *, const char *, const char *, unsigned, unsigned) =
      (int (*)(const char *, const char *, const char *, const char *, unsigned, unsigned))dlsym(RTLD_DEFAULT, "dd_udevmon_inject");
  int sent = inject("add", "/devices/droiddeck/usb4/4-1:1.3/0003:054C:09CC.0005/hidraw/hidraw20", "hidraw",
                    "/dev/hidraw20", 245, 20);
  printf("sent %d\n", sent);
  struct pollfd p = {.fd = fd, .events = POLLIN};
  if (sent > 0 && poll(&p, 1, 1000) == 1) {
    char buf[2048];
    struct sockaddr_nl from;
    struct iovec iov = {.iov_base = buf, .iov_len = sizeof(buf)};
    struct msghdr msg = {.msg_name = &from, .msg_namelen = sizeof(from), .msg_iov = &iov, .msg_iovlen = 1};
    ssize_t n = recvmsg(fd, &msg, 0);
    int found = 0;
    for (ssize_t i = 0; n > 0 && i < n; i += (ssize_t)strlen(buf + i) + 1)
      if (!strcmp(buf + i, "ACTION=add") || !strcmp(buf + i, "DEVNAME=/dev/hidraw20")) found++;
    printf("received %s fields %d\n", n > 0 && !memcmp(buf, "libudev", 8) ? "libudev" : "nothing", found);
  }
  return 0;
}
"""


@unittest.skipUnless(shutil.which("gcc") and os.uname().machine == "x86_64", "needs gcc on an x86-64 host")
class UdevMonitorPreloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp())
        cls.library = cls.tmp / "libudevmon.so"
        subprocess.run(["gcc", "-shared", "-fPIC", "-O2", "-Wall", "-Wextra", "-Werror", "-DUDEVMON_TEST", "-DBINFMT_TEST",
                        '-DBINFMT_HANDLER="/bin/false"', "-o", str(cls.library), "-pthread"]
                       + [str(PRELOAD / name) for name in ("binfmt.c", "net.c", "udevmon.c", "pathcache.c", "perms.c", "webhelper.c")]
                       + [str(Path(__file__).with_name("netlink_stubs.c")), "-ldl"], check=True)
        source = cls.tmp / "program.c"
        source.write_text(PROGRAM)
        cls.program = cls.tmp / "program"
        subprocess.run(["gcc", "-O2", "-o", str(cls.program), str(source), "-ldl"], check=True)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, True)

    def run_program(self, *args, refuse=False):
        env = dict(os.environ, LD_PRELOAD=str(self.library))
        env.pop("DD_UDEVMON_REFUSE", None)
        if refuse:
            env["DD_UDEVMON_REFUSE"] = "1"
        return subprocess.run([str(self.program), *args], env=env, capture_output=True, text=True, check=True).stdout.splitlines()

    def test_a_monitor_the_system_will_not_subscribe_hears_the_sessions_events(self):
        out = self.run_program(refuse=True)
        self.assertEqual(["bind 0", "cloexec 1 nonblock 1", "sent 1", "received libudev fields 2"], out)

    def test_a_monitor_the_system_subscribes_stays_on_the_real_socket(self):
        self.assertEqual(["bind 0", "cloexec 1 nonblock 1", "sent 0"], self.run_program())

    def test_a_monitor_bound_without_groups_hears_the_sessions_events(self):
        out = self.run_program("0")
        self.assertEqual(["bind 0", "cloexec 1 nonblock 1", "sent 1", "received libudev fields 2"], out)

    def test_failed_rebind_leaves_the_original_subscription_in_place(self):
        out = self.run_program("2", "rebind")
        self.assertEqual(["bind 0", "rebind -1", "cloexec 1 nonblock 1", "sent 0"], out)

    def test_closed_monitors_do_not_exhaust_stand_in_slots(self):
        out = self.run_program("0", "churn")
        self.assertEqual(["bind 0", "cloexec 1 nonblock 1", "sent 1", "received libudev fields 2"], out)


if __name__ == "__main__":
    unittest.main()
