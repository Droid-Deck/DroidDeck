"""Compile and exercise production BC push-descriptor interception from a clean source."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
HELPER = Path(__file__).with_name("hybris_push_descriptors_test.c")


def vulkan_include():
    for path in (Path(os.environ.get("VULKAN_INCLUDE", "")), Path("/usr/include"),
                 Path(sys.base_prefix) / "include"):
        if (path / "vulkan/vulkan.h").is_file():
            return path
    return Path("/usr/include")


class HybrisPushDescriptorTest(unittest.TestCase):
    def test_patched_production_push_descriptor_journal(self):
        source_value = os.environ.get("HYBRIS_PUSH_SOURCE")
        if not source_value:
            self.skipTest("set HYBRIS_PUSH_SOURCE to the clean pinned libhybris checkout")
        source = Path(source_value)
        self.assertTrue((source / ".git").exists())
        self.assertTrue((source / "hybris/vulkan/compat/bc_commands.c").is_file())
        self.assertEqual(subprocess.run(["git", "-C", str(source), "status", "--porcelain"],
                                        capture_output=True, text=True, check=True).stdout, "")
        compiler = shutil.which(os.environ.get("CC", "cc"))
        self.assertIsNotNone(compiler)
        include = vulkan_include()
        self.assertTrue((include / "vulkan/vulkan.h").is_file())
        with tempfile.TemporaryDirectory(prefix="droiddeck-hybris-push-") as directory:
            checkout = Path(directory) / "libhybris"
            subprocess.run(["git", "clone", "-q", "--no-hardlinks", str(source), str(checkout)], check=True)
            for patch in sorted((ROOT / "tools/hybris/patches").glob("*.patch")):
                checked = subprocess.run(["git", "-C", str(checkout), "apply", "--check", str(patch)],
                                         capture_output=True, text=True)
                self.assertEqual(checked.returncode, 0, checked.stdout + checked.stderr)
                subprocess.run(["git", "-C", str(checkout), "apply", str(patch)], check=True)
            compat = checkout / "hybris/vulkan/compat"
            layer = checkout / "hybris/vulkan/layer"
            program = Path(directory) / "push-descriptor-test"
            built = subprocess.run([
                compiler, "-std=gnu11", "-Wall", "-Wextra", "-Werror", "-Wno-unused-parameter",
                "-Dcalloc=push_test_calloc", "-I", str(include), "-I", str(compat), "-I", str(layer),
                str(HELPER), str(compat / "bc_commands.c"), str(compat / "bc_command_state.c"),
                str(compat / "bc_policy.c"),
                "-pthread", "-o", str(program)], capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stdout + built.stderr)
            ran = subprocess.run([str(program)], capture_output=True, text=True, timeout=30)
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
            self.assertIn("hybris push descriptor journal ok", ran.stdout)


if __name__ == "__main__":
    unittest.main()
