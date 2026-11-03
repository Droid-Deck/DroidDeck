"""The compat layer must size every VkDeviceCreateInfo input-chain node the pinned headers declare."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

if __package__:
    from .test_hybris_vendor_compat import vulkan_include
else:
    from test_hybris_vendor_compat import vulkan_include


ROOT = Path(__file__).resolve().parents[2]
PATCH = ROOT / "tools/hybris/patches/0017-device-create-sizes.patch"


def device_create_nodes(registry, header):
    """sType/struct pairs that extend VkDeviceCreateInfo and that vulkan_core.h declares."""
    text = header.read_text()
    nodes = []
    for node in ET.parse(registry).getroot().iter("type"):
        if node.get("alias") or "VkDeviceCreateInfo" not in (node.get("structextends") or "").split(","):
            continue
        member = node.find("member[name='sType']")
        stype, name = (member.get("values") if member is not None else None), node.get("name")
        if stype and re.search(r"\}\s*%s;" % name, text) and re.search(r"\b%s\s*=" % stype, text):
            nodes.append((stype, name))
    return nodes


class HybrisDeviceCreateSizesTest(unittest.TestCase):
    def test_every_declared_device_create_node_has_a_size(self):
        source_value = os.environ.get("HYBRIS_SIZES_SOURCE")
        if not source_value:
            self.skipTest("set HYBRIS_SIZES_SOURCE to the clean pinned libhybris checkout")
        include = vulkan_include()
        registry = include.parent / "registry/vk.xml"
        self.assertTrue(registry.is_file(), "the pinned Vulkan-Headers checkout (with registry/) is required")
        compiler = shutil.which(os.environ.get("CC", "cc"))
        self.assertIsNotNone(compiler, "a host C compiler is required")
        nodes = device_create_nodes(registry, include / "vulkan/vulkan_core.h")
        # DXVK and Zink chain this one; the table predating it failed their vkCreateDevice.
        self.assertIn("VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAINTENANCE_9_FEATURES_KHR", dict(nodes))
        with tempfile.TemporaryDirectory(prefix="droiddeck-hybris-sizes-") as directory:
            work = Path(directory)
            checkout = work / "libhybris"
            subprocess.run(["git", "clone", "-q", "--no-hardlinks", source_value, str(checkout)], check=True)
            subprocess.run(["git", "-C", str(checkout), "apply", str(PATCH)], check=True)
            checks = "".join(
                '    if (!node_size(%s)) { printf("missing %s\\n"); ++missing; }\n' % (stype, stype)
                for stype, _ in nodes)
            (work / "sizes.c").write_text(
                "#include <stdio.h>\n#include <vulkan/vulkan.h>\n#include <vulkan/vk_layer.h>\n"
                "static size_t node_size(VkStructureType type)\n{\n    switch (type) {\n"
                "    case VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO: return sizeof(VkLayerDeviceCreateInfo);\n"
                '#include "device_create_sizes.inc"\n    default: return 0;\n    }\n}\n'
                "int main(void)\n{\n    int missing = 0;\n" + checks +
                '    printf("nodes=%d missing=%d\\n", ' + str(len(nodes)) + ", missing);\n"
                "    return missing != 0;\n}\n")
            program = work / "sizes"
            built = subprocess.run([
                compiler, "-std=gnu11", "-Wall", "-Wextra", "-Werror", "-I", str(include),
                "-I", str(checkout / "hybris/vulkan/layer"), str(work / "sizes.c"), "-o", str(program)],
                capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stdout + built.stderr)
            ran = subprocess.run([str(program)], capture_output=True, text=True, timeout=30)
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
            self.assertIn(f"nodes={len(nodes)} missing=0", ran.stdout)


if __name__ == "__main__":
    unittest.main()
