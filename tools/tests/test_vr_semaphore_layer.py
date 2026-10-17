import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


@unittest.skipUnless(shutil.which("gcc"), "needs gcc on a Linux host")
class VrSemaphoreLayerTest(unittest.TestCase):
    def test_waits_are_never_fabricated(self):
        repository = Path(__file__).resolve().parents[2]
        candidates = [os.environ.get("VK_INCLUDE", ""), "/usr/include", repository / "build/vr/include"]
        candidates += [path for root in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")) if root
                       for path in Path(root).glob("ndk/*/toolchains/llvm/prebuilt/*/sysroot/usr/include")]
        headers = next((Path(path) for path in candidates if path and (Path(path) / "vulkan/vk_layer.h").is_file()), None)
        if headers is None:
            self.skipTest("needs Vulkan headers")
        with tempfile.TemporaryDirectory() as directory:
            executable = Path(directory) / "semaphore-layer-test"
            subprocess.run(["gcc", "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-Wno-unused-parameter",
                            "-pthread", "-I" + str(headers), str(repository / "tools/vr/tests/test_semaphore_layer.c"),
                            "-ldl", "-o", str(executable)], check=True)
            result = subprocess.run([str(executable)], capture_output=True, text=True, timeout=20)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("fd ownership passed", result.stdout)
