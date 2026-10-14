import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[1] / "linuxfs/fex/faultreport.c"
PROGRAM = 'int main(void) { __asm__ volatile("ud2"); return 0; }\n'
FLAGS = ["-shared", "-fPIC", "-O2", "-Wall", "-Wextra", "-Werror", "-nostdlib", "-fno-stack-protector", "-fno-builtin",
         "-fvisibility=hidden"]


@unittest.skipUnless(shutil.which("gcc") and os.uname().machine == "x86_64", "needs gcc on an x86-64 host")
class FaultReportTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        (self.tmp / "program.c").write_text(PROGRAM)

    def build(self, bits):
        library, program = self.tmp / ("libfaultreport%s.so" % bits), self.tmp / ("program%s" % bits)
        if subprocess.run(["gcc", "-m" + bits, "-o", str(program), str(self.tmp / "program.c")], capture_output=True).returncode:
            self.skipTest("gcc cannot build %s-bit programs here" % bits)
        made = subprocess.run(["gcc", "-m" + bits] + FLAGS + ["-o", str(library), str(SOURCE)], capture_output=True, text=True)
        self.assertEqual(made.returncode, 0, made.stderr)
        return library, program

    def check(self, bits):
        library, program = self.build(bits)
        result = subprocess.run([str(program)], env=dict(os.environ, LD_PRELOAD=str(library)), capture_output=True, text=True)
        self.assertEqual(result.returncode, -4)
        self.assertRegex(result.stderr, r"droiddeck-fex: SIGILL at 0x[0-9a-f]+ \(si_code 02\), offset 0x[0-9a-f]+, bytes 0f 0b ")
        self.assertIn("program%s" % bits, result.stderr)

    def test_an_x86_64_program_reports_its_illegal_instruction(self):
        self.check("64")

    def test_an_i386_program_reports_its_illegal_instruction(self):
        self.check("32")


if __name__ == "__main__":
    unittest.main()
