import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class DrmHandleTest(unittest.TestCase):
    def test_handle_lifetime_and_device_scope(self):
        compiler = shutil.which(os.environ.get("CC", "cc"))
        if not compiler:
            self.fail("A C compiler is required for the DRM adapter regression test")
        source = Path(__file__).with_name("drm_handles_test.c")
        with tempfile.TemporaryDirectory(prefix="droiddeck-drm-") as tmp:
            program = Path(tmp) / "drm-handles"
            built = subprocess.run([compiler, "-std=gnu11", "-Wall", "-Wextra", "-Werror",
                            "-UNDEBUG", "-pthread", str(source), "-ldl", "-o", str(program)],
                           capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stdout + built.stderr)
            ran = subprocess.run([str(program)], capture_output=True, text=True, timeout=30)
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)


if __name__ == "__main__":
    unittest.main()
