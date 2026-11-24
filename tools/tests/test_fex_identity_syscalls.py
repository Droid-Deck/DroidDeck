import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

from tools.tests.test_usr2_preload import runner

REPO = Path(__file__).resolve().parents[2]
PATCH = REPO / "tools/fex/patches/0016-identity-syscalls-through-libc.patch"
HOOK = REPO / "tools/nativeguest/hook/privileged.c"
HOOK_BUILD = REPO / "tools/nativeguest/build.sh"
PASSTHROUGH = "Source/Tools/LinuxEmulation/LinuxSyscalls/Syscalls/Passthrough.cpp"
REGISTER = re.compile(r"REGISTER_SYSCALL_IMPL(?:_X64|_X32)?\((\w+), (\w+?)(\d)<SYSCALL_DEF\((\w+)\)>\);")
IDENTITY = {"getuid", "getgid", "geteuid", "getegid", "setuid", "setgid", "setreuid", "setregid", "getgroups",
            "setgroups", "setresuid", "getresuid", "setresgid", "getresgid", "setfsuid", "setfsgid", "fchown"}


def changed():
    removed, added = {}, {}
    for line in PATCH.read_text().splitlines():
        found = REGISTER.search(line)
        if found and line[:1] in "+-":
            (added if line[0] == "+" else removed)[found.group(1)] = found.groups()[1:]
    return removed, added


class FexIdentitySyscallsTest(unittest.TestCase):
    def test_only_the_passthrough_table_changes(self):
        self.assertEqual(re.findall(r"^diff --git a/(\S+) ", PATCH.read_text(), re.M), [PASSTHROUGH])

    def test_each_identity_syscall_keeps_its_arity_and_goes_through_libc(self):
        removed, added = changed()
        self.assertEqual(sorted(removed), sorted(added))
        self.assertEqual(IDENTITY, {number for _, _, number in added.values()})
        for name, (template, arity, number) in added.items():
            with self.subTest(name=name):
                self.assertEqual(("SyscallPassthrough", arity, number), removed[name])
                self.assertEqual("SyscallPathPassthrough", template)

    def test_the_new_template_calls_the_libc_syscall(self):
        added = [line[1:] for line in PATCH.read_text().splitlines() if line.startswith("+") and not line.startswith("+++")]
        self.assertEqual(["  uint64_t Result = ::syscall(syscall_num);"], [line for line in added if "Result =" in line])
        self.assertFalse([line for line in added if line.strip().startswith(("//", "/*"))])

    def test_every_routed_syscall_is_answered_by_the_hook(self):
        hook = HOOK.read_text()
        for number in IDENTITY:
            with self.subTest(number=number):
                self.assertIn(f"number == __NR_{number})", hook)


PROBE = r"""
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

int main(int argc, char **argv) {
  unsigned int r = 7, e = 7, s = 7;
  long rc = syscall(SYS_getresuid, &r, &e, &s);
  printf("getresuid %ld %u %u %u\n", rc, r, e, s);
  errno = 0;
  rc = syscall(SYS_getresuid, NULL, &e, &s);
  printf("getresuid-null %ld %d\n", rc, errno);
  r = e = s = 7;
  rc = syscall(SYS_getresgid, &r, &e, &s);
  printf("getresgid %ld %u %u %u\n", rc, r, e, s);
  errno = 0;
  rc = syscall(SYS_getresgid, &r, NULL, &s);
  printf("getresgid-null %ld %d\n", rc, errno);
  unsigned int *locked = mmap(NULL, 4096, PROT_READ, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  errno = 0;
  rc = syscall(SYS_getresuid, &r, &e, locked);
  printf("getresuid-readonly %ld %d\n", rc, errno);
  errno = 0;
  rc = syscall(SYS_getresgid, locked, &e, &s);
  printf("getresgid-readonly %ld %d\n", rc, errno);
  gid_t groups[8];
  rc = syscall(SYS_getgroups, 8, groups);
  printf("getgroups %ld %u\n", rc, rc > 0 ? groups[0] : 99u);
  errno = 0;
  rc = syscall(SYS_getgroups, 8, locked);
  printf("getgroups-readonly %ld %d\n", rc, errno);
  int fd = open(argv[1], O_RDONLY);
  printf("fchown %ld\n", syscall(SYS_fchown, fd, 4321, 4321));
  printf("getuid %ld\n", syscall(SYS_getuid));
  return 0;
}
"""


class NativeHookIdentityTest(unittest.TestCase):
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
        (tmp / "root").mkdir()
        cls.file = tmp / "owned"
        cls.file.write_text("")

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def probe(self):
        hook_env = {"DDNATV_ROOT": str(Path(self.tmp.name) / "root"), "LD_PRELOAD": str(self.hook)}
        env = {k: v for k, v in os.environ.items() if k != "LD_PRELOAD"}
        if self.prefix:
            cmd = list(self.prefix) + [arg for key, value in hook_env.items() for arg in ("-E", f"{key}={value}")]
        else:
            cmd = []
            env.update(hook_env)
        out = subprocess.run(cmd + [str(Path(self.tmp.name) / "probe"), str(self.file)], env=env,
                             capture_output=True, text=True, timeout=120)
        self.assertEqual(0, out.returncode, out.stderr)
        return {line.split(" ", 1)[0]: line.split(" ", 1)[1] for line in out.stdout.splitlines()}

    def test_the_hook_answers_identity_syscalls_as_root(self):
        result = self.probe()
        self.assertEqual("0 0 0 0", result["getresuid"])
        self.assertEqual("0 0 0 0", result["getresgid"])
        self.assertEqual("1 0", result["getgroups"])
        self.assertEqual("-1 14", result["getgroups-readonly"])
        self.assertEqual("0", result["fchown"])
        self.assertEqual("0", result["getuid"])

    def test_a_missing_pointer_still_faults(self):
        result = self.probe()
        self.assertEqual("-1 14", result["getresuid-null"])
        self.assertEqual("-1 14", result["getresgid-null"])
        self.assertEqual("-1 14", result["getresuid-readonly"])
        self.assertEqual("-1 14", result["getresgid-readonly"])
        self.assertEqual(os.stat(self.file).st_uid, os.getuid())


if __name__ == "__main__":
    unittest.main()
