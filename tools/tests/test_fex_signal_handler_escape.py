import platform
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

FEX = Path(__file__).resolve().parents[1] / "fex"
PATCH = FEX / "patches/0009-signal-handler-escape-frees-host-stack.patch"
PROBE = FEX / "probes/signal-handler-escape.c"
DELEGATOR = "Source/Tools/LinuxEmulation/LinuxSyscalls/SignalDelegator.cpp"
CONTEXT = "Source/Tools/LinuxEmulation/ArchHelpers/MContext.h"
THREADS = "Source/Tools/LinuxEmulation/LinuxSyscalls/ThreadManager.h"
FIELDS = ["PrevGuestFrame", "GuestFrameEnd", "GuestInterruptedSP", "GuestAltBase", "GuestAltEnd"]


def files():
    sections = re.split(r"^diff --git a/(\S+) \S+\n", PATCH.read_text(), flags=re.M)
    return {sections[i]: sections[i + 1] for i in range(1, len(sections), 2)}


def hunks(section):
    return re.findall(r"^@@ [^@]+ @@ ?(.*)\n((?:[ +\-].*\n?)*)", section, re.M)


def added(body):
    return [line[1:].strip() for line in body.splitlines() if line.startswith("+") and not line.startswith("+++")]


def removed(body):
    return [line for line in body.splitlines() if line.startswith("-") and not line.startswith("---")]


class SignalHandlerEscapeTest(unittest.TestCase):
    def test_only_signal_state_files_change(self):
        self.assertEqual(sorted(files()), sorted([CONTEXT, DELEGATOR, THREADS]))

    def test_nothing_is_removed(self):
        for name, section in files().items():
            self.assertEqual(removed(section), [], name)

    def test_added_code_has_no_comments(self):
        for name, section in files().items():
            for line in added(section):
                self.assertFalse("//" in line or "/*" in line, f"{name}: {line}")

    def test_both_context_backups_record_the_guest_frame(self):
        lines = added(files()[CONTEXT])
        self.assertEqual(lines, [f"uint64_t {field};" for field in FIELDS] * 2)

    def test_each_thread_keeps_its_frame_chain(self):
        self.assertEqual([line for line in added(files()[THREADS]) if line], ["uint64_t GuestFrames {};", "uint32_t GuestFrameCount {};"])

    def test_delivery_pushes_and_reclaims_only_left_frames_at_the_dispatch_level(self):
        found = {function: body for function, body in hunks(files()[DELEGATOR])}
        delivery = "".join(body for function, body in found.items() if "HandleDispatcherGuestSignal" in function)
        lines = added(delivery)
        self.assertIn("#ifdef ARCHITECTURE_arm64", lines)
        gate = " ".join(lines)
        self.assertIn("WasInJIT && Newest && ContextBackup->PrevSP == SignalInfo.GuestFrames", gate)
        self.assertIn("Frame->SignalHandlerRefCounter == SignalInfo.GuestFrameCount + 1", gate)
        self.assertIn("GuestLeftFrame(Newest, OldGuestSP", gate)
        self.assertIn("ContextBackup->PrevSP = HostTop;", lines)
        self.assertIn("Frame->SignalHandlerRefCounter -= LeftFrames;", lines)
        self.assertIn("SignalInfo.GuestFrames = reinterpret_cast<uint64_t>(ContextBackup);", lines)
        self.assertIn("++SignalInfo.GuestFrameCount;", lines)
        self.assertLess(lines.index("#endif"), lines.index("ContextBackup->PrevGuestFrame = SignalInfo.GuestFrames;"))

    def test_signal_return_pops_its_frame_and_any_newer_ones(self):
        found = hunks(files()[DELEGATOR])
        restore = "".join(body for function, body in found if "RestoreThreadState" in function)
        lines = added(restore)
        self.assertIn("if (Entry == OldSP) {", lines)
        self.assertIn("SignalInfo.GuestFrames = EntryBackup->PrevGuestFrame;", lines)
        self.assertIn("Thread->CurrentFrame->SignalHandlerRefCounter -= Depth - 1;", lines)
        self.assertNotIn("TYPE_PAUSE", " ".join(lines))

    def test_thread_stop_clears_the_chain(self):
        found = hunks(files()[DELEGATOR])
        pause = "".join(body for function, body in found if "HandleSignalPause" in function)
        self.assertEqual(added(pause), ["ThreadObject->SignalInfo.GuestFrames = 0;", "ThreadObject->SignalInfo.GuestFrameCount = 0;"])

    def test_patch_follows_the_syscall_state_patch_on_every_release(self):
        names = sorted(path.name for path in (FEX / "patches").glob("*.patch"))
        self.assertEqual(names[names.index(PATCH.name) - 1], "0008-signal-return-restores-syscall-state.patch")
        self.assertNotIn("Fixed-upstream", PATCH.read_text())
        self.assertIn("tools/fex/patches/*.patch", (FEX / "build-in-arch.sh").read_text())


@unittest.skipUnless(platform.machine() == "x86_64", "the probe is x86-64 code")
class SignalHandlerEscapeProbeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        compiler = shutil.which("cc") or shutil.which("gcc") or shutil.which("clang")
        if not compiler:
            raise unittest.SkipTest("no C compiler")
        cls.tmp = Path(tempfile.mkdtemp(prefix="fex-signal-escape-"))
        cls.binary = cls.tmp / "probe"
        subprocess.run([compiler, "-O2", "-o", str(cls.binary), str(PROBE)], check=True, capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def test_native_kernel_survives_every_escape(self):
        result = subprocess.run([str(self.binary), "20000"], capture_output=True, text=True, timeout=120)
        self.assertEqual(result.returncode, 0, result.stdout)
        counts = dict(part.split("=") for part in result.stdout.split())
        for phase in ("onstack", "offstack", "mixed", "returns", "outer"):
            self.assertEqual(counts[phase], "20000", phase)
        self.assertEqual(counts["nested"], "60000")
        self.assertEqual(counts["wrong"], "0")


if __name__ == "__main__":
    unittest.main()
