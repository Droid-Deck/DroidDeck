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
            extension = Path(tmp) / "extension.spv"
            scalar = Path(tmp) / "scalar.spv"
            debug = Path(tmp) / "debug.spv"
            private_readonly = Path(tmp) / "private-readonly.spv"
            parameter = Path(tmp) / "parameter.spv"
            converted = Path(tmp) / "converted.spv"
            fixed = Path(tmp) / "fixed.spv"
            built = subprocess.run(
                [compiler, "-std=gnu11", "-Wall", "-Wextra", "-Werror", "-Wno-unused-parameter", "-UNDEBUG",
                 "-pthread", "-Dcalloc=test_calloc", "-I", str(LAYER), "-I", str(vulkan), "-I", str(spirv),
                 str(HARNESS), str(LAYER / "spirv.c"), str(LAYER / "pipeline.c"), "-o", str(program)],
                capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stdout + built.stderr)
            ran = subprocess.run([str(program), str(module), str(guest), str(extension), str(scalar), str(debug),
                                  str(private_readonly), str(converted), str(parameter), str(fixed)],
                                 capture_output=True, text=True, timeout=30)
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
            self.assertIn("mali compat spirv ok", ran.stdout)
            self.assertTrue(module.is_file())
            self.assertTrue(guest.is_file())
            self.assertTrue(extension.is_file())
            self.assertTrue(scalar.is_file())
            self.assertTrue(debug.is_file())
            self.assertTrue(fixed.is_file())
            validator = shutil.which("spirv-val")
            if not validator:
                return
            for original in (private_readonly, converted):
                checked = subprocess.run([validator, "--target-env", "vulkan1.3", str(original)],
                                         capture_output=True, text=True)
                self.assertEqual(checked.returncode, 0, checked.stdout + checked.stderr)
                downgraded = bytearray(original.read_bytes())
                downgraded[4:8] = (0x00010300).to_bytes(4, "little")
                offset = 20
                while True:
                    instruction = int.from_bytes(downgraded[offset:offset + 4], "little")
                    length, opcode = instruction >> 16, instruction & 0xffff
                    if opcode == 15:
                        downgraded[offset:offset + 4] = (((length - 1) << 16) | opcode).to_bytes(4, "little")
                        del downgraded[offset + (length - 1) * 4:offset + length * 4]
                        break
                    offset += length * 4
                original.write_bytes(downgraded)
                checked = subprocess.run([validator, "--target-env", "vulkan1.1", str(original)],
                                         capture_output=True, text=True)
                self.assertNotEqual(checked.returncode, 0, "the 1.3 form must be rejected")
                expected = "NonWritable" if original == private_readonly else "UConvert"
                self.assertIn(expected, checked.stdout + checked.stderr)
            for lowered in (module, guest, extension, scalar, debug, parameter, fixed):
                checked = subprocess.run([validator, "--target-env", "vulkan1.1", str(lowered)],
                                         capture_output=True, text=True)
                self.assertEqual(checked.returncode, 0, checked.stdout + checked.stderr)


if __name__ == "__main__":
    unittest.main()
