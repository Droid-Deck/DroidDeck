import re
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
PATCH = REPO / "tools/fex/patches/0017-fd-exec-keeps-argv0-and-path.patch"
SYSCALLS = "Source/Tools/LinuxEmulation/LinuxSyscalls/Syscalls.cpp"
INTERPRETER = "Source/Tools/FEXInterpreter/FEXInterpreter.cpp"


def lines(sign):
    return [line[1:].strip() for line in PATCH.read_text().splitlines() if line.startswith(sign) and not line.startswith(sign * 3)]


class FexFdExecTest(unittest.TestCase):
    def test_only_the_execve_handler_and_the_interpreter_change(self):
        self.assertEqual([INTERPRETER, SYSCALLS], re.findall(r"^diff --git a/(\S+) ", PATCH.read_text(), re.M))

    def test_a_descriptor_exec_keeps_the_callers_argv0(self):
        self.assertIn("if (!NeedsLoaderFD) {", lines("-"))
        self.assertIn("if (!NeedsLoaderFD && !IsFDExec) {", lines("+"))

    def test_an_anonymous_program_reports_its_recovered_path(self):
        self.assertIn('FEXCore::Config::Set(FEXCore::Config::CONFIG_APP_FILENAME, "<Anonymous>");', lines("-"))
        self.assertIn("FEXCore::Config::Set(FEXCore::Config::CONFIG_APP_FILENAME, Program.ProgramPath);", lines("+"))

    def test_the_config_name_stays_anonymous(self):
        self.assertNotIn('FEXCore::Config::Set(FEXCore::Config::CONFIG_APP_CONFIG_NAME, "<Anonymous>");', lines("-"))

    def test_no_comments_are_added(self):
        self.assertFalse([line for line in lines("+") if line.startswith(("//", "/*"))])


if __name__ == "__main__":
    unittest.main()
