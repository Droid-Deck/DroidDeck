import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

PRELOAD = Path(__file__).resolve().parents[1] / "linuxfs/preload"

PROGRAM = r"""
#include <stdio.h>
#include <stdlib.h>
char *const *bl_webhelper_argv(const char *path, char *const argv[], char *const envp[]);
int main(int argc, char **argv) {
  const char *mode = getenv("TEST_NODE_MODE_CHILD");
  static char node[64];
  snprintf(node, sizeof node, "ELECTRON_RUN_AS_NODE=%s", mode ? mode : "");
  char *node_env[] = {node, NULL};
  char *plain_env[] = {"PATH=/usr/bin", NULL};
  char *const *envp = mode ? node_env : plain_env;
  char *const *out = bl_webhelper_argv(argv[1], argv + 1, (char *const *)envp);
  for (int i = 0; out[i]; i++) printf("%s\n", out[i]);
  return 0;
}
"""

WEBHELPER = "/root/.local/share/Steam/steamrtarm64/steamwebhelper"


@unittest.skipUnless(shutil.which("cc"), "needs cc")
class WebhelperPreloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp())
        source = cls.tmp / "program.c"
        source.write_text(PROGRAM)
        cls.program = cls.tmp / "program"
        subprocess.run(["cc", "-O2", "-Wall", "-Werror", "-o", str(cls.program), str(source),
                        str(PRELOAD / "webhelper.c")], check=True)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def argv(self, *args, spec=None, node_child=False, parent_node=False):
        env = {k: v for k, v in os.environ.items() if k != "BL_WEBHELPER_ARGS"}
        env.pop("ELECTRON_RUN_AS_NODE", None)
        if spec is not None:
            env["BL_WEBHELPER_ARGS"] = spec
        if node_child is not False:
            env["TEST_NODE_MODE_CHILD"] = "1" if node_child is True else node_child
        else:
            env.pop("TEST_NODE_MODE_CHILD", None)
        if parent_node:
            env["ELECTRON_RUN_AS_NODE"] = "1"
        result = subprocess.run([str(self.program), *args], env=env, capture_output=True, text=True, check=True)
        return result.stdout.splitlines()

    def chromium(self, directory, name):
        root = self.tmp / directory
        root.mkdir(exist_ok=True)
        executable = root / name
        executable.touch()
        (root / "chrome-sandbox").touch()
        return str(executable)

    def test_the_browser_process_keeps_its_helpers_in_process(self):
        out = self.argv(WEBHELPER, "-lang=en_US", "--enable-features=A", "--no-sandbox", "--disable-features=B,C")
        self.assertEqual(out, [WEBHELPER, "-lang=en_US", "--no-sandbox", "--no-zygote", "--in-process-gpu",
                               "--enable-features=A", "--disable-features=B,C,AudioServiceOutOfProcess"])

    def test_feature_lists_are_added_when_steam_passes_none(self):
        out = self.argv(WEBHELPER, spec="+X -Y")
        self.assertEqual(out, [WEBHELPER, "--enable-features=X", "--disable-features=Y"])

    def test_child_processes_and_other_programs_are_left_alone(self):
        child = [WEBHELPER, "--type=renderer", "--enable-features=A"]
        self.assertEqual(self.argv(*child), child)
        other = ["/usr/bin/steam", "--enable-features=A"]
        self.assertEqual(self.argv(*other), other)

    def test_node_mode_from_the_child_environment_skips_sandbox_injection(self):
        code = self.chromium("vscode", "code")
        self.assertEqual(self.argv(code, node_child=True), [code])

    def test_any_nonempty_node_mode_value_skips_sandbox_injection(self):
        code = self.chromium("vscode-true", "code")
        for value in ("true", "0", "yes"):
            self.assertEqual(self.argv(code, node_child=value), [code])

    def test_empty_node_mode_value_still_gets_sandbox_flag(self):
        chrome = self.chromium("chromium-empty-mode", "chrome")
        self.assertEqual(self.argv(chrome, node_child=""), [chrome, "--no-sandbox"])

    def test_non_browser_helper_beside_chrome_sandbox_skips_injection_in_node_mode(self):
        helper = self.chromium("vscode-helper", "remote-cli")
        self.assertEqual(self.argv(helper, node_child=True), [helper])

    def test_chromium_gui_launch_still_gets_sandbox_flag_from_explicit_environment(self):
        chrome = self.chromium("chromium", "chrome")
        self.assertEqual(self.argv(chrome), [chrome, "--no-sandbox"])

    def test_parent_node_mode_does_not_override_the_explicit_child_environment(self):
        chrome = self.chromium("chromium-parent-mode", "chrome")
        self.assertEqual(self.argv(chrome, parent_node=True), [chrome, "--no-sandbox"])

    def test_stock_turns_it_off(self):
        args = [WEBHELPER, "--enable-features=A"]
        self.assertEqual(self.argv(*args, spec="stock"), args)

    def test_angle_switches_are_appended_with_the_gpu_process_kept_out_of_process(self):
        out = self.argv(WEBHELPER, "-lang=en_US", "--enable-features=A", spec="--use-gl=angle --use-angle=vulkan")
        self.assertEqual(out, [WEBHELPER, "-lang=en_US", "--use-gl=angle", "--use-angle=vulkan",
                               "--enable-features=A"])
        self.assertNotIn("--in-process-gpu", out)

    def test_vulkan_compositing_features_join_steams_feature_list(self):
        out = self.argv(WEBHELPER, "--enable-features=PlatformHEVCDecoderSupport,V4L2VideoDecode",
                        spec="--use-gl=angle --use-angle=vulkan +Vulkan +VulkanFromANGLE +DefaultANGLEVulkan")
        self.assertEqual(out, [WEBHELPER, "--use-gl=angle", "--use-angle=vulkan",
                               "--enable-features=PlatformHEVCDecoderSupport,V4L2VideoDecode,"
                               "Vulkan,VulkanFromANGLE,DefaultANGLEVulkan"])

    def test_an_empty_setting_means_the_default(self):
        self.assertIn("--no-zygote", self.argv(WEBHELPER, spec=""))


if __name__ == "__main__":
    unittest.main()
