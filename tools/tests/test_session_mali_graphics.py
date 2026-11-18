import os
import shutil
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
        for file in (self.icd, self.lib):
            file.touch()
        self.bin = self.root / "bin"
        self.bin.mkdir()
        for tool in ("sed", "head", "grep", "rm", "dirname", "env", "timeout", "tail"):
            (self.bin / tool).symlink_to(shutil.which(tool))
        self.vulkaninfo = self.bin / "vulkaninfo"
        self.vulkaninfo.write_text('#!/bin/bash\nprintf "%s\\n" "$VULKAN_INFO"\nexit "$VULKAN_STATUS"\n')
        self.vulkaninfo.chmod(0o755)
        self.glprobe = self.bin / "glprobe"
        self.glprobe.write_text('''#!/bin/bash
if [ "$GL_VARIANT" = descriptors ] && [ "${ZINK_DESCRIPTORS:-}" = auto ]; then exit 0; fi
if [ "$GL_VARIANT" = no-compat ] && [ "${DROIDDECK_MALI_COMPAT:-}" = 0 ]; then exit 0; fi
exit "$GL_STATUS"
''')
        self.glprobe.chmod(0o755)
        self.sock = socket.socket(socket.AF_UNIX)
        self.sock.bind(str(self.root / "server.sock"))
        self.sock.listen(1)
        self.addCleanup(self.sock.close)
        text = SESSION.read_text()
        self.graphics = text[text.index("mali_graphics_failed() {"):text.index('if [ "${DD_GAME_LOGS:-0}" = 1 ]')]
        self.graphics = self.graphics.replace("/usr/local/share/vkbridge/droidbridge_icd.json", str(self.icd))
        self.graphics = self.graphics.replace("/usr/local/lib/vkbridge/libvulkan_droidbridge.so", str(self.lib))
        self.gl = text[text.index('    if [ "${DD_VENUS:-0}" = 1 ]'):text.index('    rm -f "$HOME/.dd-restart-game"')]
        self.gl = self.gl.replace("/usr/local/bin/droiddeck-glprobe", str(self.glprobe))

    def run_graphics(self, mode="bridge", smoke="FAIL memory sharing", status=1, path="bridge", vulkan_status=0, driver="",
                     vulkan_info="nullDescriptor = true", with_vulkaninfo=True, gl_status=None, gl_variant=""):
        if not with_vulkaninfo:
            self.vulkaninfo.unlink(missing_ok=True)
        env = dict(os.environ, DD_MALI_GRAPHICS_MODE=mode, DD_MALI_GRAPHICS_PATH=path,
                   DD_DEBUG_DIR=str(self.root), DD_VKBRIDGE="1" if path == "bridge" else "0",
                    DD_VENUS="1" if path == "venus" else "0", DD_INSIDE="",
                    DD_VK_DRIVER=driver,
                   VKBRIDGE_SOCKET=str(self.root / "server.sock"),
                   SMOKE=smoke, PATH=str(self.bin), GALLIUM_DRIVER="zink", LIBGL_ALWAYS_SOFTWARE="0",
                   MESA_LOADER_DRIVER_OVERRIDE="zink", LIBGL_KOPPER_DRI2="true",
                   ZINK_DEBUG="compact", ZINK_DESCRIPTORS="lazy", GL_STATUS=str(gl_status), GL_VARIANT=gl_variant,
                   SMOKE_STATUS=str(status), VULKAN_STATUS=str(vulkan_status), VULKAN_INFO=vulkan_info)
        stubs = '''
step() { :; }
vkbridge_wait_socket() { :; }
timeout() {
  shift
  if [ "$1" = /usr/local/bin/droiddeck-vkbridge-smoke ]; then
    printf '%s\n' "$SMOKE"
    return "$SMOKE_STATUS"
  fi
  "$@"
}
'''
        return subprocess.run(["/bin/bash", "-uc", stubs + self.graphics + (self.gl if gl_status is not None else "") +
                               '\necho "CONTINUED bridge=$DD_VKBRIDGE icd=${VK_DRIVER_FILES:-} path=$DD_MALI_GRAPHICS_PATH gl=$GALLIUM_DRIVER software=$LIBGL_ALWAYS_SOFTWARE"'],
                              env=env, capture_output=True, text=True, timeout=5)

    def test_fixed_bridge_failures_stop(self):
        for smoke, status in (("FAIL memory sharing", 1), ("FAIL draw", 0), ("", 124), ("", 127)):
            with self.subTest(smoke=smoke, status=status):
                result = self.run_graphics(smoke=smoke, status=status)
                self.assertEqual(69, result.returncode, result.stderr + result.stdout)
                self.assertNotIn("CONTINUED", result.stdout)
                self.assertIn("MaliBridge self-test failed", (self.root / "mali-graphics-error").read_text())
                self.assertFalse((self.root / "vkbridge-fallback").exists())
                self.assertFalse((self.root / "mali-graphics-path").exists())

    def test_panvk_rejects_an_unreadable_import_instead_of_the_runtime_driver(self):
        for mode in ("auto", "panvk"):
            with self.subTest(mode=mode):
                result = self.run_graphics(mode=mode, path="panvk", driver=str(self.root / "missing.json"))
                self.assertEqual(69, result.returncode, result.stderr + result.stdout)
                self.assertIn("PanVK manifest or driver library is unreadable", result.stdout)
                self.assertNotIn("using the runtime's own", result.stdout)

    def test_auto_bridge_failure_stops_without_changing_path(self):
        result = self.run_graphics(mode="auto")
        self.assertEqual(69, result.returncode, result.stderr + result.stdout)
        self.assertIn("MaliBridge self-test failed (memory)", result.stdout)
        self.assertNotIn("CONTINUED", result.stdout)
        self.assertFalse((self.root / "mali-graphics-path").exists())

    def test_base_runtime_without_vulkaninfo_keeps_fixed_and_auto_paths(self):
        for path in ("panvk", "venus"):
            for mode in ("auto", path):
                with self.subTest(path=path, mode=mode):
                    result = self.run_graphics(mode=mode, path=path, with_vulkaninfo=False)
                    self.assertEqual(0, result.returncode, result.stderr + result.stdout)
                    self.assertIn(f"path={path} gl=zink software=0", result.stdout)
                    self.assertIn("skipping the optional Vulkan self-test", result.stdout)
                    self.assertFalse((self.root / "mali-graphics-error").exists())
                    self.assertFalse((self.root / "mali-graphics-path").exists())

    def test_passing_panvk_and_venus_probes_confirm_the_resolved_path(self):
        for path in ("panvk", "venus"):
            for mode in ("auto", path):
                with self.subTest(path=path, mode=mode):
                    result = self.run_graphics(mode=mode, path=path)
                    self.assertEqual(0, result.returncode, result.stderr + result.stdout)
                    self.assertEqual(path + "\n", (self.root / "mali-graphics-path").read_text())

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
            for mode in ("auto", path):
                for status in (1, 124):
                    with self.subTest(path=path, mode=mode, status=status):
                        result = self.run_graphics(mode=mode, path=path, vulkan_status=status)
                        self.assertEqual(69, result.returncode, result.stderr + result.stdout)
                        self.assertNotIn("CONTINUED", result.stdout)
                        self.assertFalse((self.root / "mali-graphics-path").exists())

    def test_auto_venus_without_null_descriptor_uses_software_gl(self):
        result = self.run_graphics(mode="auto", path="venus", vulkan_info="nullDescriptor = false")
        self.assertEqual(0, result.returncode, result.stderr + result.stdout)
        self.assertIn("path=venus gl=llvmpipe software=1", result.stdout)

    def test_fixed_venus_gl_failure_stops_even_without_vulkaninfo(self):
        for status in (1, 124):
            with self.subTest(status=status):
                result = self.run_graphics(mode="venus", path="venus", with_vulkaninfo=False, gl_status=status)
                self.assertEqual(69, result.returncode, result.stderr + result.stdout)
                self.assertIn("Venus Zink self-test failed", (self.root / "mali-graphics-error").read_text())
                self.assertNotIn("CONTINUED", result.stdout)

    def test_auto_venus_gl_failure_keeps_venus_with_software_gl(self):
        result = self.run_graphics(mode="auto", path="venus", with_vulkaninfo=False, gl_status=1)
        self.assertEqual(0, result.returncode, result.stderr + result.stdout)
        self.assertIn("path=venus gl=llvmpipe software=1", result.stdout)

    def test_fixed_venus_keeps_zink_when_gl_probe_or_descriptor_variant_succeeds(self):
        for status, variant in ((0, ""), (1, "descriptors")):
            with self.subTest(status=status, variant=variant):
                result = self.run_graphics(mode="venus", path="venus", with_vulkaninfo=False,
                                           gl_status=status, gl_variant=variant)
                self.assertEqual(0, result.returncode, result.stderr + result.stdout)
                self.assertIn("path=venus gl=zink software=0", result.stdout)

    def test_fixed_venus_rejects_gl_that_only_works_without_the_compat_layer(self):
        result = self.run_graphics(mode="venus", path="venus", gl_status=1, gl_variant="no-compat")
        self.assertEqual(69, result.returncode, result.stderr + result.stdout)
        self.assertIn("Zink starts only without the Mali compatibility layer", result.stdout)
        self.assertFalse((self.root / "mali-graphics-path").exists())


if __name__ == "__main__":
    unittest.main()
