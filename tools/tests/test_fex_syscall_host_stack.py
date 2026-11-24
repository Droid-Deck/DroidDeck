import platform
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

FEX = Path(__file__).resolve().parents[1] / "fex"
PATCH = FEX / "patches/0010-syscall-exit-resets-host-stack.patch"
PROBE = FEX / "probes/syscall-host-stack.c"
BRANCH_OPS = "FEXCore/Source/Interface/Core/JIT/BranchOps.cpp"


def hunks():
    text = PATCH.read_text()
    files = re.findall(r"^diff --git a/(\S+) ", text, re.M)
    return files, re.findall(r"^@@ [^@]+ @@ ?(.*)\n((?:[ +\-].*\n?)*)", text, re.M)


class SyscallHostStackTest(unittest.TestCase):
    def test_only_the_branch_ops_change(self):
        files, found = hunks()
        self.assertEqual(files, [BRANCH_OPS])
        self.assertEqual(len(found), 2)

    def test_both_ops_that_leave_the_block_reset_the_stack_after_spilling(self):
        _, found = hunks()
        for (function, body), op in zip(found, ("DEF_OP(Syscall)", "DEF_OP(ThreadRemoveCodeEntry)")):
            lines = body.splitlines()
            added = [line[1:].strip() for line in lines if line.startswith("+")]
            self.assertEqual([line for line in added if line], ["ResetStack();"], op)
            self.assertEqual([line for line in lines if line.startswith("-")], [], op)
            context = [line[1:].strip() for line in lines]
            self.assertIn(op, function + " " + " ".join(context))
            self.assertLess(context.index("SpillStaticRegs(TMP1);"), context.index("ResetStack();"), op)

    def test_patch_is_skipped_once_upstream_ships_it(self):
        self.assertEqual(PATCH.read_text().splitlines()[0], "Fixed-upstream: FEX-2612")
        self.assertIn("Fixed-upstream: FEX-", (FEX / "build-in-arch.sh").read_text())


@unittest.skipUnless(platform.machine() == "x86_64", "the probe is x86-64 code")
class SyscallHostStackProbeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        compiler = shutil.which("cc") or shutil.which("gcc") or shutil.which("clang")
        if not compiler:
            raise unittest.SkipTest("no C compiler")
        cls.tmp = Path(tempfile.mkdtemp(prefix="fex-syscall-stack-"))
        cls.binary = cls.tmp / "probe"
        subprocess.run([compiler, "-O2", "-o", str(cls.binary), str(PROBE)], check=True, capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def test_native_kernel_runs_every_syscall(self):
        result = subprocess.run([str(self.binary), "200000"], capture_output=True, text=True, timeout=120)
        self.assertEqual(result.returncode, 0, result.stdout)
        counts = dict(part.split("=") for part in result.stdout.split())
        self.assertIn(counts["mode"], ("avx", "mmx"))
        self.assertEqual(counts["syscalls"], "200000")


if __name__ == "__main__":
    unittest.main()
