import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


def vulkan_include():
    override = os.environ.get("VULKAN_INCLUDE")
    if override:
        return Path(override)
    # Distribution headers do not move with a virtualenv/toolcache interpreter.
    # Termux's native headers live under the base interpreter's prefix instead.
    for include in (Path("/usr/include"), Path(sys.base_prefix) / "include"):
        if (include / "vulkan/vulkan.h").is_file():
            return include
    return Path("/usr/include")


ROOT = Path(__file__).resolve().parents[2]
PATCH = ROOT / "tools/hybris/patches/0002-vendor-compatibility.patch"
HELPER = Path(__file__).with_name("hybris_vendor_compat_test.c")
FEATURE_HELPER = Path(__file__).with_name("hybris_vendor_device_features_test.c")
FEATURE_BASE = Path(__file__).with_name("hybris_vendor_device_features_base.c")

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


class HybrisVendorCompatibilityTest(unittest.TestCase):
    def test_explicit_hal_environment_is_prepared_before_dlopen(self):
        icd_patch = file_diff(PATCH, "hybris/vulkan/icd/icd.c")
        explicit = icd_patch.index("if (path && path[0])")
        prepare = icd_patch.index("hybris_vendor_compat_prepare_vulkan_hal(path)")
        namespace_setup = icd_patch.index("With Android's generated namespace config")
        self.assertLess(explicit, prepare)
        self.assertLess(prepare, namespace_setup)

    def test_native_profile_and_workarounds(self):
        compiler = shutil.which(os.environ.get("CC", "cc"))
        self.assertIsNotNone(compiler, "a C compiler is required")
        include = vulkan_include()
        self.assertTrue((include / "vulkan/vulkan.h").is_file(),
                        "Vulkan headers are required")
        with tempfile.TemporaryDirectory(prefix="droiddeck-hybris-vendor-") as directory:
            root = Path(directory)
            compat = root / "compat"
            layer = root / "layer"
            common = root / "common"
            compat.mkdir()
            layer.mkdir()
            common.mkdir()
            (compat / "vendor_compat.c").write_text(added_file(
                PATCH, "hybris/vulkan/compat/vendor_compat.c"))
            (compat / "vendor_compat.h").write_text(added_file(
                PATCH, "hybris/vulkan/compat/vendor_compat.h"))
            (common / "vendor_compat.c").write_text(added_file(
                PATCH, "hybris/common/vendor_compat.c"))
            (common / "vendor_compat.h").write_text(added_file(
                PATCH, "hybris/common/vendor_compat.h"))
            dlfcn = root / "hybris/common"
            dlfcn.mkdir(parents=True)
            (dlfcn / "dlfcn.h").write_text(
                "#pragma once\nvoid *hybris_dlopen(const char *, int);\n"
                "void *hybris_dlsym(void *, const char *);\n")
            (compat / "bc_policy.h").write_text(
                "#pragma once\n#include <vulkan/vulkan.h>\n"
                "unsigned hybris_bc_physical_mask(VkPhysicalDevice);\n")
            (compat / "scaled_vertex.h").write_text(
                "#pragma once\n#include <vulkan/vulkan.h>\n#include <stddef.h>\n"
                "void *hybris_scaled_alloc(const VkAllocationCallbacks *, size_t, VkSystemAllocationScope);\n"
                "void hybris_scaled_free(const VkAllocationCallbacks *, void *);\n")
            (layer / "layer.h").write_text(
                "#pragma once\n#include <vulkan/vulkan.h>\n"
                "struct hybris_layer_physical { VkInstance instance; uint64_t generation; "
                "uint32_t api_version; PFN_vkGetInstanceProcAddr resolver; unsigned application_policy; };\n"
                "struct hybris_layer_device { VkDevice handle; PFN_vkGetDeviceProcAddr resolver; "
                "VkPhysicalDevice physical; uint64_t generation; unsigned application_policy; "
                "int track_commands; uint64_t instance_generation; };\n"
                "int hybris_layer_lookup_physical(VkPhysicalDevice, struct hybris_layer_physical *);\n"
                "int hybris_layer_lookup_device(VkDevice, struct hybris_layer_device *);\n")
            program = root / "hybris-vendor-test"
            built = subprocess.run([
                compiler, "-std=gnu11", "-Wall", "-Wextra", "-Werror",
                "-Wno-unused-parameter", "-pthread", "-I", str(compat), "-I", str(root),
                "-I", str(include), str(HELPER), str(compat / "vendor_compat.c"),
                str(common / "vendor_compat.c"),
                "-o", str(program)], capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stdout + built.stderr)
            ran = subprocess.run([str(program)], capture_output=True, text=True, timeout=30)
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
            self.assertIn("hybris vendor compatibility ok", ran.stdout)
            preserved = subprocess.run([str(program), "preinitialized"],
                                       capture_output=True, text=True, timeout=30)
            self.assertEqual(preserved.returncode, 0,
                             preserved.stdout + preserved.stderr)
            self.assertIn("hybris native environment preserved", preserved.stdout)
            retried = subprocess.run([str(program), "retry"],
                                     capture_output=True, text=True, timeout=30)
            self.assertEqual(retried.returncode, 0, retried.stdout + retried.stderr)
            self.assertIn("hybris environment preparation retried", retried.stdout)

    def test_device_feature_copy_preserves_input_and_fails_closed(self):
        compiler = shutil.which(os.environ.get("CC", "cc"))
        self.assertIsNotNone(compiler, "a C compiler is required")
        include = vulkan_include()
        self.assertTrue((include / "vulkan/vulkan.h").is_file(),
                        "Vulkan headers are required")
        with tempfile.TemporaryDirectory(prefix="droiddeck-hybris-features-") as directory:
            checkout = Path(directory) / "libhybris"
            source_value = os.environ.get("HYBRIS_VENDOR_SOURCE")
            if source_value:
                source = Path(source_value)
                self.assertTrue((source / "hybris/vulkan/layer/device_features.c").is_file(),
                                "HYBRIS_VENDOR_SOURCE must be a pristine pinned libhybris checkout")
                subprocess.run(["git", "clone", "-q", "--no-hardlinks", str(source), str(checkout)],
                               check=True)
                for patch in [ROOT / "tools/hybris/powervr-hal-version.patch",
                              ROOT / "tools/hybris/patches/0001-dxvk-bc-profile.patch", PATCH]:
                    applied = subprocess.run(["git", "-C", str(checkout), "apply", "--check", str(patch)],
                                             capture_output=True, text=True)
                    self.assertEqual(applied.returncode, 0, applied.stdout + applied.stderr)
                    subprocess.run(["git", "-C", str(checkout), "apply", str(patch)], check=True)
            else:
                layer = checkout / "hybris/vulkan/layer"
                compat = checkout / "hybris/vulkan/compat"
                layer.mkdir(parents=True)
                compat.mkdir()
                shutil.copyfile(FEATURE_BASE, layer / "device_features.c")
                (layer / "device_features.h").write_text(
                    "#pragma once\n#include <vulkan/vulkan.h>\n"
                    "struct hybris_device_features { VkDeviceCreateInfo info; VkPhysicalDeviceFeatures features; "
                    "VkBaseOutStructure *copies; const void *suffix; };\n"
                    "VkResult hybris_device_features_prepare(VkPhysicalDevice, const VkDeviceCreateInfo *, "
                    "const VkAllocationCallbacks *, struct hybris_device_features *);\n"
                    "void hybris_device_features_release(struct hybris_device_features *, "
                    "const VkAllocationCallbacks *);\n")
                (layer / "device_create_sizes.inc").write_text(
                    "case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES: "
                    "return sizeof(VkPhysicalDeviceTimelineSemaphoreFeatures);\n"
                    "case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2: "
                    "return sizeof(VkPhysicalDeviceFeatures2);\n")
                for name, declaration in {
                    "vertex_stores.h": "int hybris_vertex_stores_active(VkPhysicalDevice);",
                    "clip_distance.h": "int hybris_clip_active(VkPhysicalDevice);",
                    "vendor_compat.h": "int hybris_vendor_full_bc_feature(VkPhysicalDevice);",
                    "scaled_vertex.h": ("void *hybris_scaled_alloc(const VkAllocationCallbacks *, size_t, "
                        "VkSystemAllocationScope); void hybris_scaled_free(const VkAllocationCallbacks *, void *);"),
                }.items():
                    (compat / name).write_text("#pragma once\n#include <vulkan/vulkan.h>\n#include <stddef.h>\n" +
                                               declaration + "\n")
                patch_file = Path(directory) / "device-features.patch"
                patch_file.write_text(file_diff(PATCH, "hybris/vulkan/layer/device_features.c"))
                applied = subprocess.run(["git", "-C", str(checkout), "apply", "--check", str(patch_file)],
                                         capture_output=True, text=True)
                self.assertEqual(applied.returncode, 0, applied.stdout + applied.stderr)
                subprocess.run(["git", "-C", str(checkout), "apply", str(patch_file)], check=True)
            program = Path(directory) / "device-features-test"
            built = subprocess.run([
                compiler, "-std=gnu11", "-Wall", "-Wextra", "-Werror",
                "-Wno-unused-parameter", "-I", str(include),
                "-I", str(checkout / "hybris/vulkan/layer"),
                "-I", str(checkout / "hybris/vulkan/compat"),
                str(FEATURE_HELPER),
                str(checkout / "hybris/vulkan/layer/device_features.c"),
                "-o", str(program)], capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stdout + built.stderr)
            ran = subprocess.run([str(program)], capture_output=True, text=True, timeout=30)
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)


if __name__ == "__main__":
    unittest.main()
