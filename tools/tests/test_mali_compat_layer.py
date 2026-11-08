import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
LAYER = ROOT / "tools/venus/layer"
DEPS = ROOT / "build/layer-deps"


def include(variable, default):
    return Path(os.environ.get(variable, str(default)))


class MaliCompatLayerTest(unittest.TestCase):
    def test_dual_source_blending_against_a_mock_loader_and_driver(self):
        compiler = shutil.which(os.environ.get("CC", "cc"))
        if not compiler:
            self.fail("A C compiler is required for the Mali compatibility layer test")
        vulkan = include("MALI_COMPAT_VULKAN_INCLUDE", DEPS / "vulkan/include")
        spirv = include("MALI_COMPAT_SPIRV_INCLUDE", DEPS / "spirv/include")
        bcdec = include("MALI_COMPAT_BCDEC_INCLUDE", DEPS / "bcdec")
        missing = [str(path) for path in (vulkan / "vulkan/vk_layer.h", spirv / "spirv/unified1/spirv.h",
                                          bcdec / "bcdec.h") if not path.is_file()]
        if missing:
            message = "Vulkan 1.4, SPIR-V and bcdec headers are required: " + ", ".join(missing)
            if os.environ.get("CI"):
                self.fail(message)
            self.skipTest(message)
        with tempfile.TemporaryDirectory(prefix="droiddeck-mali-compat-") as tmp:
            program = Path(tmp) / "mali-compat-layer"
            built = subprocess.run([compiler, "-std=gnu11", "-Wall", "-Wextra", "-Werror", "-Wno-unused-parameter",
                                    "-UNDEBUG", "-pthread", "-I", str(LAYER), "-I", str(vulkan), "-I", str(spirv),
                                    "-I", str(bcdec), str(Path(__file__).with_name("mali_compat_layer_test.c")),
                                    *sorted(str(source) for source in LAYER.glob("*.c")), "-o", str(program)],
                                   capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stdout + built.stderr)
            ran = subprocess.run([str(program), tmp], capture_output=True, text=True, timeout=30)
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
            self.assertIn("mali compat layer ok", ran.stdout)
            self.assertIn("emulating dualSrcBlend for mock", ran.stderr)
            validator = shutil.which("spirv-val")
            if not validator:
                return
            for name, environment in (("dual-1.0.spv", "vulkan1.0"), ("plain-1.0.spv", "vulkan1.0"),
                                      ("stripped-1.0.spv", "vulkan1.0"), ("stripped-1.4.spv", "vulkan1.2")):
                checked = subprocess.run([validator, "--target-env", environment, str(Path(tmp) / name)],
                                         capture_output=True, text=True)
                self.assertEqual(checked.returncode, 0, name + ": " + checked.stdout + checked.stderr)


if __name__ == "__main__":
    unittest.main()
