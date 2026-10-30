import getpass
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

SU = Path(__file__).resolve().parents[1] / "ubuntu/overlay-runtime/usr/local/bin/su"


class SuWrapperTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.dir = Path(temp.name)
        sudo = self.dir / "sudo"
        sudo.write_text("#!/bin/bash\nprintf 'sudo'; printf ' <%s>' \"$@\"; echo\n")
        sudo.chmod(0o755)
        self.su = self.dir / "su"
        self.su.write_text(SU.read_text().replace("/usr/local/bin/sudo", str(sudo)))
        self.su.chmod(0o755)

    def run_su(self, *args):
        env = dict(os.environ, SHELL="/bin/bash")
        return subprocess.run([str(self.su), *args], capture_output=True, text=True, timeout=30, env=env)

    def test_a_command_after_the_user_reaches_the_root_shell(self):
        out = self.run_su("root", "-c", "echo hi")
        self.assertEqual("sudo <-s> <echo hi>\n", out.stdout)

    def test_the_current_user_is_not_elevated(self):
        out = self.run_su(getpass.getuser(), "-c", "echo as-me")
        if getpass.getuser() == "root":
            self.assertEqual("sudo <-s> <echo as-me>\n", out.stdout)
        else:
            self.assertEqual("as-me\n", out.stdout)

    def test_arguments_after_the_user_go_to_the_shell(self):
        out = self.run_su("-", "root", "-c", "echo $0 $1", "zero", "one")
        self.assertEqual("sudo <-i> </bin/bash> <-c> <echo $0 $1> <zero> <one>\n", out.stdout)

    def test_other_users_are_refused(self):
        out = self.run_su("droiddeck-nobody-here")
        self.assertEqual(1, out.returncode)
        self.assertEqual("", out.stdout)

    def test_plain_su_opens_a_root_shell(self):
        self.assertEqual("sudo <-s>\n", self.run_su().stdout)
        self.assertEqual("sudo <-i>\n", self.run_su("-").stdout)


if __name__ == "__main__":
    unittest.main()
