import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

if __package__:
    from .test_vk_external_image_sync import vulkan_include_args
else:
    from test_vk_external_image_sync import vulkan_include_args

ROOT = Path(__file__).resolve().parents[2]
SOURCES = ROOT / "app/src/main/cpp/waylandcomp/src"


class VkOutputAhbTest(unittest.TestCase):
    def test_output_import_capabilities_and_cleanup(self):
        compiler = shutil.which(os.environ.get("CC", "cc"))
        self.assertIsNotNone(compiler, "A C compiler is required for the output-AHB regression test")
        source = (SOURCES / "vk_present.c").read_text()
        image_start = source.index("struct vkp_image {")
        image_end = source.index("\n};", image_start) + 3
        start = source.index("\nint vkp_output_ahb_supported(")
        end = source.index("\nstruct vkp_image *vkp_image_import_dmabuf(", start)
        destroy_start = source.index("\nvoid vkp_image_destroy(")
        destroy_end = source.index("\nstatic int map_draw(", destroy_start)
        with tempfile.TemporaryDirectory(prefix="droiddeck-output-ahb-") as directory:
            work = Path(directory)
            (work / "vk_output_ahb_under_test.inc").write_text(
                source[image_start:image_end] + source[start:end] + source[destroy_start:destroy_end]
            )
            binary = work / "vk-output-ahb"
            command = [compiler, "-std=c11", "-Wall", "-Wextra", "-Werror", "-UNDEBUG",
                       "-I", str(work), "-I", str(SOURCES), *vulkan_include_args(),
                       str(ROOT / "tools/tests/vk_output_ahb_test.c"), "-o", str(binary)]
            built = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stdout + built.stderr)
            ran = subprocess.run([str(binary)], capture_output=True, text=True, timeout=30)
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)


    def test_output_pool_native_and_legacy_fallbacks(self):
        compiler = shutil.which(os.environ.get("CC", "cc"))
        self.assertIsNotNone(compiler, "A C compiler is required for the output pool regression test")
        source = (SOURCES / "sc_layer.c").read_text()
        start = source.index("\nstatic int sniff_modifier(")
        end = source.index("\nstatic int fence_signalled(", start)
        with tempfile.TemporaryDirectory(prefix="droiddeck-output-pool-") as directory:
            work = Path(directory)
            (work / "sc_output_pool_under_test.inc").write_text(source[start:end])
            binary = work / "sc-output-pool"
            command = [compiler, "-std=c11", "-Wall", "-Wextra", "-Werror", "-UNDEBUG",
                       "-fsanitize=address,undefined", "-I", str(work),
                       str(ROOT / "tools/tests/sc_output_pool_test.c"), "-o", str(binary)]
            built = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stdout + built.stderr)
            ran = subprocess.run([str(binary)], capture_output=True, text=True, timeout=30)
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)


if __name__ == "__main__":
    unittest.main()
