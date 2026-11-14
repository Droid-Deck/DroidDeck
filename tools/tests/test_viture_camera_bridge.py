import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
COMPILER = shutil.which("c++") or shutil.which("g++")


@unittest.skipUnless(COMPILER, "C++ compiler required")
class VitureCameraBridgeTest(unittest.TestCase):
    def test_stream_lifecycle_and_latest_frame(self):
        with tempfile.TemporaryDirectory() as directory:
            executable = Path(directory) / "viture-camera-test"
            subprocess.run([
                COMPILER, "-std=c++17", "-Wall", "-Wextra", "-Werror", "-pthread",
                "-I", str(ROOT / "app/src/test/cpp/viture_camera_fake"),
                str(ROOT / "app/src/test/cpp/viture_camera_bridge_test.cpp"),
                str(ROOT / "app/src/main/cpp/viture/sdk_camera_bridge.cpp"),
                "-o", str(executable),
            ], check=True)
            subprocess.run([str(executable)], check=True)


if __name__ == "__main__":
    unittest.main()
