import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import unittest

WRAPPER = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-steam-taskset"
GRAPHICS_KEYS = ("DROIDDECK_SYSTEM_VULKAN_RUNTIME", "DROIDDECK_POWERVR_RUNTIME",
                 "DROIDDECK_SYSTEM_VULKAN_PRESENTATION", "DROIDDECK_SYSTEM_VULKAN_GPU",
                 "SDL_FRAMEBUFFER_ACCELERATION")


class SteamHelperAffinityTest(unittest.TestCase):
    def launch(self, status, form="helper", configured=True, inherited=None, wsi=False):
        with tempfile.TemporaryDirectory(prefix="droiddeck-helper-") as temporary:
            root = Path(temporary)
            helper = root / "steamwebhelper"
            helper.write_text(f"#!{sys.executable}\n" +
                              'import json, os, sys\n'
                              'with open(os.environ["STARTS"],"a") as f: f.write(json.dumps(sys.argv[1:])+"\\n")\n'
                              'with open(os.environ["ENVIRONMENT"],"a") as f: f.write(json.dumps(["helper",os.getenv("SDL_FRAMEBUFFER_ACCELERATION")])+"\\n")\n'
                              'with open(os.environ["KOPPER"],"a") as f: f.write(str(os.getenv("LIBGL_KOPPER_DISABLE"))+"\\n")\n'
                              'sys.exit(23)\n')
            helper.chmod(0o755)
            real = root / "real-taskset"
            real.write_text(f"#!{sys.executable}\n" +
                            'import json, os, sys\n'
                            'with open(os.environ["TRACE"],"a") as f: f.write(json.dumps(sys.argv[1:])+"\\n")\n'
                            'with open(os.environ["ENVIRONMENT"],"a") as f: f.write(json.dumps(["taskset",os.getenv("SDL_FRAMEBUFFER_ACCELERATION")])+"\\n")\n'
                            f'sys.exit({status})\n')
            real.chmod(0o755)
            wrapper = root / "taskset"
            wrapper.write_text(WRAPPER.read_text().replace("/usr/bin/taskset", shlex.quote(str(real))))
            helper_path = helper
            if form == "alias":
                helper_path = root / "helper symlink"
                helper_path.symlink_to(helper)
            elif form == "other":
                helper_path = root / "other/steamwebhelper"
                helper_path.parent.mkdir()
                helper_path.write_text(helper.read_text())
                helper_path.chmod(0o755)
            args = ["0x7c", str(helper_path), "arg with spaces", "--literal=$HOME"]
            if form == "query":
                args = ["-p", "12345"]
            elif form == "invalid":
                args[0] = "not-a-mask"
            elif form == "empty":
                args = []
            env = dict(os.environ, TRACE=str(root / "trace"), STARTS=str(root / "starts"),
                       ENVIRONMENT=str(root / "environment"), KOPPER=str(root / "kopper"))
            for key in GRAPHICS_KEYS:
                env.pop(key, None)
            env.pop("BL_STEAM_GL_WSI_ACTIVE", None)
            env.pop("LIBGL_KOPPER_DISABLE", None)
            env.update(inherited or {})
            env.pop("BL_STEAM_WEBHELPER", None)
            if configured:
                env["BL_STEAM_WEBHELPER"] = str(helper)
            if wsi:
                env.update(BL_STEAM_GL_WSI_ACTIVE="1", LIBGL_KOPPER_DISABLE="true")
            result = subprocess.run(["bash", str(wrapper), *args], env=env,
                                    capture_output=True, text=True, timeout=5)
            trace = [json.loads(x) for x in (root / "trace").read_text().splitlines()]
            starts = ([json.loads(x) for x in (root / "starts").read_text().splitlines()]
                      if (root / "starts").exists() else [])
            environment = [json.loads(x) for x in (root / "environment").read_text().splitlines()]
            kopper = ((root / "kopper").read_text().splitlines() if (root / "kopper").exists() else [])
            return result, trace, starts, args, environment, kopper

    def test_denied_pinning_still_executes_helper_once(self):
        result, trace, starts, args, environment, _ = self.launch(1)
        self.assertEqual(result.returncode, 23)
        self.assertEqual(starts, [args[2:]])
        self.assertEqual(len(trace), 1)
        self.assertEqual(trace[0][:2], ["-p", "0x7c"])
        self.assertRegex(trace[0][2], r"^[1-9][0-9]*$")
        self.assertIn("using the allowed CPUs", result.stderr)
        self.assertEqual(environment, [["taskset", None], ["helper", None]])

    def test_successful_pinning_preserves_helper_exit_status(self):
        result, trace, starts, args, _, _ = self.launch(0)
        self.assertEqual(result.returncode, 23)
        self.assertEqual(starts, [args[2:]])
        self.assertEqual(trace[0][:2], ["-p", "0x7c"])
        self.assertNotIn("pinning unavailable", result.stderr)

    def test_symlink_to_actual_helper_is_recognized(self):
        result, _, starts, args, _, _ = self.launch(1, "alias")
        self.assertEqual(result.returncode, 23)
        self.assertEqual(starts, [args[2:]])

    def test_wsi_path_overrides_valves_kopper_disable(self):
        result, _, starts, args, _, kopper = self.launch(0, wsi=True)
        self.assertEqual(result.returncode, 23)
        self.assertEqual(starts, [args[2:]])
        self.assertEqual(kopper, ["None"])

    def test_other_taskset_forms_and_targets_pass_through(self):
        for form, configured in [("other", True), ("query", True), ("invalid", True),
                                 ("empty", True), ("helper", False)]:
            with self.subTest(form=form, configured=configured):
                result, trace, starts, args, _, _ = self.launch(47, form, configured)
                self.assertEqual(result.returncode, 47)
                self.assertEqual(trace, [args])
                self.assertEqual(starts, [])
                self.assertNotIn("pinning unavailable", result.stderr)

    def test_shm_disables_framebuffer_acceleration_only_for_actual_helper(self):
        profiles = (
            {"DROIDDECK_SYSTEM_VULKAN_RUNTIME": "/runtime", "DROIDDECK_SYSTEM_VULKAN_PRESENTATION": "x11-shm",
             "DROIDDECK_SYSTEM_VULKAN_GPU": "future-vendor"},
            {"DROIDDECK_SYSTEM_VULKAN_RUNTIME": "/runtime", "DROIDDECK_SYSTEM_VULKAN_GPU": "mali"},
            {"DROIDDECK_POWERVR_RUNTIME": "/runtime", "DROIDDECK_SYSTEM_VULKAN_GPU": "mali"},
        )
        for profile in profiles:
            for form in ("helper", "alias"):
                with self.subTest(profile=profile, form=form):
                    result, _, starts, args, environment, _ = self.launch(
                        1, form, inherited=dict(profile, SDL_FRAMEBUFFER_ACCELERATION="1"))
                    self.assertEqual(result.returncode, 23)
                    self.assertEqual(starts, [args[2:]])
                    self.assertEqual(environment, [["taskset", "1"], ["helper", "0"]])

    def test_other_taskset_forms_and_targets_keep_framebuffer_environment(self):
        profile = {"DROIDDECK_SYSTEM_VULKAN_RUNTIME": "/runtime",
                   "DROIDDECK_SYSTEM_VULKAN_PRESENTATION": "x11-shm", "SDL_FRAMEBUFFER_ACCELERATION": "1"}
        for form, configured in [("other", True), ("query", True), ("invalid", True),
                                 ("empty", True), ("helper", False)]:
            with self.subTest(form=form, configured=configured):
                result, trace, starts, args, environment, _ = self.launch(47, form, configured, profile)
                self.assertEqual(result.returncode, 47)
                self.assertEqual(trace, [args])
                self.assertEqual(starts, [])
                self.assertEqual(environment, [["taskset", "1"]])

    def test_other_profiles_preserve_helper_framebuffer_environment(self):
        profiles = (
            {},
            {"DROIDDECK_SYSTEM_VULKAN_RUNTIME": "/runtime", "DROIDDECK_SYSTEM_VULKAN_GPU": "powervr"},
            {"DROIDDECK_SYSTEM_VULKAN_RUNTIME": "/runtime", "DROIDDECK_SYSTEM_VULKAN_GPU": "xclipse"},
            {"DROIDDECK_SYSTEM_VULKAN_RUNTIME": "/runtime", "DROIDDECK_SYSTEM_VULKAN_GPU": "mali",
             "DROIDDECK_SYSTEM_VULKAN_PRESENTATION": "native"},
            {"DROIDDECK_SYSTEM_VULKAN_PRESENTATION": "x11-shm"},
            {"DROIDDECK_SYSTEM_VULKAN_GPU": "mali"},
        )
        for profile in profiles:
            for inherited_value in (None, "1"):
                with self.subTest(profile=profile, inherited_value=inherited_value):
                    inherited = dict(profile)
                    if inherited_value is not None:
                        inherited["SDL_FRAMEBUFFER_ACCELERATION"] = inherited_value
                    result, _, starts, args, environment, _ = self.launch(0, inherited=inherited)
                    self.assertEqual(result.returncode, 23)
                    self.assertEqual(starts, [args[2:]])
                    self.assertEqual(environment, [["taskset", inherited_value], ["helper", inherited_value]])


if __name__ == "__main__":
    unittest.main()
