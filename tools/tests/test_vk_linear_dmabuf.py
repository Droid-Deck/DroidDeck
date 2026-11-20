import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from tools.tests.test_vk_external_image_sync import vulkan_include_args


class VkLinearDmabufTest(unittest.TestCase):
    def test_linear_dmabuf_copy(self):
        compiler = shutil.which(os.environ.get("CC", "cc"))
        if not compiler:
            self.fail("A C compiler is required for the linear dma-buf copy regression test")

        tests = Path(__file__).resolve().parent
        source = tests / "vk_linear_dmabuf_test.c"
        includes = tests.parents[1] / "app/src/main/cpp/waylandcomp/src"
        with tempfile.TemporaryDirectory(prefix="droiddeck-vk-linear-") as tmp:
            program = Path(tmp) / "vk-linear-dmabuf"
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
