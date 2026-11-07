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
    def test_calibrated_device_command_stays_out_of_physical_proc_addr(self):
        entry = file_diff(PATCH, "hybris/vulkan/layer/entry.c")
        instance = entry[entry.index("static PFN_vkVoidFunction VKAPI_CALL get_instance_proc"):
                         entry.index("static PFN_vkVoidFunction VKAPI_CALL get_physical_proc")]
        physical = entry[entry.index("static PFN_vkVoidFunction VKAPI_CALL get_physical_proc"):]
        self.assertIn("hybris_vendor_instance_alias_proc", instance)
        reject = physical.index('if (!strcmp(name, "vkGetCalibratedTimestampsKHR")) return NULL;')
        resolve = physical.index("state.physical_resolver")
        self.assertLess(reject, resolve)
        source_value = os.environ.get("HYBRIS_VENDOR_SOURCE")
        if source_value:
            with tempfile.TemporaryDirectory(prefix="droiddeck-entry-routing-") as directory:
                checkout = Path(directory) / "libhybris"
                subprocess.run(["git", "clone", "-q", "--no-hardlinks", source_value, str(checkout)], check=True)
                for patch in [ROOT / "tools/hybris/powervr-hal-version.patch",
                              *sorted((ROOT / "tools/hybris/patches").glob("*.patch"))]:
                    subprocess.run(["git", "-C", str(checkout), "apply", "--check", str(patch)], check=True)
                    subprocess.run(["git", "-C", str(checkout), "apply", str(patch)], check=True)
                final = (checkout / "hybris/vulkan/layer/entry.c").read_text()
                instance = final[final.index("static PFN_vkVoidFunction VKAPI_CALL get_instance_proc"):
                                 final.index("static PFN_vkVoidFunction VKAPI_CALL get_physical_proc")]
                physical = final[final.index("static PFN_vkVoidFunction VKAPI_CALL get_physical_proc"):]
                self.assertIn("hybris_vendor_instance_alias_proc", instance)
                self.assertNotIn("hybris_vendor_physical_alias_proc", instance)
                reject = physical.index('if (!strcmp(name, "vkGetCalibratedTimestampsKHR")) return NULL;')
                resolve = physical.index("state.physical_resolver")
                self.assertLess(reject, resolve)

    def test_calibrated_lookup_routes_before_downstream_proc_queries(self):
        source_value = os.environ.get("HYBRIS_VENDOR_SOURCE")
        if not source_value:
            self.skipTest("set HYBRIS_VENDOR_SOURCE to the clean pinned libhybris checkout")
        compiler = shutil.which(os.environ.get("CC", "cc"))
        self.assertIsNotNone(compiler)
        with tempfile.TemporaryDirectory(prefix="droiddeck-calibrated-routing-") as directory:
            checkout = Path(directory) / "libhybris"
            subprocess.run(["git", "clone", "-q", "--no-hardlinks", source_value, str(checkout)],
                           check=True)
            for patch in [ROOT / "tools/hybris/powervr-hal-version.patch",
                          *sorted((ROOT / "tools/hybris/patches").glob("*.patch"))]:
                subprocess.run(["git", "-C", str(checkout), "apply", "--check", str(patch)], check=True)
                subprocess.run(["git", "-C", str(checkout), "apply", str(patch)], check=True)
            entry = (checkout / "hybris/vulkan/layer/entry.c").read_text()
            instance_start = entry.index("static PFN_vkVoidFunction VKAPI_CALL get_instance_proc")
            physical_start = entry.index("static PFN_vkVoidFunction VKAPI_CALL get_physical_proc")
            negotiate = entry.index("__attribute__((visibility", physical_start)
            instance_proc = entry[instance_start:physical_start]
            physical_proc = entry[physical_start:negotiate]
            device_source = (checkout / "hybris/vulkan/layer/device.c").read_text()
            device_start = device_source.index(
                "PFN_vkVoidFunction VKAPI_CALL hybris_layer_get_device_proc")
            device_proc = device_source[device_start:]
            program_source = Path(directory) / "routing.c"
            program_source.write_text(r'''
#define VK_NO_PROTOTYPES
#include <assert.h>
#include <stdint.h>
#include <string.h>
#include <vulkan/vulkan.h>
struct instance_state { VkInstance handle; PFN_vkGetInstanceProcAddr resolver;
    PFN_vkGetInstanceProcAddr physical_resolver; int zink_profile; };
struct command_dispatch { PFN_vkCmdBindVertexBuffers CmdBindVertexBuffers; };
struct hybris_layer_device { VkDevice handle; PFN_vkGetDeviceProcAddr resolver;
    int track_commands; struct command_dispatch *recording; int maintenance5; };
static VkInstance test_instance = (VkInstance)(uintptr_t)1;
static VkDevice test_device = (VkDevice)(uintptr_t)2;
static int instance_queries, physical_queries, device_queries, device_allowed;
static void address(void) {}
static PFN_vkVoidFunction function(void) { return (PFN_vkVoidFunction)address; }
static PFN_vkVoidFunction VKAPI_CALL downstream_instance(VkInstance instance, const char *name)
{ (void)instance; (void)name; ++instance_queries; return function(); }
static PFN_vkVoidFunction VKAPI_CALL downstream_physical(VkInstance instance, const char *name)
{ (void)instance; (void)name; ++physical_queries; return function(); }
static PFN_vkVoidFunction VKAPI_CALL downstream_device(VkDevice device, const char *name)
{ (void)device; (void)name; ++device_queries; return function(); }
static int instance_snapshot(VkInstance instance, struct instance_state *out) {
    if (instance != test_instance) return 0;
    *out = (struct instance_state){test_instance, downstream_instance, downstream_physical, 0};
    return 1;
}
static int hybris_layer_lookup_device(VkDevice device, struct hybris_layer_device *out) {
    static struct command_dispatch recording;
    if (device != test_device) return 0;
    *out = (struct hybris_layer_device){test_device, downstream_device, 1, &recording, 0};
    return 1;
}
static PFN_vkVoidFunction hybris_vendor_instance_alias_proc(
    VkInstance instance, PFN_vkGetInstanceProcAddr resolver, const char *name) {
    (void)instance; (void)resolver;
    return !strcmp(name, "vkGetPhysicalDeviceCalibrateableTimeDomainsKHR") ||
        !strcmp(name, "vkGetCalibratedTimestampsKHR") ? function() : NULL;
}
static PFN_vkVoidFunction hybris_vendor_physical_alias_proc(
    VkInstance instance, PFN_vkGetInstanceProcAddr resolver, const char *name) {
    (void)instance; (void)resolver;
    return !strcmp(name, "vkGetPhysicalDeviceCalibrateableTimeDomainsKHR") ? function() : NULL;
}
static PFN_vkVoidFunction hybris_vendor_device_alias_proc(VkDevice device, const char *name) {
    (void)device;
    return device_allowed && !strcmp(name, "vkGetCalibratedTimestampsKHR") ? function() : NULL;
}
static int hybris_shader_device_proc_allowed(VkDevice device, const char *name)
{ (void)device; (void)name; return 1; }
static int hybris_layer_vertex_buffers2_proc(const char *name) { (void)name; return 0; }
static int hybris_dynamic_proc(const struct hybris_layer_device *device, const char *name)
{ (void)device; (void)name; return 0; }
static int hybris_maintenance5_added_proc(const char *name) { (void)name; return 0; }
static PFN_vkVoidFunction hybris_layer_physical_proc(const char *name) { (void)name; return NULL; }
static PFN_vkVoidFunction hybris_layer_device_dispatch(const char *name, int tracked)
{ (void)name; (void)tracked; return NULL; }
static PFN_vkVoidFunction zink_wrap(VkInstance instance, const char *name, PFN_vkVoidFunction fn)
{ (void)instance; (void)name; return fn; }
static void create_instance(void) {}
static void hybris_layer_create_device(void) {}
static void destroy_instance(void) {}
static void hybris_layer_destroy_device(void) {}
PFN_vkVoidFunction VKAPI_CALL hybris_layer_get_device_proc(VkDevice, const char *);
''' + instance_proc + physical_proc + device_proc + r'''
int main(void) {
    PFN_vkVoidFunction alias = function();
    assert(get_instance_proc(test_instance,
        "vkGetPhysicalDeviceCalibrateableTimeDomainsKHR") == alias);
    assert(get_instance_proc(test_instance, "vkGetCalibratedTimestampsKHR") == alias);
    assert(instance_queries == 0);
    assert(get_physical_proc(test_instance,
        "vkGetPhysicalDeviceCalibrateableTimeDomainsKHR") == alias);
    assert(!get_physical_proc(test_instance, "vkGetCalibratedTimestampsKHR"));
    assert(physical_queries == 0);
    assert(!hybris_layer_get_device_proc(test_device, "vkGetCalibratedTimestampsKHR"));
    assert(device_queries == 0);
    device_allowed = 1;
    assert(hybris_layer_get_device_proc(test_device, "vkGetCalibratedTimestampsKHR") == alias);
    assert(device_queries == 0);
    assert(hybris_layer_get_device_proc(test_device, "vkOther") == alias);
    assert(device_queries == 1);
    return 0;
}
''')
            program = Path(directory) / "routing"
            built = subprocess.run([
                compiler, "-std=gnu11", "-Wall", "-Wextra", "-Werror",
                "-Wno-unused-function", "-I", str(vulkan_include()), str(program_source),
                "-o", str(program)], capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stdout + built.stderr)
            ran = subprocess.run([str(program)], capture_output=True, text=True, timeout=30)
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)

    def test_command_tracking_uses_effective_device_policy(self):
        source_value = os.environ.get("HYBRIS_VENDOR_SOURCE")
        if not source_value:
            self.skipTest("set HYBRIS_VENDOR_SOURCE to the clean pinned libhybris checkout")
        compiler = shutil.which(os.environ.get("CC", "cc"))
        self.assertIsNotNone(compiler)
        with tempfile.TemporaryDirectory(prefix="droiddeck-tracking-policy-") as directory:
            checkout = Path(directory) / "libhybris"
            subprocess.run(["git", "clone", "-q", "--no-hardlinks", source_value, str(checkout)], check=True)
            for patch in [ROOT / "tools/hybris/powervr-hal-version.patch",
                          *sorted((ROOT / "tools/hybris/patches").glob("*.patch"))]:
                subprocess.run(["git", "-C", str(checkout), "apply", "--check", str(patch)], check=True)
                subprocess.run(["git", "-C", str(checkout), "apply", str(patch)], check=True)
            device = (checkout / "hybris/vulkan/layer/device.c").read_text()
            if "static int polygon_tracking(" not in device:
                self.assertIn(".track_commands = 1,", device)
                start = device.index("\nPFN_vkVoidFunction hybris_layer_device_dispatch(") + 1
                end = device.index("\nPFN_vkVoidFunction VKAPI_CALL hybris_layer_get_device_proc", start)
                dispatch = device[start:end]
                program_source = Path(directory) / "tracking.c"
                program_source.write_text(r'''
#define VK_NO_PROTOTYPES
#include <assert.h>
#include <stdint.h>
#include <string.h>
#include <vulkan/vulkan.h>
static PFN_vkVoidFunction result(uintptr_t value) { return (PFN_vkVoidFunction)value; }
static PFN_vkVoidFunction hybris_layer_commands_proc(const char *n) { return !strcmp(n, "record") ? result(1) : 0; }
static PFN_vkVoidFunction hybris_memory_visibility_proc(const char *n) { (void)n; return 0; }
static PFN_vkVoidFunction hybris_vendor_device_proc(const char *n) { (void)n; return 0; }
static PFN_vkVoidFunction hybris_maintenance5_dispatch(const char *n) { return !strcmp(n, "maintenance5") ? result(2) : 0; }
static PFN_vkVoidFunction hybris_dynamic_dispatch(const char *n) { return !strcmp(n, "dynamic") ? result(3) : 0; }
static PFN_vkVoidFunction hybris_null_dispatch(const char *n) { (void)n; return 0; }
static PFN_vkVoidFunction hybris_bc_proc(const char *n) { (void)n; return 0; }
static PFN_vkVoidFunction hybris_shader_cleanup_proc(const char *n) { (void)n; return 0; }
static PFN_vkVoidFunction hybris_shader_proc(const char *n) { (void)n; return 0; }
static PFN_vkVoidFunction hybris_vertex_stores_proc(const char *n) { (void)n; return 0; }
''' + dispatch + r'''
int main(void) {
    assert(!hybris_layer_device_dispatch("record", 0));
    assert(hybris_layer_device_dispatch("record", 1) == result(1));
    assert(hybris_layer_device_dispatch("maintenance5", 0) == result(2));
    assert(hybris_layer_device_dispatch("dynamic", 0) == result(3));
    return 0;
}
''')
                program = Path(directory) / "tracking-test"
                built = subprocess.run([compiler, "-std=gnu11", "-Wall", "-Wextra", "-Werror",
                                        "-I", str(vulkan_include()), str(program_source), "-o", str(program)],
                                       capture_output=True, text=True)
                self.assertEqual(built.returncode, 0, built.stdout + built.stderr)
                ran = subprocess.run([str(program)], capture_output=True, text=True, timeout=30)
                self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
                return
            polygon = device[device.index("static int polygon_tracking("):device.index("\nstruct device_state {")]
            start = device.index("    if (!state) return VK_ERROR_OUT_OF_HOST_MEMORY;")
            start = device.index("\n", start) + 1
            end = device.index("    if (allocator) state->allocator = *allocator;", start)
            initializer = device[start:end]
            program_source = Path(directory) / "tracking.c"
            program_source.write_text(r'''
#define VK_NO_PROTOTYPES
#include <assert.h>
#include <stdint.h>
#include "application_policy.h"
static int polygon_emulated;
int hybris_shader_polygon_emulated(VkPhysicalDevice physical)
{ (void)physical; return polygon_emulated; }
''' + polygon + r'''
struct instance { unsigned application_policy; uint64_t generation; };
struct tracked_device { void *resolver; VkPhysicalDevice physical; unsigned application_policy;
    int track_commands; uint64_t instance_generation; };
struct device_state { struct tracked_device device; int custom_allocator; };
static struct device_state make_state(unsigned policy, const VkDeviceCreateInfo *info)
{
    struct instance instance = {.application_policy = policy};
    struct device_state storage, *state = &storage;
    void *resolver = 0, *allocator = 0;
    VkPhysicalDevice physical = VK_NULL_HANDLE;
''' + initializer + r'''
    return *state;
}
int main(void)
{
    const unsigned policy = HYBRIS_APP_HOST_UPLOAD_FLUSH | HYBRIS_APP_RENDERING_SEGMENTS |
        HYBRIS_APP_HOST_READBACK_INVALIDATE;
    const char *unsupported[] = {"VK_FAKE_unsupported"};
    VkDeviceCreateInfo info = {.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
        .enabledExtensionCount = 1, .ppEnabledExtensionNames = unsupported};
    struct device_state state = make_state(policy, &info);
    assert(!state.device.application_policy && !state.device.track_commands);
    info.enabledExtensionCount = 0;
    state = make_state(policy, &info);
    assert(state.device.application_policy == policy && state.device.track_commands);
    VkDeviceGroupDeviceCreateInfo group = {.sType = VK_STRUCTURE_TYPE_DEVICE_GROUP_DEVICE_CREATE_INFO,
        .physicalDeviceCount = 2};
    info.pNext = &group;
    state = make_state(HYBRIS_APP_RENDERING_SEGMENTS, &info);
    assert(!state.device.application_policy && !state.device.track_commands);
    VkPhysicalDeviceFeatures features = {.fillModeNonSolid = VK_TRUE};
    info.pEnabledFeatures = &features; polygon_emulated = 1;
    state = make_state(policy, &info);
    assert(state.device.track_commands);
    info.enabledExtensionCount = 1;
    state = make_state(policy, &info);
    assert(!state.device.application_policy && state.device.track_commands);
    VkPhysicalDeviceFeatures2 features2 = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2,
        .features.fillModeNonSolid = VK_TRUE};
    info.pEnabledFeatures = 0; info.pNext = &features2;
    state = make_state(policy, &info);
    assert(!state.device.application_policy && state.device.track_commands);
    polygon_emulated = 0;
    state = make_state(policy, &info);
    assert(!state.device.track_commands);
    return 0;
}
''')
            compat = checkout / "hybris/vulkan/compat"
            program = Path(directory) / "tracking-test"
            built = subprocess.run([compiler, "-std=gnu11", "-Wall", "-Wextra", "-Werror",
                                    "-I", str(vulkan_include()), "-I", str(compat), str(program_source),
                                    str(compat / "application_policy.c"), "-o", str(program)],
                                   capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stdout + built.stderr)
            ran = subprocess.run([str(program)], capture_output=True, text=True, timeout=30)
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)

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
        source_value = os.environ.get("HYBRIS_VENDOR_SOURCE")
        if not source_value:
            self.skipTest("set HYBRIS_VENDOR_SOURCE to the clean pinned libhybris checkout")
        compiler = shutil.which(os.environ.get("CC", "cc"))
        self.assertIsNotNone(compiler, "a C compiler is required")
        include = vulkan_include()
        self.assertTrue((include / "vulkan/vulkan.h").is_file(),
                        "Vulkan headers are required")
        with tempfile.TemporaryDirectory(prefix="droiddeck-hybris-features-") as directory:
            checkout = Path(directory) / "libhybris"
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
