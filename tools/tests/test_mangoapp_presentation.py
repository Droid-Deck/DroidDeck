import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import unittest


MANGOAPP = Path(__file__).resolve().parents[1] / "mangoapp/mangoapp"
GRAPHICS_KEYS = (
    "DROIDDECK_SYSTEM_VULKAN_GPU", "DROIDDECK_SYSTEM_VULKAN_RUNTIME",
    "DROIDDECK_SYSTEM_VULKAN_PRESENTATION", "DROIDDECK_SYSTEM_VULKAN_DXVK",
    "DROIDDECK_HYBRIS_SESSION_LAYERS", "DROIDDECK_HYBRIS_LAYER_PATH",
    "DROIDDECK_HYBRIS_SESSION_BC_TEXTURES", "HYBRIS_BC_TEXTURES",
    "DROIDDECK_POWERVR_RUNTIME", "VK_LAYER_PATH", "VK_INSTANCE_LAYERS",
    "WSI_X11_FORCE_SHM", "ENABLE_GAMESCOPE_WSI", "VK_LOADER_LAYERS_DISABLE",
    "DISABLE_WSI_LAYER", "XDG_SESSION_TYPE", "WAYLAND_DISPLAY", "LD_LIBRARY_PATH",
)
OUTER_PROFILE = {
    "VK_LAYER_PATH": "/outer/lib",
    "VK_INSTANCE_LAYERS": "VK_LAYER_HYBRIS_compat",
    "WSI_X11_FORCE_SHM": "0",
    "ENABLE_GAMESCOPE_WSI": "1",
    "VK_LOADER_LAYERS_DISABLE": "outer_disabled_layer",
    "DISABLE_WSI_LAYER": "1",
    "XDG_SESSION_TYPE": "wayland",
    "WAYLAND_DISPLAY": "wayland-0",
    "LD_LIBRARY_PATH": "/outer/libraries",
}


