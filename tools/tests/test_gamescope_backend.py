import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SESSION = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-session"
DESKTOP_GPU = Path(__file__).resolve().parents[1] / "linuxfs/desktop/droiddeck-gpu"
GRAPHICS_KEYS = ("DROIDDECK_SYSTEM_VULKAN_GPU", "DROIDDECK_SYSTEM_VULKAN_RUNTIME", "DROIDDECK_POWERVR_RUNTIME",
                 "DROIDDECK_SYSTEM_VULKAN_PRESENTATION", "DROIDDECK_SYSTEM_VULKAN_DXVK",
                 "DROIDDECK_HYBRIS_SESSION_LAYERS", "DROIDDECK_HYBRIS_LAYER_PATH",
                 "DROIDDECK_HYBRIS_SESSION_BC_TEXTURES", "HYBRIS_BC_TEXTURES",
                 "VK_LAYER_PATH", "VK_INSTANCE_LAYERS", "WSI_X11_FORCE_SHM", "ENABLE_GAMESCOPE_WSI",
                 "VK_LOADER_LAYERS_DISABLE", "DISABLE_WSI_LAYER")


class GamescopeBackendTest(unittest.TestCase):
    def select(self, backend=None, mode="desktop"):
        source = SESSION.read_text()
        start = source.index('  backend=${BL_GAMESCOPE_BACKEND:-wayland}')
        end = source.index('\n', source.index('  args=(--backend ', start))
        script = 'width=1280; height=720; nested=1280\n' + source[start:end]
        script += '\nprintf "%s\\n" "${args[0]}" "${args[1]}" "${SDL_VIDEODRIVER:-unset}" "${gamescope_cursor_composite:-unset}"\n'
        env = dict(os.environ)
        env.pop("BL_GAMESCOPE_BACKEND", None)
        env.pop("SDL_VIDEODRIVER", None)
        env.pop("gamescope_cursor_composite", None)
        env["mode"] = mode
        if backend is not None:
            env["BL_GAMESCOPE_BACKEND"] = backend
        return subprocess.run(["bash", "-c", script], env=env, text=True,
                              capture_output=True, timeout=5)

    def test_default_retains_the_wayland_backend(self):
        result = self.select()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["--backend", "wayland", "unset", "unset"], result.stdout.splitlines())

    def test_sdl_uses_the_host_wayland_vulkan_swapchain(self):
        result = self.select("sdl")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["--backend", "sdl", "wayland", "unset"], result.stdout.splitlines())

    def test_steam_cursor_composition_preserves_backend_selection(self):
        for backend, sdl in (("wayland", "unset"), ("sdl", "wayland")):
            with self.subTest(backend=backend):
                result = self.select(backend, mode="steam")
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(["--backend", backend, sdl, "2"], result.stdout.splitlines())

    def test_unknown_backend_fails_before_gamescope(self):
        result = self.select("drm")
        self.assertEqual(64, result.returncode)
        self.assertIn("unsupported gamescope backend: drm", result.stdout)

    def test_mali_x11_profile_is_shared_by_all_gamescope_client_modes(self):
        source = SESSION.read_text()
        start = source.index('bl_system_runtime=${DROIDDECK_SYSTEM_VULKAN_RUNTIME:')
        end = source.index("# gamescope's reaper", start)
        # This common block runs before dispatching steam, run, or desktop and after exec gamescope.
        self.assertLess(source.index('  exec gamescope "${args[@]}"'), start)
        self.assertLess(end, source.index("case $mode in"))
        script = source[start:end] + '\nexec "$1" -c "$2"\n'
        probe = f'import json, os; print(json.dumps({{key:os.getenv(key) for key in {GRAPHICS_KEYS!r}}}))'
        for mode in ("steam", "run", "desktop"):
            with self.subTest(mode=mode):
                env = {key: value for key, value in os.environ.items() if key not in GRAPHICS_KEYS}
                env.update(mode=mode, DROIDDECK_SYSTEM_VULKAN_GPU="mali", DROIDDECK_SYSTEM_VULKAN_RUNTIME="/runtime",
                           DISABLE_WSI_LAYER="1")
                result = subprocess.run(["bash", "-c", script, "test", sys.executable, probe], env=env,
                                        text=True, capture_output=True, timeout=5)
                self.assertEqual(0, result.returncode, result.stderr)
                graphics = json.loads(result.stdout)
                self.assertEqual("/runtime/wsi:/runtime/lib", graphics["VK_LAYER_PATH"])
                self.assertEqual("VK_LAYER_window_system_integration:VK_LAYER_HYBRIS_compat", graphics["VK_INSTANCE_LAYERS"])
                self.assertEqual("1", graphics["WSI_X11_FORCE_SHM"])
                self.assertIsNone(graphics["DISABLE_WSI_LAYER"])


