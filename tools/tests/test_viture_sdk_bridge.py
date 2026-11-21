import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
COMPILER = shutil.which("c++") or shutil.which("g++")
JAVAC = shutil.which("javac")
JAVA_HOME = Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else (
    Path(JAVAC).resolve().parent.parent if JAVAC else None
)
JNI_INCLUDE = JAVA_HOME / "include" if JAVA_HOME else None


@unittest.skipUnless(
    sys.platform.startswith("linux") and COMPILER and JNI_INCLUDE and
    (JNI_INCLUDE / "jni.h").is_file() and (JNI_INCLUDE / "linux/jni_md.h").is_file(),
    "Linux C++ compiler and JDK headers required",
)
class VitureSdkBridgeTest(unittest.TestCase):
    def test_native_display_preserves_tracking_ownership(self):
        with tempfile.TemporaryDirectory() as directory:
            executable = Path(directory) / "viture-sdk-bridge-test"
            subprocess.run([
                COMPILER, "-std=c++17", "-Wall", "-Wextra", "-Werror", "-pthread",
                "-I", str(JNI_INCLUDE), "-I", str(JNI_INCLUDE / "linux"),
                "-I", str(ROOT / "app/src/test/cpp/viture_bridge_fake"),
                str(ROOT / "app/src/test/cpp/viture_sdk_bridge_test.cpp"),
                str(ROOT / "app/src/main/cpp/viture/sdk_loader.cpp"),
                "-ldl", "-o", str(executable),
            ], check=True)
            subprocess.run([str(executable)], check=True)


if __name__ == "__main__":
    unittest.main()
