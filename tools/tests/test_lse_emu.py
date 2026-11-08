from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


TOOLS = Path(__file__).resolve().parents[1]
SYSROOT = Path("/usr/aarch64-linux-gnu")


def toolchain():
    if not (shutil.which("aarch64-linux-gnu-gcc") and shutil.which("qemu-aarch64") and SYSROOT.is_dir()):
        raise unittest.SkipTest("needs aarch64-linux-gnu-gcc, libc6-dev-arm64-cross and qemu-user")


class LseEmuTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        toolchain()
        cls.tmp = tempfile.TemporaryDirectory(prefix="droiddeck-lse-")
        out = Path(cls.tmp.name)
        subprocess.run(["bash", str(TOOLS / "linuxfs/lse-emu/build.sh"), str(out)], check=True)
        cls.library = out / "usr/local/lib/droiddeck-lse/liblse_emu.so"
        cls.program = out / "lse-test"
        subprocess.run(["aarch64-linux-gnu-gcc", "-O1", "-march=armv8.1-a", "-Wall", "-Wextra", "-Werror", "-pthread",
                        str(Path(__file__).with_name("lse_emu_test.c")), "-o", str(cls.program)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def run_on(self, cpu, *env, threads=4):
        command = ["qemu-aarch64", "-cpu", cpu, "-L", str(SYSROOT)]
        for item in env:
            command += ["-E", item]
        return subprocess.run(command + [str(self.program), str(threads)], capture_output=True, text=True, timeout=120)

    def test_an_armv8_0_cpu_faults_without_the_emulator(self):
        result = self.run_on("cortex-a57")
        self.assertEqual(result.returncode, -4, result.stdout + result.stderr)

    def test_trapping_alone_runs_every_form_on_an_armv8_0_cpu(self):
        result = self.run_on("cortex-a57", f"LD_PRELOAD={self.library}", "LSE_EMU=1", "LSE_EMU_STATS=1")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("lse ok", result.stdout)
        self.assertRegex(result.stderr, r"traps=[1-9]\d* patched=0 ")

    def test_patching_runs_every_form_on_an_armv8_0_cpu(self):
        # qemu-user write-protects translated code pages and races when other threads run them while the
        # stub arena is written, so threaded patching is only meaningful on hardware.
        result = self.run_on("cortex-a57", f"LD_PRELOAD={self.library}", "LSE_EMU=1", "LSE_EMU_PATCH=1",
                             "LSE_EMU_STATS=1", threads=1)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("lse ok", result.stdout)
        self.assertRegex(result.stderr, r"patched=[1-9]\d* failed=0")

    def test_it_stays_off_without_the_switch(self):
        result = self.run_on("cortex-a57", f"LD_PRELOAD={self.library}")
        self.assertEqual(result.returncode, -4, result.stdout + result.stderr)

    def test_it_stays_off_on_a_cpu_with_lse(self):
        result = self.run_on("neoverse-n1", f"LD_PRELOAD={self.library}", "LSE_EMU=1", "LSE_EMU_STATS=1")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn("traps=", result.stderr)


if __name__ == "__main__":
    unittest.main()