class DesktopGamescopeBackendTest(unittest.TestCase):
    def launch(self, backend=None, inherited=None):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            capture = root / "gamescope.json"
            gamescope = root / "gamescope"
            gamescope.write_text(
                f"#!{sys.executable}\n"
                "import json, os, subprocess, sys\n"
                "from pathlib import Path\n"
                "command = sys.argv[sys.argv.index('--') + 1:]\n"
                "window = command.index('/usr/local/bin/droiddeck-gpu-window')\n"
                "probe = 'import json, os, sys; print(json.dumps({\"env\":dict(os.environ),\"args\":sys.argv[1:]}))'\n"
                "command[window:window + 1] = [sys.executable, '-c', probe]\n"
                "client = json.loads(subprocess.check_output(command, text=True))\n"
                "Path(os.environ['GAMESCOPE_CAPTURE']).write_text(json.dumps({\n"
                "    'args': sys.argv[1:],\n"
                "    'sdl': os.environ.get('SDL_VIDEODRIVER'),\n"
                "    'gdk': os.environ.get('GDK_BACKEND'),\n"
                "    'qt': os.environ.get('QT_QPA_PLATFORM'),\n"
                "    'wayland': os.environ.get('WAYLAND_DISPLAY'),\n"
                f"    'graphics': {{key: os.environ.get(key) for key in {GRAPHICS_KEYS!r}}},\n"
                "    'client': client,\n"
                "}))\n"
            )
            gamescope.chmod(0o755)
            env = dict(os.environ)
            for name in ("BL_GAMESCOPE_BACKEND", "BL_DEBUG_DIR", "BL_FPS", "BL_REFRESH", "BL_HDR", *GRAPHICS_KEYS):
                env.pop(name, None)
            env.update(PATH=f"{root}{os.pathsep}{env.get('PATH', '')}",
                       GAMESCOPE_CAPTURE=str(capture), WAYLAND_DISPLAY="desktop-0",
                       SDL_VIDEODRIVER="dummy", GDK_BACKEND="wayland", QT_QPA_PLATFORM="wayland")
            if backend is not None:
                env["BL_GAMESCOPE_BACKEND"] = backend
            env.update(inherited or {})
            result = subprocess.run(["bash", str(DESKTOP_GPU), "demo", "one argument"],
                                    env=env, text=True, capture_output=True, timeout=5)
            return result, json.loads(capture.read_text()) if capture.exists() else None

    def assert_xwayland_application(self, capture):
        args = capture["args"]
        self.assertEqual(["env", "SDL_VIDEODRIVER=x11", "/usr/local/bin/droiddeck-gpu-window",
                          "demo", "one argument"], args[args.index("--") + 1:])
        self.assertEqual("x11", capture["gdk"])
        self.assertEqual("xcb", capture["qt"])
        self.assertEqual("wayland-0", capture["wayland"])
        self.assertEqual("x11", capture["client"]["env"]["SDL_VIDEODRIVER"])
        self.assertEqual(["demo", "one argument"], capture["client"]["args"])

    def test_default_keeps_wayland_and_xwayland_application(self):
        result, capture = self.launch()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["--backend", "wayland"], capture["args"][:2])
        self.assertEqual("x11", capture["sdl"])
        self.assert_xwayland_application(capture)

    def test_sdl_uses_wayland_without_leaking_to_the_application(self):
        result, capture = self.launch("sdl")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["--backend", "sdl"], capture["args"][:2])
        self.assertEqual("wayland", capture["sdl"])
        self.assert_xwayland_application(capture)

    def test_unknown_backend_fails_before_gamescope(self):
        result, capture = self.launch("drm")
        self.assertEqual(64, result.returncode)
        self.assertIn("unsupported gamescope backend: drm", result.stderr)
        self.assertIsNone(capture)

    def test_mali_desktop_apps_use_x11_shm_without_affecting_gamescope(self):
        for runtime_key in ("DROIDDECK_SYSTEM_VULKAN_RUNTIME", "DROIDDECK_POWERVR_RUNTIME"):
            with self.subTest(runtime_key=runtime_key):
                result, capture = self.launch("sdl", {
                    "DROIDDECK_SYSTEM_VULKAN_GPU": "mali", runtime_key: "/runtime with spaces",
                    "VK_LAYER_PATH": "/runtime with spaces/lib", "VK_INSTANCE_LAYERS": "VK_LAYER_HYBRIS_compat",
                    "DISABLE_WSI_LAYER": "1", "WSI_X11_FORCE_SHM": "0",
                })
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("wayland", capture["sdl"])
                self.assertEqual("/runtime with spaces/lib", capture["graphics"]["VK_LAYER_PATH"])
                self.assertEqual("VK_LAYER_HYBRIS_compat", capture["graphics"]["VK_INSTANCE_LAYERS"])
                self.assertIsNone(capture["graphics"]["WSI_X11_FORCE_SHM"])
                self.assertEqual("1", capture["graphics"]["DISABLE_WSI_LAYER"])
                client = capture["client"]["env"]
                self.assertEqual("x11", client["SDL_VIDEODRIVER"])
                self.assertEqual("/runtime with spaces/wsi:/runtime with spaces/lib", client["VK_LAYER_PATH"])
                self.assertEqual("VK_LAYER_window_system_integration:VK_LAYER_HYBRIS_compat", client["VK_INSTANCE_LAYERS"])
                self.assertEqual("1", client["WSI_X11_FORCE_SHM"])
                self.assertEqual("0", client["ENABLE_GAMESCOPE_WSI"])
                self.assertEqual("*gamescope*", client["VK_LOADER_LAYERS_DISABLE"])
                self.assertNotIn("DISABLE_WSI_LAYER", client)
                self.assertEqual(["demo", "one argument"], capture["client"]["args"])

    def test_manual_and_powervr_profiles_keep_inherited_vulkan_settings(self):
        for gpu, runtime in (("powervr", "/runtime"), ("xclipse", "/runtime"), ("mali", ""), ("", "/runtime")):
            with self.subTest(gpu=gpu, runtime=runtime):
                result, capture = self.launch("sdl", {
                    "DROIDDECK_SYSTEM_VULKAN_GPU": gpu, "DROIDDECK_SYSTEM_VULKAN_RUNTIME": runtime,
                    "VK_LAYER_PATH": "custom/layers", "VK_INSTANCE_LAYERS": "custom_layer", "WSI_X11_FORCE_SHM": "0",
                })
                self.assertEqual(0, result.returncode, result.stderr)
                self.assert_xwayland_application(capture)
                for key in ("VK_LAYER_PATH", "VK_INSTANCE_LAYERS", "WSI_X11_FORCE_SHM"):
                    self.assertEqual(capture["graphics"][key], capture["client"]["env"][key])

    def test_nested_mali_launch_resets_gamescope_to_the_wayland_profile(self):
        result, capture = self.launch("sdl", {
            "DROIDDECK_SYSTEM_VULKAN_GPU": "mali", "DROIDDECK_SYSTEM_VULKAN_RUNTIME": "/new-runtime",
            "DROIDDECK_POWERVR_RUNTIME": "/old-runtime",
            "VK_LAYER_PATH": "/new-runtime/wsi:/new-runtime/lib",
            "VK_INSTANCE_LAYERS": "VK_LAYER_window_system_integration:VK_LAYER_HYBRIS_compat",
            "WSI_X11_FORCE_SHM": "1", "ENABLE_GAMESCOPE_WSI": "0", "VK_LOADER_LAYERS_DISABLE": "*gamescope*",
        })
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("/new-runtime/lib", capture["graphics"]["VK_LAYER_PATH"])
        self.assertEqual("VK_LAYER_HYBRIS_compat", capture["graphics"]["VK_INSTANCE_LAYERS"])
        for key in ("WSI_X11_FORCE_SHM", "ENABLE_GAMESCOPE_WSI", "VK_LOADER_LAYERS_DISABLE"):
            self.assertIsNone(capture["graphics"][key])
        self.assertEqual("/new-runtime/wsi:/new-runtime/lib", capture["client"]["env"]["VK_LAYER_PATH"])
        self.assertEqual("1", capture["client"]["env"]["WSI_X11_FORCE_SHM"])

    def test_shm_capability_preserves_vendor_layers_across_nested_launch(self):
        result, capture = self.launch("sdl", {
            "DROIDDECK_SYSTEM_VULKAN_GPU": "future-vendor", "DROIDDECK_SYSTEM_VULKAN_RUNTIME": "/runtime",
            "DROIDDECK_SYSTEM_VULKAN_PRESENTATION": "x11-shm",
            "DROIDDECK_HYBRIS_SESSION_LAYERS": "VK_LAYER_vendor_session",
            "DROIDDECK_HYBRIS_LAYER_PATH": "/vendor-layer",
            "VK_LAYER_PATH": "/runtime/wsi:/runtime/lib:/vendor-layer",
            "VK_INSTANCE_LAYERS": "VK_LAYER_window_system_integration:VK_LAYER_vendor_session",
            "WSI_X11_FORCE_SHM": "1",
        })
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("/runtime/lib:/vendor-layer", capture["graphics"]["VK_LAYER_PATH"])
        self.assertEqual("VK_LAYER_vendor_session", capture["graphics"]["VK_INSTANCE_LAYERS"])
        self.assertIsNone(capture["graphics"]["WSI_X11_FORCE_SHM"])
        client = capture["client"]["env"]
        self.assertEqual("/runtime/wsi:/runtime/lib:/vendor-layer", client["VK_LAYER_PATH"])
        self.assertEqual("VK_LAYER_window_system_integration:VK_LAYER_vendor_session", client["VK_INSTANCE_LAYERS"])
        self.assertEqual("1", client["WSI_X11_FORCE_SHM"])

    def test_native_capability_overrides_legacy_mali_and_preserves_xclipse(self):
        for gpu in ("mali", "xclipse"):
            with self.subTest(gpu=gpu):
                result, capture = self.launch("sdl", {
                    "DROIDDECK_SYSTEM_VULKAN_GPU": gpu, "DROIDDECK_SYSTEM_VULKAN_RUNTIME": "/runtime",
                    "DROIDDECK_SYSTEM_VULKAN_PRESENTATION": "native",
                    "DROIDDECK_HYBRIS_SESSION_LAYERS": "VK_LAYER_HYBRIS_compat",
                    "DROIDDECK_HYBRIS_SESSION_BC_TEXTURES": "0", "HYBRIS_BC_TEXTURES": "0",
                    "VK_LAYER_PATH": "/runtime/lib",
                    "VK_INSTANCE_LAYERS": "VK_LAYER_HYBRIS_compat", "WSI_X11_FORCE_SHM": "0",
                })
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("x11", capture["client"]["env"]["SDL_VIDEODRIVER"])
                self.assertEqual(["demo", "one argument"], capture["client"]["args"])
                self.assertIsNone(capture["graphics"]["WSI_X11_FORCE_SHM"])
                self.assertEqual("0", capture["client"]["env"]["WSI_X11_FORCE_SHM"])
                for key in ("VK_LAYER_PATH", "VK_INSTANCE_LAYERS", "HYBRIS_BC_TEXTURES"):
                    self.assertEqual(capture["graphics"][key], capture["client"]["env"][key])

    def test_nested_native_xclipse_restores_session_but_preserves_game_client(self):
        inherited = {
            "DROIDDECK_SYSTEM_VULKAN_GPU": "xclipse", "DROIDDECK_SYSTEM_VULKAN_RUNTIME": "/runtime",
            "DROIDDECK_SYSTEM_VULKAN_PRESENTATION": "native",
            "DROIDDECK_HYBRIS_SESSION_LAYERS": "VK_LAYER_HYBRIS_compat",
            "DROIDDECK_HYBRIS_SESSION_BC_TEXTURES": "0", "HYBRIS_BC_TEXTURES": "dxvk",
            "VK_LAYER_PATH": "/runtime/wsi:/runtime/lib",
            "VK_INSTANCE_LAYERS": "VK_LAYER_window_system_integration:VK_LAYER_HYBRIS_compat",
            "ENABLE_GAMESCOPE_WSI": "0", "VK_LOADER_LAYERS_DISABLE": "*gamescope*",
        }
        result, capture = self.launch("sdl", inherited)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("wayland", capture["sdl"])
        self.assertEqual("0", capture["graphics"]["HYBRIS_BC_TEXTURES"])
        self.assertEqual("/runtime/lib", capture["graphics"]["VK_LAYER_PATH"])
        self.assertEqual("VK_LAYER_HYBRIS_compat", capture["graphics"]["VK_INSTANCE_LAYERS"])
        for key in ("WSI_X11_FORCE_SHM", "ENABLE_GAMESCOPE_WSI", "VK_LOADER_LAYERS_DISABLE"):
            self.assertIsNone(capture["graphics"][key])
        client = capture["client"]["env"]
        for key in ("HYBRIS_BC_TEXTURES", "VK_LAYER_PATH", "VK_INSTANCE_LAYERS",
                    "ENABLE_GAMESCOPE_WSI", "VK_LOADER_LAYERS_DISABLE"):
            self.assertEqual(inherited[key], client[key])
        self.assertNotIn("WSI_X11_FORCE_SHM", client)
        self.assertEqual("x11", client["SDL_VIDEODRIVER"])
        self.assertEqual(["demo", "one argument"], capture["client"]["args"])

    def test_nested_session_bc_does_not_leak_into_unset_or_empty_client_policy(self):
        for presentation in ("native", "x11-shm"):
            for policy in (None, "", "dxvk"):
                with self.subTest(presentation=presentation, policy=policy):
                    inherited = {
                        "DROIDDECK_SYSTEM_VULKAN_RUNTIME": "/runtime",
                        "DROIDDECK_SYSTEM_VULKAN_PRESENTATION": presentation,
                        "DROIDDECK_HYBRIS_SESSION_LAYERS": "VK_LAYER_HYBRIS_compat",
                        "DROIDDECK_HYBRIS_SESSION_BC_TEXTURES": "0",
                    }
                    if policy is not None:
                        inherited["HYBRIS_BC_TEXTURES"] = policy
                    result, capture = self.launch("sdl", inherited)
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertEqual("0", capture["graphics"]["HYBRIS_BC_TEXTURES"])
                    client = capture["client"]["env"]
                    self.assertEqual(policy, client.get("HYBRIS_BC_TEXTURES"))
                    if presentation == "native":
                        self.assertNotIn("VK_LAYER_PATH", client)
                        self.assertNotIn("VK_INSTANCE_LAYERS", client)
                    else:
                        self.assertEqual("1", client["WSI_X11_FORCE_SHM"])
                        self.assertEqual("/runtime/wsi:/runtime/lib", client["VK_LAYER_PATH"])
                        self.assertEqual("VK_LAYER_window_system_integration:VK_LAYER_HYBRIS_compat", client["VK_INSTANCE_LAYERS"])
