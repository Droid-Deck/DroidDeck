import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
LAYER = ROOT / "tools/venus/layer"
DEPS = ROOT / "build/layer-deps"
HARNESS = Path(__file__).with_name("mali_compat_spirv_test.c")


def include(variable, default):
    return Path(os.environ.get(variable, str(default)))


class MaliCompatSpirvTest(unittest.TestCase):
    def test_the_layer_lowers_a_module_to_the_version_the_driver_knows(self):
        compiler = shutil.which(os.environ.get("CC", "cc"))
        if not compiler:
            self.fail("A C compiler is required for the Mali compatibility layer test")
        vulkan = include("MALI_COMPAT_VULKAN_INCLUDE", DEPS / "vulkan/include")
        spirv = include("MALI_COMPAT_SPIRV_INCLUDE", DEPS / "spirv/include")
        missing = [str(path) for path in (vulkan / "vulkan/vk_layer.h", spirv / "spirv/unified1/spirv.h")
                  if not path.is_file()]
        if missing:
            message = "Vulkan 1.4 and SPIR-V headers are required: " + ", ".join(missing)
            if os.environ.get("CI"):
                self.fail(message)
            self.skipTest(message)
        with tempfile.TemporaryDirectory(prefix="droiddeck-mali-spirv-") as tmp:
            program = Path(tmp) / "mali-compat-spirv"
            module = Path(tmp) / "capped.spv"
            guest = Path(tmp) / "guest.spv"
            built = subprocess.run(
                [compiler, "-std=gnu11", "-Wall", "-Wextra", "-Werror", "-Wno-unused-parameter", "-UNDEBUG",
                 "-pthread", "-I", str(LAYER), "-I", str(vulkan), "-I", str(spirv),
                 str(HARNESS), str(LAYER / "spirv.c"), "-o", str(program)],
                capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stdout + built.stderr)
            ran = subprocess.run([str(program), str(module), str(guest)], capture_output=True, text=True, timeout=30)
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
            self.assertIn("mali compat spirv ok", ran.stdout)
            self.assertTrue(module.is_file())
            self.assertTrue(guest.is_file())
            validator = shutil.which("spirv-val")
            if not validator:
                return
            for lowered in (module, guest):
                checked = subprocess.run([validator, "--target-env", "vulkan1.1", str(lowered)],
                                         capture_output=True, text=True)
                self.assertEqual(checked.returncode, 0, checked.stdout + checked.stderr)


if __name__ == "__main__":
    unittest.main()
