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
PATCH = ROOT / "tools/hybris/patches/0011-zink-vertex-input-profile.patch"
HELPER = Path(__file__).with_name("hybris_zink_profile_test.c")


class HybrisZinkProfileTest(unittest.TestCase):
    def test_engine_rename_and_feature_mask(self):
        compiler = shutil.which(os.environ.get("CC", "cc"))
        self.assertIsNotNone(compiler, "a C compiler is required")
        include = vulkan_include()
        self.assertTrue((include / "vulkan/vulkan.h").is_file(),
                        "Vulkan headers are required")
        with tempfile.TemporaryDirectory(prefix="droiddeck-zink-profile-") as directory:
            root = Path(directory)
            for name in ("zink_profile.c", "zink_profile.h"):
                (root / name).write_text(added_file(PATCH, "hybris/vulkan/compat/" + name))
            program = root / "zink-profile-test"
            built = subprocess.run([
                compiler, "-std=gnu11", "-Wall", "-Wextra", "-Werror", "-pthread",
                "-I", str(root), "-I", str(include), str(HELPER),
                str(root / "zink_profile.c"), "-o", str(program)],
                capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stdout + built.stderr)
            powervr = "/vendor/lib64/hw/vulkan.powervr.so"
            for hal, switch, renamed in ((powervr, None, 1), (powervr, "0", 0),
                                         ("/vendor/lib64/hw/vulkan.mali.so", None, 0),
                                         ("/vendor/lib64/hw/vulkan.mali.so", "1", 1), (None, None, 0)):
                env = dict(os.environ)
                for key in ("HYBRIS_VULKAN_HAL", "HYBRIS_VULKAN_COMPAT_ZINK_PROFILE"):
                    env.pop(key, None)
                if hal is not None:
                    env["HYBRIS_VULKAN_HAL"] = hal
                if switch is not None:
                    env["HYBRIS_VULKAN_COMPAT_ZINK_PROFILE"] = switch
                ran = subprocess.run([str(program)], capture_output=True, text=True,
                                     timeout=30, env=env)
                self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
                self.assertIn(f"renamed={renamed}", ran.stdout, (hal, switch))
                self.assertIn("hybris zink profile ok", ran.stdout)


if __name__ == "__main__":
    unittest.main()
