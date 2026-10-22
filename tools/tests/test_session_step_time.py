import re
import subprocess
import unittest
from pathlib import Path

SESSION = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-session"


def step_function():
    match = re.search(r"^step\(\) \{.*\}$", SESSION.read_text(), re.M)
    return match.group(0)


class SessionStepTimeTest(unittest.TestCase):
    def test_a_step_line_carries_milliseconds_before_its_text(self):
        out = subprocess.run(["bash", "-c", step_function() + "\nstep starting the Steam client"],
                             capture_output=True, text=True, timeout=30).stdout.strip()
        self.assertRegex(out, r"^== STEP \d{2}:\d{2}:\d{2}\.\d{3} starting the Steam client$")

    def test_the_text_after_the_first_space_is_the_step_name(self):
        out = subprocess.run(["bash", "-c", step_function() + "\nstep checking the Steam client"],
                             capture_output=True, text=True, timeout=30).stdout.strip()
        self.assertEqual("checking the Steam client", out.removeprefix("== STEP ").split(" ", 1)[1])


if __name__ == "__main__":
    unittest.main()
