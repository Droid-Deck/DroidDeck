import re
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
PATCH = REPO / "tools/fex/patches/0016-identity-syscalls-through-libc.patch"
HOOK = REPO / "tools/nativeguest/hook/privileged.c"
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


if __name__ == "__main__":
    unittest.main()
