import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
COMPILER = shutil.which("c++") or shutil.which("g++")


@unittest.skipUnless(COMPILER, "C++ compiler required")
class VitureSdkLoaderTest(unittest.TestCase):
    def test_loading_and_failure_recovery(self):
        with tempfile.TemporaryDirectory() as directory:
            executable = Path(directory) / "viture-sdk-loader-test"
            subprocess.run([
                COMPILER, "-std=c++17", "-Wall", "-Wextra", "-Werror",
                "-I", str(ROOT / "app/src/test/cpp/viture_loader_fake"),
                str(ROOT / "app/src/test/cpp/viture_sdk_loader_test.cpp"),
                str(ROOT / "app/src/main/cpp/viture/sdk_loader.cpp"),
                "-o", str(executable),
            ], check=True)
            subprocess.run([str(executable)], check=True)


if __name__ == "__main__":
    unittest.main()
