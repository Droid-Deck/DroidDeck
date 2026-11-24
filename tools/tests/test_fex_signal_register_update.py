import platform
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

FEX = Path(__file__).resolve().parents[1] / "fex"
PATCH = FEX / "patches/0012-signal-return-applies-guest-registers.patch"
PROBE = FEX / "probes/signal-register-update.c"
HEADER = "Source/Tools/LinuxEmulation/LinuxSyscalls/SignalDelegator.h"
FRAMES = "Source/Tools/LinuxEmulation/LinuxSyscalls/SignalDelegator/GuestFramesManagement.cpp"
X64_ORDER = ["RAX", "RCX", "RDX", "RBX", "RSP", "RBP", "RSI", "RDI", "R8", "R9", "R10", "R11", "R12", "R13", "R14", "R15"]


def files():
    sections = re.split(r"^diff --git a/(\S+) \S+\n", PATCH.read_text(), flags=re.M)
    return {sections[i]: sections[i + 1] for i in range(1, len(sections), 2)}


def added(section):
    return [line[1:].strip() for line in section.splitlines() if line.startswith("+") and not line.startswith("+++")]


def removed(section):
    return [line[1:].strip() for line in section.splitlines() if line.startswith("-") and not line.startswith("---")]


def helper():
    lines = added(files()[FRAMES])
    start = next(i for i, line in enumerate(lines) if line.startswith("void SignalDelegator::ResumeWithGuestGPRs("))
    end = lines.index("#endif", start)
    return lines[start:end + 1]


class SignalRegisterUpdateTest(unittest.TestCase):
    def test_only_signal_frame_files_change(self):
        self.assertEqual(sorted(files()), sorted([FRAMES, HEADER]))

    def test_patch_only_adds_code(self):
        for name, section in files().items():
            self.assertEqual(removed(section), [], name)

    def test_added_code_has_no_comments(self):
        for name, section in files().items():
            for line in added(section):
                self.assertNotIn("//", line, name)
                self.assertNotIn("/*", line, name)

    def test_helper_is_declared_once(self):
        self.assertEqual(sum(line.startswith("void ResumeWithGuestGPRs(") for line in added(files()[HEADER])), 1)

    def test_only_changed_registers_are_written_back(self):
        body = helper()
        self.assertIn("if (((Delivered ^ GuestGPRs[i]) & Mask) == 0) {", body)
        self.assertEqual(body[body.index("if (((Delivered ^ GuestGPRs[i]) & Mask) == 0) {") + 1], "continue;")

    def test_spilled_registers_go_to_the_frame_and_live_ones_to_host_registers(self):
        body = helper()
        self.assertIn("const bool InFrame = AllSpilled || (Spilled & (1U << HostReg));", body)
        self.assertIn("Frame->State.gregs[i] = GuestGPRs[i];", body)
        self.assertIn("ArchHelpers::Context::SetArmReg(ucontext, HostReg, GuestGPRs[i]);", body)
        self.assertIn("for (size_t i = 0; i < Count && i < Config.SRAGPRCount; i++) {", body)

    def test_signals_outside_jit_code_are_left_alone(self):
        body = helper()
        guard = body.index("if (!AllSpilled && !(Context->Flags & ArchHelpers::Context::ContextFlags::CONTEXT_FLAG_INJIT)) {")
        self.assertEqual(body[guard + 1], "return;")
        self.assertEqual(body[2], "#ifdef ARCHITECTURE_arm64")

    def test_every_frame_type_applies_registers_when_rip_is_unchanged(self):
        lines = added(files()[FRAMES])
        calls = [line for line in lines if line.startswith("ResumeWithGuestGPRs(")]
        self.assertEqual(calls, [
            "ResumeWithGuestGPRs(Context, Frame, ucontext, GuestGPRs, FEXCore::Core::CPUState::NUM_GPRS, ~0ULL);",
            "ResumeWithGuestGPRs(Context, Frame, ucontext, GuestGPRs, 8, 0xFFFF'FFFFULL);",
            "ResumeWithGuestGPRs(Context, Frame, ucontext, GuestGPRs, 8, 0xFFFF'FFFFULL);",
        ])
        branches = [lines[i + 1] for i, line in enumerate(lines[:-1]) if line == "} else {" and lines[i + 1].startswith("uint64_t GuestGPRs[")]
        self.assertEqual(branches, ["uint64_t GuestGPRs[FEXCore::Core::CPUState::NUM_GPRS];", "uint64_t GuestGPRs[8];", "uint64_t GuestGPRs[8];"])

    def test_registers_are_stored_by_guest_register_index(self):
        lines = added(files()[FRAMES])
        x64 = [m.group(1) for line in lines for m in [re.fullmatch(r"COPY_REG\((\w+)\);", line)] if m]
        self.assertEqual(sorted(x64), sorted(X64_ORDER))
        self.assertIn("#define COPY_REG(x) GuestGPRs[FEXCore::X86State::REG_##x] = guest_uctx->uc_mcontext.gregs[FEXCore::x86_64::FEX_REG_##x];", lines)
        legacy = re.findall(r"GuestGPRs\[FEXCore::X86State::REG_(\w+)\] = guest_uctx->sc\.(\w+);", "\n".join(lines))
        self.assertEqual(sorted(legacy), sorted((r, r[1:].lower()) for r in X64_ORDER[:8]))
        rt = re.findall(r"GuestGPRs\[FEXCore::X86State::REG_(\w+)\] = guest_uctx->uc\.uc_mcontext\.gregs\[FEXCore::x86::FEX_REG_(\w+)\];", "\n".join(lines))
        self.assertEqual(sorted(rt), sorted((r, r) for r in X64_ORDER[:8]))

    def test_patch_follows_the_thunk_patch_on_every_release(self):
        names = sorted(path.name for path in (FEX / "patches").glob("*.patch"))
        self.assertEqual(names[names.index(PATCH.name) - 1], "0011-thunk-calls-mark-spilled-state.patch")
        self.assertNotIn("Fixed-upstream", PATCH.read_text())


@unittest.skipUnless(platform.machine() == "x86_64", "the probe is x86 code")
class SignalRegisterUpdateProbeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.compiler = shutil.which("cc") or shutil.which("gcc") or shutil.which("clang")
        if not cls.compiler:
            raise unittest.SkipTest("no C compiler")
        cls.tmp = Path(tempfile.mkdtemp(prefix="fex-signal-regs-"))

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def build(self, *flags):
        binary = self.tmp / ("probe" + "".join(flags))
        result = subprocess.run([self.compiler, "-O2", *flags, "-o", str(binary), str(PROBE)], capture_output=True, text=True)
        return binary if result.returncode == 0 else None

    def check(self, binary, *args):
        result = subprocess.run([str(binary), *args], capture_output=True, text=True, timeout=60)
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("seen=1", result.stdout)

    def test_native_kernel_applies_handler_register_changes(self):
        self.check(self.build())

    def test_native_kernel_applies_handler_register_changes_in_32_bit_frames(self):
        binary = self.build("-m32")
        if binary is None:
            self.skipTest("no 32-bit toolchain")
        self.check(binary)
        self.check(binary, "legacy")


if __name__ == "__main__":
    unittest.main()
