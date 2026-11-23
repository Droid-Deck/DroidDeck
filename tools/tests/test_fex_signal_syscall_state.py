import platform
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

FEX = Path(__file__).resolve().parents[1] / "fex"
PATCH = FEX / "patches/0008-signal-return-restores-syscall-state.patch"
PROBE = FEX / "probes/async-signal-context.c"
RESTORE = "Frame->InSyscallInfo = Context->InSyscallInfo;"


def hunks():
    text = PATCH.read_text()
    files = re.findall(r"^diff --git a/(\S+) ", text, re.M)
    return files, re.findall(r"^@@ [^@]+ @@ ?(.*)\n((?:[ +\-].*\n?)*)", text, re.M)


class SignalReturnSyscallStateTest(unittest.TestCase):
    def test_only_the_signal_delegator_changes(self):
        files, found = hunks()
        self.assertEqual(files, ["Source/Tools/LinuxEmulation/LinuxSyscalls/SignalDelegator.cpp"])
        self.assertEqual(len(found), 1)

    def test_every_guest_frame_return_restores_the_interrupted_syscall_state(self):
        _, found = hunks()
        function, body = found[0]
        self.assertIn("SignalDelegator::RestoreThreadState", function)
        lines = body.splitlines()
        added = [line[1:].strip() for line in lines if line.startswith("+")]
        removed = [line for line in lines if line.startswith("-")]
        self.assertEqual(added, [RESTORE])
        self.assertEqual(removed, [])
        context = [line[1:].strip() for line in lines]
        guest_frame = context.index("if (Context->UContextLocation) {")
        self.assertEqual(context[guest_frame + 1], "auto Frame = Thread->CurrentFrame;")
        self.assertEqual(context[guest_frame + 2], RESTORE)
        self.assertLess(context.index(RESTORE), context.index("if (Context->Flags & ArchHelpers::Context::ContextFlags::CONTEXT_FLAG_INJIT) {"))

    def test_thread_pause_returns_are_untouched(self):
        _, found = hunks()
        body = found[0][1]
        self.assertNotIn("TYPE_PAUSE", "".join(line for line in body.splitlines() if line.startswith("+")))

    def test_patch_is_applied_to_every_release(self):
        self.assertNotIn("Fixed-upstream", PATCH.read_text())
        self.assertIn("tools/fex/patches/*.patch", (FEX / "build-in-arch.sh").read_text())


@unittest.skipUnless(platform.machine() == "x86_64", "the probe is x86-64 code")
class AsyncSignalContextProbeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        compiler = shutil.which("cc") or shutil.which("gcc") or shutil.which("clang")
        if not compiler:
            raise unittest.SkipTest("no C compiler")
        cls.tmp = Path(tempfile.mkdtemp(prefix="fex-async-signal-"))
        cls.binary = cls.tmp / "probe"
        subprocess.run([compiler, "-O2", "-pthread", "-o", str(cls.binary), str(PROBE)], check=True, capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def test_native_kernel_reports_the_interrupted_registers(self):
        result = subprocess.run([str(self.binary), "500"], capture_output=True, text=True, timeout=120)
        self.assertEqual(result.returncode, 0, result.stdout)
        counts = dict(part.split("=") for part in result.stdout.split())
        self.assertGreaterEqual(int(counts["matched"]), 500)
        self.assertEqual(counts["stale"], "0")


if __name__ == "__main__":
    unittest.main()
