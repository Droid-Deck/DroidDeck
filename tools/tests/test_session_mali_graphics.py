import os
import socket
import subprocess
import tempfile
import unittest
from pathlib import Path

SESSION = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-session"


class MaliGraphicsSessionTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.icd = self.root / "bridge.json"
        self.lib = self.root / "bridge.so"
        self.fallback = self.root / "panvk.json"
        for file in (self.icd, self.lib, self.fallback):
            file.touch()
        self.sock = socket.socket(socket.AF_UNIX)
        self.sock.bind(str(self.root / "server.sock"))
        self.sock.listen(1)
        self.addCleanup(self.sock.close)
        text = SESSION.read_text()
        self.graphics = text[text.index("mali_graphics_failed() {"):text.index('if [ "${BL_GAME_LOGS:-0}" = 1 ]')]
        self.graphics = self.graphics.replace("/usr/local/share/vkbridge/droidbridge_icd.json", str(self.icd))
        self.graphics = self.graphics.replace("/usr/local/lib/vkbridge/libvulkan_droidbridge.so", str(self.lib))

    def run_graphics(self, mode="bridge", smoke="FAIL memory sharing", status=1, path="bridge", vulkan_status=0, driver="",
                     vulkan_info="nullDescriptor = true"):
        env = dict(os.environ, BL_MALI_GRAPHICS_MODE=mode, BL_MALI_GRAPHICS_PATH=path,
                   BL_DEBUG_DIR=str(self.root), BL_VKBRIDGE="1" if path == "bridge" else "0",
                    BL_VENUS="1" if path == "venus" else "0", BL_INSIDE="",
                    BL_VK_DRIVER=driver,
                   VKBRIDGE_SOCKET=str(self.root / "server.sock"),
                   BL_VKBRIDGE_FALLBACK_ICD=str(self.fallback), SMOKE=smoke,
                    SMOKE_STATUS=str(status), VULKAN_STATUS=str(vulkan_status), VULKAN_INFO=vulkan_info)
        stubs = '''
step() { :; }
vkbridge_wait_socket() { :; }
vulkaninfo() { printf '%s\n' "$VULKAN_INFO"; return "$VULKAN_STATUS"; }
timeout() {
  shift
  if [ "$1" = /usr/local/bin/droiddeck-vkbridge-smoke ]; then
    printf '%s\n' "$SMOKE"
    return "$SMOKE_STATUS"
  fi
  "$@"
}
'''
        return subprocess.run(["bash", "-uc", stubs + self.graphics +
                               '\necho "CONTINUED bridge=$BL_VKBRIDGE icd=${VK_DRIVER_FILES:-}"'],
                              env=env, capture_output=True, text=True, timeout=5)

    def test_fixed_bridge_failures_stop_even_with_a_fallback_installed(self):
        for smoke, status in (("FAIL memory sharing", 1), ("FAIL draw", 0), ("", 124), ("", 127)):
            with self.subTest(smoke=smoke, status=status):
                result = self.run_graphics(smoke=smoke, status=status)
                self.assertEqual(69, result.returncode, result.stderr + result.stdout)
                self.assertNotIn("CONTINUED", result.stdout)
                self.assertIn("fixed path will not fall back", (self.root / "mali-graphics-error").read_text())
                self.assertFalse((self.root / "vkbridge-fallback").exists())
                self.assertFalse((self.root / "mali-graphics-path").exists())

    def test_panvk_rejects_an_unreadable_import_instead_of_the_runtime_driver(self):
        for mode in ("auto", "panvk"):
            with self.subTest(mode=mode):
                result = self.run_graphics(mode=mode, path="panvk", driver=str(self.root / "missing.json"))
                self.assertEqual(69, result.returncode, result.stderr + result.stdout)
                self.assertIn("PanVK manifest or driver library is unreadable", result.stdout)
                self.assertNotIn("using the runtime's own", result.stdout)

    def test_auto_can_fall_back_and_records_the_actual_path(self):
        result = self.run_graphics(mode="auto")
        self.assertEqual(0, result.returncode, result.stderr + result.stdout)
        self.assertIn(f"CONTINUED bridge=0 icd={self.fallback}", result.stdout)
        self.assertEqual("panvk\n", (self.root / "mali-graphics-path").read_text())
        self.assertEqual([str(self.fallback), "memory"], (self.root / "vkbridge-fallback").read_text().splitlines())

    def test_auto_without_an_installed_fallback_reports_failure(self):
        self.fallback.unlink()
        result = self.run_graphics(mode="auto")
        self.assertEqual(69, result.returncode)
        self.assertIn("no compatible PanVK fallback", result.stdout)

    def test_a_failed_auto_fallback_is_not_recorded_as_the_actual_path(self):
        result = self.run_graphics(mode="auto", vulkan_status=1)
        self.assertEqual(69, result.returncode, result.stderr + result.stdout)
        self.assertIn("PanVK Vulkan self-test failed", (self.root / "mali-graphics-error").read_text())
        self.assertFalse((self.root / "mali-graphics-path").exists())

    def test_fixed_venus_cannot_silently_use_software_without_null_descriptor(self):
        result = self.run_graphics(mode="venus", path="venus", vulkan_info="nullDescriptor = false")
        self.assertEqual(69, result.returncode, result.stderr + result.stdout)
        self.assertIn("Venus lacks nullDescriptor", (self.root / "mali-graphics-error").read_text())
        self.assertNotIn("CONTINUED", result.stdout)
        self.assertFalse((self.root / "mali-graphics-path").exists())

    def test_a_passing_fixed_bridge_keeps_the_bridge(self):
        result = self.run_graphics(smoke="PASS draw", status=0)
        self.assertEqual(0, result.returncode, result.stderr + result.stdout)
        self.assertIn(f"CONTINUED bridge=1 icd={self.icd}", result.stdout)
        self.assertEqual("bridge\n", (self.root / "mali-graphics-path").read_text())

    def test_missing_bridge_library_cannot_silently_change_a_fixed_path(self):
        self.lib.unlink()
        result = self.run_graphics()
        self.assertEqual(69, result.returncode)
        self.assertIn("missing: library", result.stdout)

    def test_panvk_and_venus_vulkan_failures_stop(self):
        for path in ("panvk", "venus"):
            with self.subTest(path=path):
                result = self.run_graphics(mode=path, path=path, vulkan_status=1)
                self.assertEqual(69, result.returncode, result.stderr + result.stdout)
                self.assertNotIn("CONTINUED", result.stdout)
                self.assertFalse((self.root / "mali-graphics-path").exists())


if __name__ == "__main__":
    unittest.main()
