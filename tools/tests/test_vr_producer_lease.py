import ctypes.util
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


@unittest.skipUnless(shutil.which("gcc"), "needs gcc on a Linux host")
class VrProducerLeaseTest(unittest.TestCase):
    def compile_and_run(self, source, headers=None, gpu=False):
        repository = Path(__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory() as directory:
            executable = Path(directory) / "producer-test"
            command = ["gcc", "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-Wno-unused-parameter", "-pthread"]
            if headers:
                command += ["-I" + str(headers)]
            command += [str(repository / "tools/vr/tests" / source), "-ldl"]
            if gpu:
                command += ["-l:libvulkan.so.1"]
            subprocess.run(command + ["-o", str(executable)], check=True)
            environment = dict(os.environ)
            if gpu:
                manifests = sorted(Path("/usr/share/vulkan/icd.d").glob("lvp_icd*.json"))
                environment["VK_DRIVER_FILES"] = str(manifests[0])
                environment["VK_ICD_FILENAMES"] = str(manifests[0])
            result = subprocess.run([str(executable)], env=environment, capture_output=True, text=True, timeout=20)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            return result.stdout

    def test_protocol_and_retained_ownership(self):
        self.assertIn("disconnect quarantine passed", self.compile_and_run("test_producer_lease.c"))

    def test_real_gpu_completion_and_frozen_overwrite(self):
        if not ctypes.util.find_library("vulkan") or not list(Path("/usr/share/vulkan/icd.d").glob("lvp_icd*.json")):
            self.skipTest("needs Vulkan loader and Lavapipe")
        repository = Path(__file__).resolve().parents[2]
        candidates = [os.environ.get("VK_INCLUDE", ""), "/usr/include", repository / "build/vr/include"]
        candidates += [path for root in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")) if root
                       for path in Path(root).glob("ndk/*/toolchains/llvm/prebuilt/*/sysroot/usr/include")]
        headers = next((Path(path) for path in candidates if path and (Path(path) / "vulkan/vk_layer.h").is_file()), None)
        if headers is None:
            self.skipTest("needs Vulkan headers")
        self.assertIn("1024 buffer values passed", self.compile_and_run("test_producer_gpu.c", headers=headers, gpu=True))
