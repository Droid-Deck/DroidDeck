import platform
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

FEX = Path(__file__).resolve().parents[1] / "fex"
PATCH = FEX / "patches/0013-signal-context-matches-host-pc.patch"
PROBE = FEX / "probes/signal-return-hijack.c"
CORE = "FEXCore/Source/Interface/Core/Core.cpp"
BRANCH_OPS = "FEXCore/Source/Interface/Core/JIT/BranchOps.cpp"
JIT = "FEXCore/Source/Interface/Core/JIT/JIT.cpp"
DELEGATOR = "Source/Tools/LinuxEmulation/LinuxSyscalls/SignalDelegator.cpp"
DELEGATOR_H = "Source/Tools/LinuxEmulation/LinuxSyscalls/SignalDelegator.h"
OPCODES = "FEXCore/Source/Interface/Core/OpcodeDispatcher.h"


def files():
    sections = re.split(r"^diff --git a/(\S+) \S+\n", PATCH.read_text(), flags=re.M)
    return {sections[i]: sections[i + 1] for i in range(1, len(sections), 2)}


def added(section):
    return [line[1:].strip() for line in section.splitlines() if line.startswith("+") and not line.startswith("+++")]


def removed(section):
    return [line[1:].strip() for line in section.splitlines() if line.startswith("-") and not line.startswith("---")]


def restore():
    lines = added(files()[CORE])
    start = next(i for i, line in enumerate(lines) if line.startswith("uint64_t ContextImpl::RestoreRIPFromHostPC("))
    return lines[start:]


class SignalContextHostPcTest(unittest.TestCase):
    def test_added_code_has_no_comments(self):
        for name, section in files().items():
            for line in added(section):
                self.assertNotIn("//", line, name)
                self.assertNotIn("/*", line, name)

    def test_every_instruction_gets_a_rip_entry(self):
        core = files()[CORE]
        self.assertIn("Thread->OpDispatcher->_GuestOpcode(InstAddress - GuestRIP);", added(core))
        self.assertIn("if (ExtendedDebugInfo || Thread->OpDispatcher->CanHaveSideEffects(TableInfo, DecodedInfo)) {", removed(core))
        self.assertTrue(any(line.startswith("static bool CanHaveSideEffects(") for line in removed(files()[OPCODES])))
        self.assertFalse(any("CanHaveSideEffects" in line for name, section in files().items() for line in added(section)))

    def test_exit_code_reports_the_branch_target(self):
        body = restore()
        self.assertIn("if (Exits[i].TargetReg == CPU::CPUBackend::ExitTargetConstant) {", body)
        self.assertIn("return InlineTail->RIP + Exits[i].TargetOffset;", body)
        self.assertIn("return HostGPRs[Exits[i].TargetReg];", body)
        ops = added(files()[BRANCH_OPS])
        self.assertEqual(sum(line == "RecordExit();" for line in ops), 2)
        jit = added(files()[JIT])
        self.assertIn("JITBlockTail.NumberOfExitEntries = DebugData->Exits.size();", jit)
        self.assertIn("DebugData->Exits.clear();", jit)

    def test_block_entry_takes_the_header_it_is_about_to_store(self):
        body = restore()
        guard = body.index("if (IsAddressInCodeBuffer(Thread, HostPC)) {")
        self.assertEqual(body[guard + 1], "if (const auto Header = BlockHeaderAtEntryPoint(HostPC)) {")
        self.assertEqual(body[guard + 2], "Frame->State.InlineJITBlockHeader = Header;")
        lines = added(files()[CORE])
        self.assertIn("static uint64_t BlockHeaderAtEntryPoint(uint64_t HostPC) {", lines)
        self.assertIn("if (!Header || HostPC < Start || Header >= Start) {", lines)
        self.assertIn("return HostPC < Header + InlineTail->Size ? Header : 0;", lines)

    def test_dispatcher_pcs_never_read_the_block_header(self):
        body = restore()
        outside = body.index("} else {")
        self.assertLess(outside, next(i for i, line in enumerate(body) if line.startswith("auto Exits = ")))
        self.assertIn("return reinterpret_cast<const ExitFunctionLinkData*>(HostGPRs[30])->GuestRIP;", body[outside:outside + 4])
        self.assertIn("return Frame->State.rip;", body[outside:outside + 6])

    def test_dispatcher_with_live_static_registers_counts_as_jit(self):
        self.assertIn("const bool WasInJIT = CTX->IsAddressInCodeBuffer(Thread, OldPC) || IsAddressInDispatcherWithLiveSRA(Frame, OldPC);",
                      added(files()[DELEGATOR]))
        header = added(files()[DELEGATOR_H])
        self.assertIn("if (Address >= Config.AbsoluteLoopTopAddress && Address < Config.ThreadStopHandlerAddressSpillSRA) {", header)
        self.assertIn("return Address >= Frame->Pointers.ExitFunctionLinker && Address < Config.SignalHandlerReturnAddress;", header)

    def test_spill_passes_host_registers(self):
        self.assertIn("CTX->RestoreRIPFromHostPC(Thread, ArchHelpers::Context::GetPc(ucontext), ArchHelpers::Context::GetArmGPRs(ucontext));",
                      added(files()[DELEGATOR]))

    def test_patch_follows_the_register_patch_on_every_release(self):
        names = sorted(path.name for path in (FEX / "patches").glob("*.patch"))
        self.assertEqual(names[names.index(PATCH.name) - 1], "0012-signal-return-applies-guest-registers.patch")
        self.assertNotIn("Fixed-upstream", PATCH.read_text())


@unittest.skipUnless(platform.machine() == "x86_64", "the probe is x86 code")
class SignalReturnHijackProbeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.compiler = shutil.which("cc") or shutil.which("gcc") or shutil.which("clang")
        if not cls.compiler:
            raise unittest.SkipTest("no C compiler")
        cls.tmp = Path(tempfile.mkdtemp(prefix="fex-signal-hijack-"))
        cls.binary = cls.tmp / "probe"
        subprocess.run([cls.compiler, "-O2", "-Wall", "-Wextra", "-Werror", "-o", str(cls.binary), str(PROBE)], check=True)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def test_native_kernel_reports_consistent_context_in_every_mode(self):
        for mode in ("1", "2", "3"):
            with self.subTest(mode=mode):
                result = subprocess.run([str(self.binary), "100000", mode], capture_output=True, text=True, timeout=120)
                self.assertEqual(result.returncode, 0, result.stdout)
                self.assertTrue(result.stdout.endswith("PASS\n"), result.stdout)


if __name__ == "__main__":
    unittest.main()
