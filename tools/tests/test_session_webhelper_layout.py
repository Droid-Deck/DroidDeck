import os
import re
import subprocess
import unittest
from pathlib import Path

SESSION = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-session"
KEYS = ("BL_VKBRIDGE_WEBHELPER_ARGS", "BL_VKBRIDGE_CEF_ANGLE", "BL_VKBRIDGE_CEF_VULKAN")


def layout_function():
    match = re.search(r"^bl_webhelper_layout\(\) \{\n.*?^\}\n", SESSION.read_text(), re.M | re.S)
    return match.group(0) if match else None


class WebhelperLayoutTest(unittest.TestCase):
    def layout(self, **settings):
        function = layout_function()
        self.assertIsNotNone(function, "droiddeck-session lacks bl_webhelper_layout()")
        env = {k: v for k, v in os.environ.items() if k not in KEYS}
        env.update(settings)
        return subprocess.run(["bash", "-c", "set -u\n" + function + "bl_webhelper_layout\n"], env=env,
                              capture_output=True, text=True, check=True).stdout.strip()

    def test_unset_keeps_steams_own_layout(self):
        self.assertEqual(self.layout(), "stock")

    def test_angle_on_vulkan_brings_chromiums_vulkan_compositing(self):
        self.assertEqual(self.layout(BL_VKBRIDGE_CEF_ANGLE="vulkan"),
                         "--use-gl=angle --use-angle=vulkan +Vulkan +VulkanFromANGLE +DefaultANGLEVulkan")

    def test_vulkan_compositing_can_be_left_off(self):
        self.assertEqual(self.layout(BL_VKBRIDGE_CEF_ANGLE="vulkan", BL_VKBRIDGE_CEF_VULKAN="0"),
                         "--use-gl=angle --use-angle=vulkan")

    def test_other_angle_backends_get_only_the_angle_switches(self):
        self.assertEqual(self.layout(BL_VKBRIDGE_CEF_ANGLE="gl"), "--use-gl=angle --use-angle=gl")

    def test_explicit_layout_wins(self):
        self.assertEqual(self.layout(BL_VKBRIDGE_CEF_ANGLE="vulkan", BL_VKBRIDGE_WEBHELPER_ARGS="--x +Y"),
                         "--x +Y")

    def test_the_steam_block_uses_it(self):
        self.assertIn("BL_WEBHELPER_ARGS=$(bl_webhelper_layout)", SESSION.read_text())


if __name__ == "__main__":
    unittest.main()
