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
PATCH = ROOT / "tools/hybris/patches/0010-strip-spirv-names.patch"
HELPER = Path(__file__).with_name("hybris_strip_names_test.c")


class HybrisStripNamesTest(unittest.TestCase):
    def test_names_dropped_and_switch(self):
        compiler = shutil.which(os.environ.get("CC", "cc"))
        self.assertIsNotNone(compiler, "a C compiler is required")
        include = vulkan_include()
        self.assertTrue((include / "vulkan/vulkan.h").is_file(),
                        "Vulkan headers are required")
        with tempfile.TemporaryDirectory(prefix="droiddeck-strip-names-") as directory:
            root = Path(directory)
            for name in ("spirv_names.c", "spirv_names.h"):
                (root / name).write_text(added_file(PATCH, "hybris/vulkan/compat/" + name))
            (root / "scaled_vertex.h").write_text(
                "#pragma once\n#include <vulkan/vulkan.h>\n#include <stddef.h>\n"
                "void *hybris_scaled_alloc(const VkAllocationCallbacks *, size_t, VkSystemAllocationScope);\n"
                "void hybris_scaled_free(const VkAllocationCallbacks *, void *);\n")
            program = root / "strip-names-test"
            built = subprocess.run([
                compiler, "-std=gnu11", "-Wall", "-Wextra", "-Werror", "-pthread",
                "-I", str(root), "-I", str(include), str(HELPER),
                str(root / "spirv_names.c"), "-o", str(program)],
                capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stdout + built.stderr)
            for value, enabled in ((None, 1), ("1", 1), ("0", 0)):
                env = dict(os.environ)
                env.pop("HYBRIS_VULKAN_COMPAT_STRIP_NAMES", None)
                if value is not None:
                    env["HYBRIS_VULKAN_COMPAT_STRIP_NAMES"] = value
                ran = subprocess.run([str(program)], capture_output=True, text=True,
                                     timeout=30, env=env)
                self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
                self.assertIn(f"enabled={enabled}", ran.stdout)
                self.assertIn("hybris strip names ok", ran.stdout)


if __name__ == "__main__":
    unittest.main()
