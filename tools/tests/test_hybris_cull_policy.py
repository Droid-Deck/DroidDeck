"""Exercise cull-only extension and device-feature filtering on patched libhybris."""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

if __package__:
    from .test_hybris_vendor_compat import vulkan_include
else:
    from test_hybris_vendor_compat import vulkan_include


ROOT = Path(__file__).resolve().parents[2]


class HybrisCullPolicyTest(unittest.TestCase):
    def test_cull_only_filtering_uses_actual_physical_and_device_policy(self):
        source_value = os.environ.get("HYBRIS_CLIP_SOURCE")
        if not source_value:
            self.skipTest("set HYBRIS_CLIP_SOURCE to the pinned libhybris checkout")
        compiler = shutil.which(os.environ.get("CC", "cc"))
        self.assertIsNotNone(compiler, "a C compiler is required")
        include = vulkan_include()
        self.assertTrue((include / "vulkan/vulkan.h").is_file(), "Vulkan headers are required")
        pristine = Path(source_value)
        self.assertTrue((pristine / "hybris/vulkan/layer/physical.c").is_file(),
                        "HYBRIS_CLIP_SOURCE must be a pristine pinned libhybris checkout")
        with tempfile.TemporaryDirectory(prefix="droiddeck-cull-policy-") as directory:
            checkout = Path(directory) / "libhybris"
            subprocess.run(["git", "clone", "-q", "--no-hardlinks", str(pristine), str(checkout)], check=True)
            for patch in sorted((ROOT / "tools/hybris/patches").glob("*.patch")):
                subprocess.run(["git", "-C", str(checkout), "apply", "--check", str(patch)], check=True)
                subprocess.run(["git", "-C", str(checkout), "apply", str(patch)], check=True)
            compat = checkout / "hybris/vulkan/compat"
            layer = checkout / "hybris/vulkan/layer"
            helper = ROOT / "tools/tests/hybris_cull_policy_test.c"
            program = Path(directory) / "cull-policy-test"
            built = subprocess.run([
                compiler, "-std=gnu11", "-Wall", "-Wextra", "-Werror", "-Wno-unused-parameter",
                "-Wno-tautological-constant-out-of-range-compare",
                "-I", str(include), "-I", str(compat), "-I", str(layer), str(helper),
                str(compat / "shader_policy.c"), str(layer / "physical.c"),
                "-o", str(program)], capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stdout + built.stderr)
            ran = subprocess.run([str(program)], capture_output=True, text=True, timeout=30)
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)


if __name__ == "__main__":
    unittest.main()
