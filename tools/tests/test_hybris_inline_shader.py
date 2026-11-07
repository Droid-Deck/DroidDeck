import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

if __package__:
    from .test_hybris_vendor_compat import added_file, vulkan_include
else:
    from test_hybris_vendor_compat import added_file, vulkan_include


ROOT = Path(__file__).resolve().parents[2]
PATCH = ROOT / "tools/hybris/patches/0005-inline-shader-modules.patch"
HELPER = Path(__file__).with_name("hybris_inline_shader_test.c")


class HybrisInlineShaderTest(unittest.TestCase):
    def test_inline_module_ownership_and_failure_cleanup(self):
        compiler = shutil.which(os.environ.get("CC", "cc"))
        self.assertIsNotNone(compiler, "a C compiler is required")
        include = vulkan_include()
        self.assertTrue((include / "vulkan/vulkan.h").is_file(),
                        "Vulkan headers are required")
        with tempfile.TemporaryDirectory(prefix="droiddeck-inline-shader-") as directory:
            root = Path(directory)
            for name in ("inline_shader.c", "inline_shader.h"):
                (root / name).write_text(added_file(PATCH, "hybris/vulkan/compat/" + name))
            (root / "scaled_vertex.h").write_text(
                "#pragma once\n#include <vulkan/vulkan.h>\n#include <stddef.h>\n"
                "void *hybris_scaled_alloc(const VkAllocationCallbacks *, size_t, VkSystemAllocationScope);\n"
                "void hybris_scaled_free(const VkAllocationCallbacks *, void *);\n")
            program = root / "inline-shader-test"
            built = subprocess.run([
                compiler, "-std=gnu11", "-Wall", "-Wextra", "-Werror",
                "-I", str(root), "-I", str(include), str(HELPER),
                str(root / "inline_shader.c"), "-o", str(program)],
                capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stdout + built.stderr)
            ran = subprocess.run([str(program)], capture_output=True, text=True, timeout=30)
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
            self.assertIn("hybris inline shader modules ok", ran.stdout)


if __name__ == "__main__":
    unittest.main()
