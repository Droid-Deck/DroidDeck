import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


TOOLS = Path(__file__).resolve().parents[1]
LAYER = TOOLS / "linuxfs/xclipse-layer"


class XclipseLayerTest(unittest.TestCase):
    def test_layer_against_a_mock_loader_and_driver(self):
        compiler = shutil.which(os.environ.get("CC", "cc"))
        if not compiler:
            self.fail("A C compiler is required for the Xclipse layer regression test")
        # The build compiles the layer against the distribution's headers (libvulkan-dev);
        # VULKAN_INCLUDE points elsewhere, e.g. a Vulkan-Headers checkout on macOS.
        include = Path(os.environ.get("VULKAN_INCLUDE", "/usr/include"))
        if not (include / "vulkan/vk_layer.h").is_file():
            self.fail("Vulkan headers are required: install libvulkan-dev or set VULKAN_INCLUDE")
        with tempfile.TemporaryDirectory(prefix="droiddeck-xclipse-") as tmp:
            program = Path(tmp) / "xclipse-layer"
            built = subprocess.run([compiler, "-std=gnu11", "-Wall", "-Wextra", "-Werror", "-Wno-unused-parameter",
                                    "-UNDEBUG", "-pthread", "-I", str(LAYER), "-I", str(include),
                                    str(Path(__file__).with_name("xclipse_layer_test.c")), "-ldl", "-o", str(program)],
                                   capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stdout + built.stderr)
            ran = subprocess.run([str(program)], capture_output=True, text=True, timeout=30)
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
            self.assertIn("xclipse layer ok", ran.stdout)

    def test_device_chain_table_matches_its_generator(self):
        registry = os.environ.get("VULKAN_REGISTRY")
        if not registry:
            self.skipTest("set VULKAN_REGISTRY to a Vulkan-Headers 1.3.275 checkout to recheck device_chain.inc")
        with tempfile.TemporaryDirectory(prefix="droiddeck-chain-") as tmp:
            output = Path(tmp) / "device_chain.inc"
            subprocess.run(["python3", str(LAYER / "gen_device_chain.py"), str(Path(registry) / "registry/vk.xml"),
                            str(Path(registry) / "include/vulkan/vulkan_core.h"), str(output)],
                           check=True, capture_output=True)
            self.assertEqual(output.read_text(), (LAYER / "device_chain.inc").read_text())


if __name__ == "__main__":
    unittest.main()
