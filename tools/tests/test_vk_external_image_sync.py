import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


def vulkan_include_args():
    candidates = []
    for name in ("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT"):
        if os.environ.get(name):
            candidates.append(Path(os.environ[name]))

    android_home = os.environ.get("ANDROID_HOME")
    if android_home:
        ndk_root = Path(android_home) / "ndk"
        version = os.environ.get("NDK_VERSION")
        if version:
            candidates.append(ndk_root / version)
        if ndk_root.is_dir():
            candidates.extend(sorted(ndk_root.iterdir(), reverse=True))

    for ndk in candidates:
        include = ndk / "toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include"
        if (include / "vulkan/vulkan.h").is_file():
            return ["-idirafter", str(include)]
    return []


class VkExternalImageSyncTest(unittest.TestCase):
    def test_client_barrier_pair(self):
        compiler = shutil.which(os.environ.get("CC", "cc"))
        if not compiler:
            self.fail("A C compiler is required for the Vulkan image-sync regression test")

        tests = Path(__file__).resolve().parent
        source = tests / "vk_external_image_sync_test.c"
        includes = tests.parents[1] / "app/src/main/cpp/waylandcomp/src"
        with tempfile.TemporaryDirectory(prefix="droiddeck-vk-sync-") as tmp:
            program = Path(tmp) / "vk-external-image-sync"
            command = [
                compiler,
                "-std=c11",
                "-Wall",
                "-Wextra",
                "-Werror",
                "-UNDEBUG",
                "-I",
                str(includes),
                *vulkan_include_args(),
                str(source),
                "-o",
                str(program),
            ]
            built = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stdout + built.stderr)
            ran = subprocess.run([str(program)], capture_output=True, text=True, timeout=30)
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)


if __name__ == "__main__":
    unittest.main()
