import os
import re
import shutil
import signal
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

SESSION = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-session"


def run_program_function():
    match = re.search(r"^    run_program\(\) \{\n.*?^    \}\n", SESSION.read_text(), re.M | re.S)
    return match.group(0)


class RunStatusTest(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.dir, True)

    def run_with(self, command, launch_dir=True):
        env = dict(os.environ)
        env.pop("BL_LAUNCH_DIR", None)
        if launch_dir:
            env["BL_LAUNCH_DIR"] = str(self.dir)
        return subprocess.run(["bash", "-c", run_program_function() + "run_program " + command + "; echo unreachable"],
                              env=env, capture_output=True, text=True, timeout=30)

    def test_the_program_status_is_kept_for_the_app(self):
        run = self.run_with("sh -c 'exit 7'")
        self.assertEqual(7, run.returncode)
        self.assertNotIn("unreachable", run.stdout)
        self.assertEqual("7\n", (self.dir / "run-status").read_text())
        self.assertFalse((self.dir / "run-status.tmp").exists())

    def test_a_clean_exit_is_recorded_as_zero(self):
        self.assertEqual(0, self.run_with("true").returncode)
        self.assertEqual("0\n", (self.dir / "run-status").read_text())

    def test_nothing_is_written_without_a_launch_dir(self):
        self.assertEqual(3, self.run_with("sh -c 'exit 3'", launch_dir=False).returncode)
        self.assertEqual([], os.listdir(self.dir))

    def signalled(self, sig, program):
        env = dict(os.environ, BL_LAUNCH_DIR=str(self.dir))
        ready = self.dir / "ready"
        proc = subprocess.Popen(["bash", "-c", run_program_function() + "run_program " + program],
                                env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.addCleanup(proc.kill)
        deadline = time.monotonic() + 10
        while not ready.exists() and time.monotonic() < deadline:
            time.sleep(0.05)
        self.assertTrue(ready.exists())
        proc.send_signal(sig)
        return proc.wait(timeout=10)

    def test_a_stop_signal_reaches_the_program(self):
        program = ("python3 -c 'import signal, sys, time; signal.signal(signal.SIGTERM, lambda *a: sys.exit(9)); "
                   "open(sys.argv[1], \"w\").close(); time.sleep(30)' %s" % (self.dir / "ready"))
        self.assertEqual(9, self.signalled(signal.SIGTERM, program))
        self.assertEqual("9\n", (self.dir / "run-status").read_text())

    def test_an_interrupt_reaches_the_program(self):
        program = ("python3 -c 'import sys, time; open(sys.argv[1], \"w\").close(); time.sleep(30)' %s"
                   % (self.dir / "ready"))
        self.assertEqual(130, self.signalled(signal.SIGINT, program))
        self.assertEqual("130\n", (self.dir / "run-status").read_text())


if __name__ == "__main__":
    unittest.main()
