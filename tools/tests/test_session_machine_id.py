import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SESSION = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-session"
FRESH = "0123456789abcdef0123456789abcdef"


class SessionMachineIdTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root, True)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.file = self.root / "machine-id"

    def generator(self, output, status=0):
        tool = self.bin / "dbus-uuidgen"
        tool.write_text("#!/bin/sh\necho %s\nexit %d\n" % (output, status))
        tool.chmod(0o755)

    def ensure(self):
        match = re.search(r"^ensure_machine_id\(\) \{\n.*?^\} 2>/dev/null\n", SESSION.read_text(), re.M | re.S)
        script = match.group(0).replace("/etc/machine-id", str(self.file)) + "ensure_machine_id\n"
        subprocess.run(["bash", "-c", script], env={"PATH": "%s:/usr/bin:/bin" % self.bin}, timeout=30, check=True)
        return self.file.read_text() if self.file.exists() else None

    def test_the_empty_id_shipped_in_the_runtime_gets_a_fresh_one(self):
        self.file.write_text("")
        self.generator(FRESH)
        self.assertEqual(FRESH + "\n", self.ensure())
        self.assertEqual([self.file.name], [p.name for p in self.root.iterdir() if p.is_file()])

    def test_a_missing_or_malformed_id_is_replaced(self):
        self.generator(FRESH)
        self.assertEqual(FRESH + "\n", self.ensure())
        self.file.write_text("uninitialized\n")
        self.assertEqual(FRESH + "\n", self.ensure())
        self.file.write_text("fedcba9876543210fedcba9876543210\nextra\n")
        self.assertEqual(FRESH + "\n", self.ensure())
        self.file.write_text("0" * 32 + "\n")
        self.assertEqual(FRESH + "\n", self.ensure())

    def test_a_valid_id_is_never_changed(self):
        self.file.write_text("fedcba9876543210fedcba9876543210\n")
        self.generator(FRESH)
        self.assertEqual("fedcba9876543210fedcba9876543210\n", self.ensure())

    def test_without_a_usable_generator_the_file_is_left_alone(self):
        self.file.write_text("")
        self.generator(FRESH, status=1)
        self.assertEqual("", self.ensure())
        self.generator("not-an-id")
        self.assertEqual("", self.ensure())


if __name__ == "__main__":
    unittest.main()
