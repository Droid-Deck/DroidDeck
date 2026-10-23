import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
PATCH = ROOT / "tools/dxvk/xclipse-patches/0001-check-direct3d-bc-formats.patch"
FIXTURE = Path(__file__).with_name("dxvk_xclipse_bc_formats_test.cpp")
TOOLCHAIN_FIXTURE = Path(__file__).with_name("dxvk_xclipse_toolchain_test.cpp")


def added_file(patch, name):
    lines = patch.read_text().splitlines()
    marker = f"diff --git a/{name} b/{name}"
    start = lines.index(marker)
    output = []
    in_hunk = False
    for line in lines[start + 1:]:
        if line.startswith("diff --git "):
            break
        if line.startswith("@@"):
            in_hunk = True
        elif in_hunk and line.startswith("+") and not line.startswith("+++"):
            output.append(line[1:])
        elif in_hunk and line.startswith(" "):
            output.append(line[1:])
    return "\n".join(output) + "\n"


def file_diff(patch, name):
    lines = patch.read_text().splitlines()
    marker = f"diff --git a/{name} b/{name}"
    start = lines.index(marker)
    end = next((i for i in range(start + 1, len(lines))
                if lines[i].startswith("diff --git ")), len(lines))
    return "\n".join(lines[start:end]) + "\n"


def vulkan_include():
    override = os.environ.get("VULKAN_INCLUDE")
    if override:
        return Path(override)
    for include in (Path("/usr/include"), Path(sys.base_prefix) / "include"):
        if (include / "vulkan/vulkan.h").is_file():
            return include
    return Path("/usr/include")


class DxvkXclipseBcFormatsTest(unittest.TestCase):
    def test_production_predicate_checks_all_direct3d_formats_and_usages(self):
        compiler = shutil.which(os.environ.get("CXX", "c++"))
        self.assertIsNotNone(compiler, "a C++ compiler is required")
        include = vulkan_include()
        self.assertTrue((include / "vulkan/vulkan.h").is_file(), "Vulkan headers are required")
        with tempfile.TemporaryDirectory(prefix="droiddeck-dxvk-xclipse-") as directory:
            root = Path(directory)
            (root / "dxvk_bc_formats.h").write_text(added_file(
                PATCH, "src/dxvk/dxvk_bc_formats.h"))
            program = root / "bc-formats-test"
            built = subprocess.run([
                compiler, "-std=c++17", "-Wall", "-Wextra", "-Werror",
                "-I", str(root), "-I", str(include), str(FIXTURE), "-o", str(program),
            ], capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stdout + built.stderr)
            ran = subprocess.run([str(program)], capture_output=True, text=True, timeout=30)
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
            self.assertIn("BC format checks ok", ran.stdout)

    def test_d3d9_and_d3d11_preserve_non_bc_feature_requirements(self):
        d3d9 = file_diff(PATCH, "src/d3d9/d3d9_device.cpp")
        d3d11 = file_diff(PATCH, "src/d3d11/d3d11_device.cpp")
        adapter = file_diff(PATCH, "src/dxvk/dxvk_adapter.cpp")
        for diff in (d3d9, d3d11):
            changed_features = [line for line in diff.splitlines()
                                if line[:1] in "+-" and "enabled.core.features." in line]
            self.assertTrue(changed_features)
            self.assertTrue(all("textureCompressionBC" in line for line in changed_features))
            self.assertIn("supported.core.features.textureCompressionBC", diff)
        self.assertNotIn("-        && CHECK_FEATURE_NEED(", adapter)
        self.assertIn("checkD3dBcFormatSupport", file_diff(
            PATCH, "src/d3d9/d3d9_interface.cpp"))
        self.assertIn("checkD3dBcFormatSupport", d3d11)

    def test_pinned_legacy_source_has_modern_toolchain_includes_and_official_tag(self):
        config = file_diff(PATCH, "src/util/config/config.cpp")
        self.assertIn("+#include <algorithm>", config)
        pipeline = file_diff(PATCH, "src/dxvk/dxvk_pipemanager.cpp")
        self.assertIn("-      std::tuple(),", pipeline)
        self.assertIn("+      std::tuple(key),", pipeline)
        self.assertEqual(PATCH.read_text().count("-      std::tuple(),"), 1)
        build = (ROOT / "tools/dxvk/build_xclipse.sh").read_text()
        self.assertIn("refs/tags/v$DXVK_XCLIPSE_VERSION:refs/tags/v$DXVK_XCLIPSE_VERSION", build)
        self.assertIn("DXVK_XCLIPSE_TAG_OBJECT", build)
        self.assertNotIn('git -C "$source" tag ', build)

    def test_legacy_source_patterns_compile_with_the_host_standard_library(self):
        compiler = shutil.which(os.environ.get("CXX", "c++"))
        self.assertIsNotNone(compiler, "a C++ compiler is required")
        with tempfile.TemporaryDirectory(prefix="droiddeck-dxvk-toolchain-") as directory:
            program = Path(directory) / "toolchain-test"
            built = subprocess.run([
                compiler, "-std=c++17", "-Wall", "-Wextra", "-Werror",
                str(TOOLCHAIN_FIXTURE), "-o", str(program),
            ], capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stdout + built.stderr)
            ran = subprocess.run([str(program)], capture_output=True, text=True, timeout=30)
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)


if __name__ == "__main__":
    unittest.main()
