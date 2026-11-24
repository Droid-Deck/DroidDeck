import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

from tools.tests.test_usr2_preload import runner

REPO = Path(__file__).resolve().parents[2]
PATCH = REPO / "tools/fex/patches/0018-socket-credentials-through-libc.patch"
HOOK = REPO / "tools/nativeguest/hook/privileged.c"
HOOK_BUILD = REPO / "tools/nativeguest/build.sh"
PASSTHROUGH = "Source/Tools/LinuxEmulation/LinuxSyscalls/Syscalls/Passthrough.cpp"
REGISTER = re.compile(r"REGISTER_SYSCALL_IMPL_X64\((\w+), (\w+?)(\d)<SYSCALL_DEF\((\w+)\)>\);")
ROUTED = {"sendmsg", "recvmsg", "getsockopt"}


def changed():
    removed, added = {}, {}
    for line in PATCH.read_text().splitlines():
        found = REGISTER.search(line)
        if found and line[:1] in "+-":
            (added if line[0] == "+" else removed)[found.group(1)] = found.groups()[1:]
    return removed, added


class FexSocketCredentialsTest(unittest.TestCase):
    def test_only_the_passthrough_table_changes(self):
        self.assertEqual([PASSTHROUGH], re.findall(r"^diff --git a/(\S+) ", PATCH.read_text(), re.M))

    def test_each_socket_call_keeps_its_arity_and_goes_through_libc(self):
        removed, added = changed()
        self.assertEqual(ROUTED, set(removed))
        self.assertEqual(ROUTED, set(added))
        for name, (template, arity, number) in added.items():
            with self.subTest(name=name):
                self.assertEqual(name, number)
                self.assertEqual(("SyscallPassthrough", arity, number), removed[name])
                self.assertEqual("SyscallPathPassthrough", template)

    def test_every_routed_call_is_answered_by_the_hook(self):
        hook = HOOK.read_text()
        for number in ROUTED:
            with self.subTest(number=number):
                self.assertIn(f"number == __NR_{number})", hook)


PROBE = r"""
#define _GNU_SOURCE
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <unistd.h>

int main(void) {
  int sv[2], on = 1;
  if (socketpair(AF_UNIX, SOCK_STREAM, 0, sv) != 0) return 2;
  setsockopt(sv[1], SOL_SOCKET, SO_PASSCRED, &on, sizeof(on));
  struct ucred sent = {getpid(), (uid_t)syscall(SYS_getuid), (gid_t)syscall(SYS_getgid)};
  char byte = 0, ctrl[CMSG_SPACE(sizeof(struct ucred))];
  struct iovec iov = {&byte, 1};
  struct msghdr msg = {0};
  msg.msg_iov = &iov;
  msg.msg_iovlen = 1;
  msg.msg_control = ctrl;
  msg.msg_controllen = sizeof(ctrl);
  struct cmsghdr *c = CMSG_FIRSTHDR(&msg);
  c->cmsg_level = SOL_SOCKET;
  c->cmsg_type = SCM_CREDENTIALS;
  c->cmsg_len = CMSG_LEN(sizeof(sent));
  memcpy(CMSG_DATA(c), &sent, sizeof(sent));
  errno = 0;
  long rc = syscall(SYS_sendmsg, sv[0], &msg, 0);
  printf("sendmsg %ld %d %u\n", rc, errno, sent.uid);
  char in_ctrl[CMSG_SPACE(sizeof(struct ucred))];
  struct msghdr in = {0};
  in.msg_iov = &iov;
  in.msg_iovlen = 1;
  in.msg_control = in_ctrl;
  in.msg_controllen = sizeof(in_ctrl);
  rc = syscall(SYS_recvmsg, sv[1], &in, MSG_DONTWAIT);
  struct ucred got = {0, 99, 99};
  for (c = CMSG_FIRSTHDR(&in); c; c = CMSG_NXTHDR(&in, c))
    if (c->cmsg_type == SCM_CREDENTIALS) memcpy(&got, CMSG_DATA(c), sizeof(got));
  printf("recvmsg %ld %u\n", rc, got.uid);
  struct ucred peer = {0, 99, 99};
  socklen_t len = sizeof(peer);
  rc = syscall(SYS_getsockopt, sv[1], SOL_SOCKET, SO_PEERCRED, &peer, &len);
  printf("peercred %ld %u\n", rc, peer.uid);
  return 0;
}
"""


class NativeHookSocketCredentialsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if os.getuid() == 0:
            raise unittest.SkipTest("root may send any credentials")
        cls.cc, cls.prefix = runner()
        if cls.cc is None:
            raise unittest.SkipTest("no aarch64 compiler and runner")
        cls.tmp = tempfile.TemporaryDirectory()
        tmp = Path(cls.tmp.name)
        cls.hook = tmp / "hook.so"
        subprocess.run(["bash", str(HOOK_BUILD), str(cls.hook), cls.cc], check=True, capture_output=True)
        (tmp / "probe.c").write_text(PROBE)
        subprocess.run([cls.cc, "-O1", "-o", str(tmp / "probe"), str(tmp / "probe.c")], check=True)
        (tmp / "root").mkdir()

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def probe(self, hooked):
        hook_env = {"DDNATV_ROOT": str(Path(self.tmp.name) / "root")}
        if hooked:
            hook_env["LD_PRELOAD"] = str(self.hook)
        env = {k: v for k, v in os.environ.items() if k != "LD_PRELOAD"}
        if self.prefix:
            cmd = list(self.prefix) + [arg for key, value in hook_env.items() for arg in ("-E", f"{key}={value}")]
        else:
            cmd = []
            env.update(hook_env)
        out = subprocess.run(cmd + [str(Path(self.tmp.name) / "probe")], env=env, capture_output=True, text=True,
                             timeout=120)
        self.assertEqual(0, out.returncode, out.stderr)
        return {line.split(" ", 1)[0]: line.split(" ", 1)[1] for line in out.stdout.splitlines()}

    def test_root_credentials_sent_through_the_raw_call_reach_the_peer_as_root(self):
        result = self.probe(hooked=True)
        self.assertEqual("1 0 0", result["sendmsg"])
        self.assertEqual("1 0", result["recvmsg"])
        self.assertEqual("0 0", result["peercred"])

    def test_without_the_hook_the_kernel_keeps_the_real_ids(self):
        result = self.probe(hooked=False)
        self.assertEqual(f"1 0 {os.getuid()}", result["sendmsg"])
        self.assertEqual(f"1 {os.getuid()}", result["recvmsg"])
        self.assertEqual(f"0 {os.getuid()}", result["peercred"])


if __name__ == "__main__":
    unittest.main()
