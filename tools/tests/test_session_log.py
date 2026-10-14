import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SESSION = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-session"


def log_redirect():
    match = re.search(r"^\s*(exec > >\(.*\) 2>&1)$", SESSION.read_text(), re.M)
    return match.group(1)


class SessionLogTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.log = self.tmp / "session.log"

    def run_session(self, body, cap=None):
        script = "BL_LOG=%s\n%s%s\n%s\n" % (self.log, "BL_LOG_CAP=%d\n" % cap if cap else "", log_redirect(), body)
        subprocess.run(["bash", "-c", script], timeout=30, check=False)

    def test_the_last_lines_survive_the_writer_being_killed(self):
        self.run_session('for i in $(seq 1 60); do echo "line $i"; done\nsleep 0.5\n'
                         'for p in $(pgrep -P $$); do kill -9 $(pgrep -P $p) $p 2>/dev/null; done')
        lines = self.log.read_text().splitlines()
        self.assertEqual(60, len(lines))
        self.assertEqual("line 60", lines[-1])

    def test_the_log_keeps_its_first_bytes_and_drains_the_rest(self):
        self.run_session("printf 'abcdefghij\\nklmnop\\n'\nsleep 0.5", cap=5)
        self.assertEqual("abcde", self.log.read_text())


if __name__ == "__main__":
    unittest.main()
