import platform
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

FEX = Path(__file__).resolve().parents[1] / "fex"
PATCH = FEX / "patches/0011-thunk-calls-mark-spilled-state.patch"
PROBE = FEX / "probes/vdso-signal-state.c"
CORE = "FEXCore/Source/Interface/Core/Core.cpp"
BRANCH_OPS = "FEXCore/Source/Interface/Core/JIT/BranchOps.cpp"
DELEGATOR = "Source/Tools/LinuxEmulation/LinuxSyscalls/SignalDelegator.cpp"
FRAMES = "Source/Tools/LinuxEmulation/LinuxSyscalls/SignalDelegator/GuestFramesManagement.cpp"
MARK = "str(TMP1, STATE, offsetof(FEXCore::Core::CpuStateFrame, InSyscallInfo));"
CLEAR = "str(ARMEmitter::XReg::zr, STATE, offsetof(FEXCore::Core::CpuStateFrame, InSyscallInfo));"
RESTORE = "Frame->InSyscallInfo = Context->InSyscallInfo;"


def files():
    sections = re.split(r"^diff --git a/(\S+) \S+\n", PATCH.read_text(), flags=re.M)
    return {sections[i]: sections[i + 1] for i in range(1, len(sections), 2)}


def hunks(section):
    return re.findall(r"^@@ [^@]+ @@ ?(.*)\n((?:[ +\-].*\n?)*)", section, re.M)


def added(body):
    return [line[1:].strip() for line in body.splitlines() if line.startswith("+") and not line.startswith("+++")]


def removed(body):
    return [line[1:].strip() for line in body.splitlines() if line.startswith("-") and not line.startswith("---")]


def context(body):
    return [line[1:].strip() for line in body.splitlines() if line[:1] in (" ", "+")]


class ThunkSignalStateTest(unittest.TestCase):
    def test_only_signal_state_files_change(self):
        self.assertEqual(sorted(files()), sorted([BRANCH_OPS, CORE, DELEGATOR, FRAMES]))

    def test_added_code_has_no_new_comments(self):
        for name, section in files().items():
            moved = set(removed(section))
            for line in added(section):
                if "//" in line or "/*" in line:
                    self.assertIn(line, moved, f"{name}: {line}")

    def test_thunk_call_marks_all_state_spilled_until_the_refill(self):
        section = files()[BRANCH_OPS]
        lines = context(section)
        self.assertEqual([line for line in added(section) if line], ["LoadConstant(ARMEmitter::Size::i64Bit, TMP1, 0xFFFF);", MARK, CLEAR])
        self.assertEqual(removed(section), [])
        self.assertLess(lines.index("PushDynamicRegs(TMP1);"), lines.index(MARK))
        self.assertLess(lines.index(MARK), lines.index("InsertNamedThunkRelocation(ARMEmitter::Reg::r2, Op->ThunkNameHash);"))
        refill = lines.index("FillStaticRegs({")
        self.assertEqual(lines[refill + 1:refill + 3], [".NZCV = false,", "});"])
        self.assertLess(refill, lines.index(CLEAR))

    def test_fully_spilled_state_is_never_respilled_from_host_registers(self):
        found = hunks(files()[DELEGATOR])
        spill = "".join(body for function, body in found if "SpillSRA" in function or "IgnoreMask" in body)
        lines = context(spill)
        guard = lines.index("if ((IgnoreMask & 0xFFFF) == 0xFFFF) {")
        self.assertIn("RestoreRIPFromHostPC", lines[guard - 2])
        self.assertEqual(lines[guard + 1], "return;")
        self.assertIn("for (size_t i = 0; i < Config.SRAGPRCount; i++) {", lines[guard + 2:guard + 5])

    def test_every_guest_signal_saves_and_clears_the_mark(self):
        section = files()[DELEGATOR]
        lines = added(section)
        self.assertIn("ContextBackup->InSyscallInfo = Frame->InSyscallInfo;", lines)
        self.assertIn("Frame->InSyscallInfo = 0;", lines)
        self.assertIn(RESTORE, lines)
        self.assertIn("if (Context->Flags & ArchHelpers::Context::ContextFlags::CONTEXT_FLAG_INJIT) {", removed(section))
        self.assertNotIn("TYPE_PAUSE", " ".join(lines))

    def test_redirected_returns_restart_with_no_mark(self):
        section = files()[FRAMES]
        self.assertEqual(added(section), ["Frame->InSyscallInfo = 0;"] * 3)
        self.assertEqual(removed(section), [RESTORE] * 3)

    def test_guest_callbacks_run_with_no_mark(self):
        lines = added(files()[CORE])
        self.assertEqual(lines[1:3], ["const auto InSyscallInfo = Frame->InSyscallInfo;", "Frame->InSyscallInfo = 0;"])
        self.assertEqual(lines[-1], "Frame->InSyscallInfo = InSyscallInfo;")
        self.assertIn("ExecuteJITCallback(Frame, RIP);", lines[-2])

    def test_patch_follows_the_host_stack_patch_on_every_release(self):
        names = sorted(path.name for path in (FEX / "patches").glob("*.patch"))
        self.assertEqual(names[names.index(PATCH.name) - 1], "0010-syscall-exit-resets-host-stack.patch")
        self.assertNotIn("Fixed-upstream", PATCH.read_text())


@unittest.skipUnless(platform.machine() == "x86_64", "the probe is x86-64 code")
class VdsoSignalStateProbeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        compiler = shutil.which("cc") or shutil.which("gcc") or shutil.which("clang")
        if not compiler:
            raise unittest.SkipTest("no C compiler")
        cls.tmp = Path(tempfile.mkdtemp(prefix="fex-vdso-signal-"))
        cls.binary = cls.tmp / "probe"
        subprocess.run([compiler, "-O2", "-o", str(cls.binary), str(PROBE)], check=True, capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def test_native_kernel_keeps_registers_across_interrupted_clock_calls(self):
        result = subprocess.run([str(self.binary), "1"], capture_output=True, text=True, timeout=60)
        self.assertEqual(result.returncode, 0, result.stdout)
        counts = dict(part.split("=") for part in result.stdout.split())
        self.assertGreater(int(counts["calls"]), 0)
        self.assertGreater(int(counts["alarms"]), 0)
        self.assertEqual(counts["bad"], "0")


if __name__ == "__main__":
    unittest.main()
