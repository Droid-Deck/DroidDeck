import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

if __package__:
    from .test_hybris_clip_shader import strict_c_flags
    from .test_hybris_vendor_compat import vulkan_include
else:
    from test_hybris_clip_shader import strict_c_flags
    from test_hybris_vendor_compat import vulkan_include

ROOT = Path(__file__).resolve().parents[2]


class HybrisMultiviewCullTest(unittest.TestCase):
    def compile_and_run(self, checkout, helper):
        compiler = shutil.which(os.environ.get("CC", "cc"))
        self.assertIsNotNone(compiler)
        source = checkout / "hybris/vulkan/compat"
        program = checkout.parent / helper.removesuffix(".c")
        built = subprocess.run([
            compiler, *strict_c_flags(compiler), "-I", str(vulkan_include()),
            "-I", str(source), "-I", str(checkout / "hybris/vulkan/layer"),
            str(ROOT / "tools/tests" / helper), "-o", str(program)],
            capture_output=True, text=True)
        self.assertEqual(built.returncode, 0, built.stdout + built.stderr)
        ran = subprocess.run([str(program)], capture_output=True, text=True, timeout=30)
        self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)

    def test_render_pass_and_dynamic_view_masks_are_tracked(self):
        source_value = os.environ.get("HYBRIS_CLIP_SOURCE")
        if not source_value:
            self.skipTest("set HYBRIS_CLIP_SOURCE to the pinned libhybris checkout")
        with tempfile.TemporaryDirectory(prefix="droiddeck-multiview-cull-") as directory:
            checkout = Path(directory) / "libhybris"
            subprocess.run(["git", "clone", "-q", "--no-hardlinks", source_value, str(checkout)], check=True)
            for patch in sorted((ROOT / "tools/hybris/patches").glob("*.patch")):
                subprocess.run(["git", "-C", str(checkout), "apply", "--check", str(patch)], check=True)
                subprocess.run(["git", "-C", str(checkout), "apply", str(patch)], check=True)
            self.compile_and_run(checkout, "hybris_multiview_renderpass_test.c")


if __name__ == "__main__":
    unittest.main()