class MangoAppPresentationTest(unittest.TestCase):
    def launch(self, inherited=None):
        env = {key: value for key, value in os.environ.items() if key not in GRAPHICS_KEYS}
        env.update(OUTER_PROFILE)
        env.update(inherited or {})
        with tempfile.TemporaryDirectory(prefix="droiddeck-mangoapp with spaces-") as tmp:
            root = Path(tmp)
            library = root / "lib"
            library.mkdir()
            binary = library / "mangoapp"
            binary.write_text(
                f"#!{sys.executable}\nimport json, os, sys\n"
                + 'print(json.dumps({"args":sys.argv[1:],"graphics":'
                + f'{{key:os.getenv(key) for key in {GRAPHICS_KEYS!r}}}}}))\n'
            )
            binary.chmod(0o755)
            starts = root / "starts"
            source = MANGOAPP.read_text()
            # Exercise the complete production wrapper; redirect only its absolute paths.
            for original, replacement in (
                ("starts=/tmp/droiddeck-mangoapp-starts", f"starts={shlex.quote(str(starts))}"),
                ("LD_LIBRARY_PATH=/usr/local/lib/mangoapp", f"LD_LIBRARY_PATH={shlex.quote(str(library))}"),
                ("exec /usr/local/lib/mangoapp/mangoapp", f"exec {shlex.quote(str(binary))}"),
            ):
                self.assertEqual(source.count(original), 1)
                source = source.replace(original, replacement, 1)
            wrapper = root / "mangoapp-wrapper"
            wrapper.write_text(source)
            args = ["--test-overlay", "argument with spaces", ""]
            # Like Gamescope, this parent starts MangoApp as a separate process.
            # Capture both sides of that boundary so the compositor keeps its outer profile.
            command = ('capture=$1; wrapper=$2; shift 2\n'
                       '"$capture" parent-before\n'
                       'sh "$wrapper" "$@" || exit "$?"\n'
                       '"$capture" parent-after\n')
            run = subprocess.run(
                ["sh", "-c", command, "gamescope-parent", str(binary), str(wrapper), *args],
                env=env, text=True, capture_output=True, timeout=5,
            )
            self.assertEqual(run.returncode, 0, run.stderr)
            before, child, after = [json.loads(line) for line in run.stdout.splitlines()]
            expected_parent = {key: env.get(key) for key in GRAPHICS_KEYS}
            self.assertEqual(before["graphics"], expected_parent)
            self.assertEqual(after["graphics"], expected_parent)
            self.assertEqual(child["args"], args)
            self.assertEqual(child["graphics"]["XDG_SESSION_TYPE"], "x11")
            self.assertEqual(child["graphics"]["WAYLAND_DISPLAY"], "wayland-0")
            self.assertEqual(child["graphics"]["LD_LIBRARY_PATH"], f"{library}:/outer/libraries")
            self.assertEqual(len(starts.read_text().splitlines()), 1)
            return child["graphics"]

    def assert_mali_profile(self, graphics, runtime):
        self.assertEqual(graphics["VK_LAYER_PATH"], f"{runtime}/wsi:{runtime}/lib")
        self.assertEqual(graphics["VK_INSTANCE_LAYERS"],
                         "VK_LAYER_window_system_integration:VK_LAYER_HYBRIS_compat")
        self.assertEqual(graphics["WSI_X11_FORCE_SHM"], "1")
        self.assertEqual(graphics["ENABLE_GAMESCOPE_WSI"], "0")
        self.assertEqual(graphics["VK_LOADER_LAYERS_DISABLE"], "*gamescope*")
        self.assertIsNone(graphics["DISABLE_WSI_LAYER"])

    def assert_outer_profile(self, graphics):
        for key, value in OUTER_PROFILE.items():
            if key not in ("XDG_SESSION_TYPE", "LD_LIBRARY_PATH"):
                self.assertEqual(graphics[key], value, key)

    def test_mali_uses_x11_shm_with_either_runtime_variable(self):
        runtime = "/runtime with spaces"
        for key in ("DROIDDECK_SYSTEM_VULKAN_RUNTIME", "DROIDDECK_POWERVR_RUNTIME"):
            with self.subTest(runtime_key=key):
                self.assert_mali_profile(self.launch({
                    "DROIDDECK_SYSTEM_VULKAN_GPU": "mali", key: runtime,
                }), runtime)

    def test_generic_runtime_takes_precedence_over_legacy_alias(self):
        self.assert_mali_profile(self.launch({
            "DROIDDECK_SYSTEM_VULKAN_GPU": "mali",
            "DROIDDECK_SYSTEM_VULKAN_RUNTIME": "/new runtime",
            "DROIDDECK_POWERVR_RUNTIME": "/old runtime",
        }), "/new runtime")

    def test_empty_generic_runtime_falls_back_to_legacy_alias(self):
        self.assert_mali_profile(self.launch({
            "DROIDDECK_SYSTEM_VULKAN_GPU": "mali",
            "DROIDDECK_SYSTEM_VULKAN_RUNTIME": "",
            "DROIDDECK_POWERVR_RUNTIME": "/legacy runtime",
        }), "/legacy runtime")

    def test_powervr_adreno_and_manual_drivers_keep_existing_profile(self):
        for gpu in ("powervr", "xclipse", "adreno", ""):
            with self.subTest(gpu=gpu):
                self.assert_outer_profile(self.launch({
                    "DROIDDECK_SYSTEM_VULKAN_GPU": gpu,
                    "DROIDDECK_SYSTEM_VULKAN_RUNTIME": "/runtime",
                    "DROIDDECK_POWERVR_RUNTIME": "/legacy runtime",
                }))

    def test_mali_without_a_system_runtime_keeps_existing_profile(self):
        for runtime_variables in ({}, {
            "DROIDDECK_SYSTEM_VULKAN_RUNTIME": "", "DROIDDECK_POWERVR_RUNTIME": "",
        }):
            with self.subTest(runtime_variables=runtime_variables):
                self.assert_outer_profile(self.launch({
                    "DROIDDECK_SYSTEM_VULKAN_GPU": "mali", **runtime_variables,
                }))

    def test_explicit_shm_preserves_vendor_layers_without_a_mali_identity(self):
        graphics = self.launch({
            "DROIDDECK_SYSTEM_VULKAN_GPU": "future-vendor", "DROIDDECK_SYSTEM_VULKAN_RUNTIME": "/runtime",
            "DROIDDECK_SYSTEM_VULKAN_PRESENTATION": "x11-shm",
            "DROIDDECK_HYBRIS_SESSION_LAYERS": "VK_LAYER_vendor_session",
            "DROIDDECK_HYBRIS_LAYER_PATH": "/vendor-layer",
        })
        self.assertEqual(graphics["VK_LAYER_PATH"], "/runtime/wsi:/runtime/lib:/vendor-layer")
        self.assertEqual(graphics["VK_INSTANCE_LAYERS"], "VK_LAYER_window_system_integration:VK_LAYER_vendor_session")
        self.assertEqual(graphics["WSI_X11_FORCE_SHM"], "1")

    def test_native_capability_overrides_mali_and_keeps_xclipse_outer_layers(self):
        for gpu in ("mali", "xclipse"):
            with self.subTest(gpu=gpu):
                graphics = self.launch({
                    "DROIDDECK_SYSTEM_VULKAN_GPU": gpu, "DROIDDECK_SYSTEM_VULKAN_RUNTIME": "/runtime",
                    "DROIDDECK_SYSTEM_VULKAN_PRESENTATION": "native",
                    "VK_LAYER_PATH": "/runtime/lib", "VK_INSTANCE_LAYERS": "VK_LAYER_HYBRIS_compat",
                    "DROIDDECK_HYBRIS_SESSION_LAYERS": "VK_LAYER_HYBRIS_compat",
                    "DROIDDECK_HYBRIS_SESSION_BC_TEXTURES": "0", "HYBRIS_BC_TEXTURES": "0",
                })
                self.assertEqual(graphics["VK_LAYER_PATH"], "/runtime/lib")
                self.assertEqual(graphics["VK_INSTANCE_LAYERS"], "VK_LAYER_HYBRIS_compat")
                self.assertEqual(graphics["HYBRIS_BC_TEXTURES"], "0")
                self.assertEqual(graphics["WSI_X11_FORCE_SHM"], "0")

if __name__ == "__main__":
    unittest.main()
