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
        self.bin = self.root / "bin"
        self.bin.mkdir()
        for tool in ("sed", "head", "grep", "rm", "env", "timeout", "tail", "sleep", "mktemp"):
            (self.bin / tool).symlink_to(shutil.which(tool))
        self.icd = self.root / "bridge.json"
        self.icd.touch()
        self.lib = self.root / "bridge.so"
        self.lib.touch()
        self.vulkaninfo = self.bin / "vulkaninfo"
        self.vulkaninfo.write_text('#!/bin/bash\nprintf "%s\\n" "$VULKAN_INFO"\nexit "$VULKAN_STATUS"\n')
        self.vulkaninfo.chmod(0o755)
        self.glprobe = self.bin / "glprobe"
        self.glprobe.write_text('''#!/bin/bash
if [ "$GL_VERBOSE" = 1 ]; then
  for ((i=0; i<20000; i++)); do echo "OpenGL verbose line $i"; done
fi
if [ "$GL_VARIANT" = descriptors ] && [ "${ZINK_DESCRIPTORS:-}" = auto ]; then exit 0; fi
exit "$GL_STATUS"
''')
        self.glprobe.chmod(0o755)
        sock = socket.socket(socket.AF_UNIX)
        sock.bind(str(self.root / "server.sock"))
        self.addCleanup(sock.close)
        text = SESSION.read_text()
        self.graphics = text[text.index("dd_now_ms() {"):text.index('if [ "${DD_GAME_LOGS:-0}" = 1 ]')]
        self.graphics = self.graphics.replace("/usr/local/share/vkbridge/droidbridge_icd.json", str(self.icd))
        self.graphics = self.graphics.replace("/usr/local/lib/vkbridge/libvulkan_droidbridge.so", str(self.lib))
        self.gl = text[text.index('    if [ "${DD_VENUS:-0}" = 1 ]'):text.index('    rm -f "$HOME/.dd-restart-game"')]
        self.gl = self.gl.replace("/usr/local/bin/droiddeck-glprobe", str(self.glprobe))
        self.gamescope = text[text.index("run_gamescope() ("):text.index("collect_steam_logs() {")]

    def run_graphics(self, mode="bridge", path="bridge", smoke_status=1, vulkan_status=0,
                     info="nullDescriptor = true", gl_status=None, variant="", verbose=False,
                     missing_socket=False, stopping=False, nested=False):
        exit_file = self.root / "steam-exit"
        exit_file.unlink(missing_ok=True)
        sock = str(self.root / ("absent.sock" if missing_socket else "server.sock"))
        env = dict(os.environ, PATH=str(self.bin), XDG_RUNTIME_DIR=str(self.root), DD_INSIDE="",
                   DD_MALI_GRAPHICS_MODE=mode, DD_VKBRIDGE=str(int(path == "bridge")), DD_VENUS=str(int(path == "venus")),
                   DD_DEBUG_DIR=str(self.root), DD_STEAM_EXIT_FILE=str(exit_file),
                   DD_VKBRIDGE_FALLBACK_ICD=str(self.icd), VKBRIDGE_SOCKET=sock, VTEST_SOCKET_NAME=sock,
                   DD_VENUS_WAIT_MS="100", DD_VKBRIDGE_WAIT_MS="100", dd_stopping="1" if stopping else "",
                   SMOKE_STATUS=str(smoke_status), VULKAN_STATUS=str(vulkan_status), VULKAN_INFO=info,
                   GALLIUM_DRIVER="zink", LIBGL_ALWAYS_SOFTWARE="0", ZINK_DESCRIPTORS="lazy",
                   GL_STATUS=str(gl_status), GL_VARIANT=variant, GL_VERBOSE=str(int(verbose)))
        stubs = '''
step() { :; }
timeout() {
  if [ "$2" = /usr/local/bin/droiddeck-vkbridge-smoke ]; then
    [ "$SMOKE_STATUS" = 0 ] || echo 'FAIL memory sharing'
    return "$SMOKE_STATUS"
  fi
  command timeout "$@"
}
'''
        script = stubs + self.graphics + (self.gl if gl_status is not None else "")
        if nested:
            child = self.root / "child.sh"
            child.write_text(script)
            env["CHILD"] = str(child)
            script = self.gamescope + '\ngamescope() { /bin/bash "$CHILD"; return 0; }\nrun_gamescope\nexit $?'
        else:
            script += '\necho "CONTINUED bridge=$DD_VKBRIDGE gl=$GALLIUM_DRIVER"'
        return subprocess.run(["/bin/bash", "-uc", script], env=env, capture_output=True, text=True, timeout=5)

    def test_fixed_bridge_stops_where_auto_keeps_existing_fallback(self):
        for status in (1, 124):
            result = self.run_graphics(smoke_status=status)
            self.assertEqual(69, result.returncode, result.stderr + result.stdout)
            self.assertIn("MaliBridge self-test failed", result.stdout)
        result = self.run_graphics(mode="auto")
        self.assertEqual(0, result.returncode, result.stderr + result.stdout)
        self.assertIn("CONTINUED bridge=0", result.stdout)

    def test_fixed_bridge_success_keeps_bridge(self):
        result = self.run_graphics(smoke_status=0)
        self.assertEqual(0, result.returncode, result.stderr + result.stdout)
        self.assertIn("CONTINUED bridge=1", result.stdout)

    def test_child_failure_survives_gamescope_success(self):
        result = self.run_graphics(mode="venus", path="venus", gl_status=1, nested=True)
        self.assertEqual(69, result.returncode, result.stderr + result.stdout)
        self.assertIn("Venus GL through Zink could not start", result.stdout)

    def test_missing_optional_tool_keeps_venus(self):
        self.vulkaninfo.unlink()
        for mode in ("auto", "venus"):
            result = self.run_graphics(mode=mode, path="venus")
            self.assertEqual(0, result.returncode, result.stderr + result.stdout)
            self.assertIn("gl=zink", result.stdout)

    def test_fixed_venus_rejects_failed_probe_or_missing_null_descriptor(self):
        for status, info in ((1, ""), (124, ""), (0, "nullDescriptor = false")):
            result = self.run_graphics(mode="venus", path="venus", vulkan_status=status, info=info)
            self.assertEqual(69, result.returncode, result.stderr + result.stdout)
        result = self.run_graphics(mode="auto", path="venus", info="nullDescriptor = false")
        self.assertEqual(0, result.returncode, result.stderr + result.stdout)
        self.assertIn("gl=llvmpipe", result.stdout)

    def test_gl_success_and_accepted_variant_keep_fixed_venus_without_vulkaninfo(self):
        self.vulkaninfo.unlink()
        for status, variant in ((0, ""), (1, "descriptors")):
            result = self.run_graphics(mode="venus", path="venus", gl_status=status, variant=variant, verbose=True)
            self.assertEqual(0, result.returncode, result.stderr + result.stdout)
            self.assertIn("gl=zink", result.stdout)

    def test_failed_gl_stops_fixed_venus_but_keeps_auto_software_policy(self):
        self.vulkaninfo.unlink()
        for mode, expected in (("venus", 69), ("auto", 0)):
            result = self.run_graphics(mode=mode, path="venus", gl_status=1)
            self.assertEqual(expected, result.returncode, result.stderr + result.stdout)
            if mode == "auto": self.assertIn("gl=llvmpipe", result.stdout)

    def test_missing_socket_fails_fixed_choices_but_stop_is_cancellation(self):
        self.vulkaninfo.unlink()
        for path in ("bridge", "venus"):
            result = self.run_graphics(mode=path, path=path, missing_socket=True)
            self.assertEqual(69, result.returncode, result.stderr + result.stdout)
            result = self.run_graphics(mode=path, path=path, missing_socket=True, stopping=True)
            self.assertEqual(0, result.returncode, result.stderr + result.stdout)
            self.assertNotIn("mali graphics failed", result.stdout)


if __name__ == "__main__":
    unittest.main()
