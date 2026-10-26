import hashlib
import json
import os
import shutil
from pathlib import Path
import runpy
import struct
import shlex

import subprocess
import sys
import tempfile
from unittest import mock
import unittest
from unittest.mock import patch

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
MODULE = runpy.run_path(str(BIN / "droiddeck-game-env"))
COMPAT = runpy.run_path(str(BIN / "steam-compatibility"))
STATE = tempfile.TemporaryDirectory()
COMPAT["main"].__globals__["COMPAT_DIR"] = STATE.name


def mapping_of(text):
    tokens = COMPAT["tokenize"](text)
    return COMPAT["read_mapping"](tokens, COMPAT["mapping_block"](tokens))


def config_text(entries):
    mapping = "".join('"%s" { "name" "%s" "config" "" "priority" "%s" }' % (app, name, "75" if app == "0" else "250") for app, name in entries)
    return '"InstallConfigStore" { "Software" { "Valve" { "Steam" { "CompatToolMapping" { %s } } } } }' % mapping


class SystemGraphicsLaunchTest(unittest.TestCase):
    def runtime(self, root, payload="dxvk", architectures=("x32", "x64")):
        runtime = root / "system_vulkan" / ("a" * 64)
        files = ["run-proton.py", "lib/libVkLayer_hybris_compat.so",
                 "wsi/libVkLayer_window_system_integration.so",
                 "wsi/VkLayer_window_system_integration.json"]
        files += ["%s/%s/%s.dll" % (payload, arch, name) for arch in architectures
                  for name in ("d3d8", "d3d9", "d3d10core", "d3d11", "dxgi")]
        for name in files:
            target = runtime / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(b"component")
        (runtime / "run-proton.py").chmod(0o755)
        return runtime, files
    def test_other_gpus_and_non_game_calls_keep_the_original_launch(self):
        command = ["/proton", "waitforexitandrun", "game.exe"]
        base = {"STEAM_COMPAT_DATA_PATH": "/compatdata/42", "VK_LAYER_PATH": "original"}
        selected = dict(base, DROIDDECK_POWERVR_RUNTIME="/not-installed")
        cases = [(command, base), (command, dict(selected, STEAM_COMPAT_DATA_PATH="/compatdata/0")),
                 (command, dict(selected, STEAM_COMPAT_DATA_PATH="/compatdata/invalid")),
                 (["/proton", "run", "iscriptevaluator.exe"], selected),
                 (["/proton", "getcompatpath", "x"], selected), (command[:2], selected)]
        for args, env in cases:
            with self.subTest(args=args, env=env):
                actual, inherited = MODULE["system_graphics_launch"](args, env)
                self.assertIs(actual, args)
                self.assertIs(inherited, env)

    def test_system_graphics_follow_game_configuration_and_preserve_other_keys(self):
        with tempfile.TemporaryDirectory() as tmp:
            runtime, _ = self.runtime(Path(tmp))
            base = {"DROIDDECK_POWERVR_RUNTIME": str(runtime), "STEAM_COMPAT_DATA_PATH": "/compatdata/42"}
            config = {"version": 1, "games": {"42": {
                "PROTON_USE_WINED3D": "1", "DISABLE_WSI_LAYER": "1",
                "ENABLE_VK_LAYER_VALVE_steam_fossilize_1": "1", "HYBRIS_BC_TEXTURES": "0",
                "VK_LAYER_PATH": "old", "VK_INSTANCE_LAYERS": "old", "ENABLE_GAMESCOPE_WSI": "1",
                "FEX_TSOENABLED": "1", "WINESERVER": "/selected/wineserver", "CUSTOM": "literal $value",
            }}}
            env = MODULE["apply_config"](base, config, "42")
            command = ["/selected/proton", "waitforexitandrun", "game with spaces.exe"]
            launch, result = MODULE["system_graphics_launch"](command, env)
            self.assertEqual(launch, [str(runtime / "run-proton.py"), *command])
            self.assertEqual(result["HYBRIS_BC_TEXTURES"], "dxvk")
            self.assertEqual(result["VK_LAYER_PATH"], f"{runtime}/wsi:{runtime}/lib:old")
            self.assertEqual(result["VK_INSTANCE_LAYERS"], "VK_LAYER_window_system_integration:old:VK_LAYER_HYBRIS_compat")
            self.assertEqual(result["VK_LOADER_LAYERS_DISABLE"], "*gamescope*")
            self.assertEqual(result["ENABLE_GAMESCOPE_WSI"], "0")
            self.assertEqual(result["DISABLE_VK_LAYER_VALVE_steam_fossilize_1"], "1")
            for key in ("PROTON_USE_WINED3D", "DISABLE_WSI_LAYER", "ENABLE_VK_LAYER_VALVE_steam_fossilize_1"):
                self.assertNotIn(key, result)
                self.assertIn(key, env)
            for key in ("FEX_TSOENABLED", "WINESERVER", "CUSTOM"):
                self.assertEqual(result[key], env[key])

    def test_xclipse_uses_shared_compat_layer_and_game_bc_with_its_dxvk_payload(self):
        with tempfile.TemporaryDirectory() as tmp:
            runtime, _ = self.runtime(Path(tmp))
            env = {"DROIDDECK_POWERVR_RUNTIME": str(runtime), "STEAM_COMPAT_DATA_PATH": "/compatdata/42",
                   "DROIDDECK_HYBRIS_LAYERS": "VK_LAYER_HYBRIS_compat",
                   "DROIDDECK_HYBRIS_SESSION_LAYERS": "VK_LAYER_HYBRIS_compat",
                   "DROIDDECK_HYBRIS_SESSION_BC_TEXTURES": "0", "HYBRIS_BC_TEXTURES": "0",
                   "DROIDDECK_HYBRIS_DXVK": "dxvk-2.6.2"}
            command = ["/proton", "waitforexitandrun", "game.exe"]
            # The selected payload is required like the default one.
            with self.assertRaises(MODULE["SystemGraphicsError"]):
                MODULE["system_graphics_launch"](command, env)
            for arch in ("x32", "x64"):
                for name in ("d3d8", "d3d9", "d3d10core", "d3d11", "dxgi"):
                    target = runtime / "dxvk-2.6.2" / arch / (name + ".dll")
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(b"component")
            launch, result = MODULE["system_graphics_launch"](command, env)
            self.assertEqual(launch, [str(runtime / "run-proton.py"), *command])
            self.assertEqual(result["VK_INSTANCE_LAYERS"],
                             "VK_LAYER_window_system_integration:VK_LAYER_HYBRIS_compat")
            self.assertEqual(result["VK_LAYER_PATH"], f"{runtime}/wsi:{runtime}/lib")
            self.assertEqual(result["HYBRIS_BC_TEXTURES"], "dxvk")
            self.assertEqual(result["DROIDDECK_HYBRIS_SESSION_BC_TEXTURES"], "0")
            self.assertEqual(env["HYBRIS_BC_TEXTURES"], "0")
            self.assertEqual(result["DROIDDECK_HYBRIS_DXVK"], "dxvk-2.6.2")
            generic = dict(env, DROIDDECK_SYSTEM_VULKAN_RUNTIME=str(runtime),
                           DROIDDECK_SYSTEM_VULKAN_GPU="xclipse",
                           DROIDDECK_SYSTEM_VULKAN_PRESENTATION="native",
                           DROIDDECK_SYSTEM_VULKAN_DXVK="managed",
                           DROIDDECK_POWERVR_RUNTIME="/old-alias-must-not-be-used")
            generic_launch, generic_result = MODULE["system_graphics_launch"](command, generic)
            self.assertEqual(generic_launch, launch)
            for key in ("VK_INSTANCE_LAYERS", "VK_LAYER_PATH", "DROIDDECK_HYBRIS_DXVK", "HYBRIS_BC_TEXTURES"):
                self.assertEqual(generic_result[key], result[key])
            for name in ("../dxvk", "other", "dxvk/x"):
                with self.subTest(name=name), self.assertRaises(MODULE["SystemGraphicsError"]):
                    MODULE["system_graphics_launch"](command, dict(env, DROIDDECK_HYBRIS_DXVK=name))

    def test_managed_native_payload_preserves_selected_environment_and_presentation(self):
        architectures = ("i386-windows", "x86_64-windows", "aarch64-windows")
        for payload in ("dxvk-mali", "dxvk-native"):
            for presentation in ("native", "x11-shm"):
                with self.subTest(payload=payload, presentation=presentation), tempfile.TemporaryDirectory() as tmp:
                    runtime, _ = self.runtime(Path(tmp), payload, architectures)
                    command = ["/selected/proton", "waitforexitandrun", "game with spaces.exe"]
                    env = {"DROIDDECK_SYSTEM_VULKAN_RUNTIME": str(runtime),
                           "DROIDDECK_SYSTEM_VULKAN_GPU": "mali",
                           "DROIDDECK_SYSTEM_VULKAN_DXVK": "managed",
                           "DROIDDECK_SYSTEM_VULKAN_PRESENTATION": presentation,
                           "DROIDDECK_HYBRIS_DXVK": payload, "DXVK_MALI_COMPAT": "1",
                           "STEAM_COMPAT_DATA_PATH": "/compatdata/42", "WSI_X11_FORCE_SHM": "0",
                           "WINESERVER": "/selected/wineserver", "BL_SYNC_PACK": "selected",
                           "WINEDLLOVERRIDES": "custom=n", "CUSTOM": "literal $value"}
                    saved = dict(env)
                    launch, result = MODULE["system_graphics_launch"](command, env)
                    self.assertEqual(launch, [str(runtime / "run-proton.py"), *command])
                    self.assertEqual(result["WSI_X11_FORCE_SHM"], "1" if presentation == "x11-shm" else "0")
                    self.assertEqual(result["HYBRIS_BC_TEXTURES"], "dxvk")
                    for key in ("DROIDDECK_HYBRIS_DXVK", "DXVK_MALI_COMPAT", "WINESERVER",
                                "BL_SYNC_PACK", "WINEDLLOVERRIDES", "CUSTOM"):
                        self.assertEqual(result[key], env[key])
                    self.assertEqual(env, saved)

    def test_managed_native_payload_requires_all_architectures_and_nonempty_dlls(self):
        architectures = ("i386-windows", "x86_64-windows", "aarch64-windows")
        for payload in ("dxvk-mali", "dxvk-native"):
            with self.subTest(payload=payload), tempfile.TemporaryDirectory() as tmp:
                runtime, _ = self.runtime(Path(tmp), payload, architectures)
                # Legacy aliases must not hide an incomplete native payload.
                self.runtime(Path(tmp), payload)
                command = ["/proton", "waitforexitandrun", "game.exe"]
                env = {"DROIDDECK_SYSTEM_VULKAN_RUNTIME": str(runtime),
                       "DROIDDECK_SYSTEM_VULKAN_GPU": "mali", "DROIDDECK_SYSTEM_VULKAN_DXVK": "managed",
                       "DROIDDECK_HYBRIS_DXVK": payload, "STEAM_COMPAT_DATA_PATH": "/compatdata/42"}
                for arch in architectures:
                    folder = runtime / payload / arch
                    hidden = folder.with_name(arch + ".hidden")
                    folder.rename(hidden)
                    with self.subTest(missing_arch=arch), patch.object(os, "execvpe") as execute:
                        with self.assertRaisesRegex(MODULE["SystemGraphicsError"], payload + "/" + arch):
                            MODULE["exec_proton"](command, env)
                        execute.assert_not_called()
                    hidden.rename(folder)
                    for name in ("d3d8", "d3d9", "d3d10core", "d3d11", "dxgi"):
                        dll = folder / (name + ".dll")
                        original = dll.read_bytes()
                        dll.write_bytes(b"")
                        with self.subTest(empty=dll), patch.object(os, "execvpe") as execute:
                            with self.assertRaisesRegex(MODULE["SystemGraphicsError"], payload + "/" + arch):
                                MODULE["exec_proton"](command, env)
                            execute.assert_not_called()
                        dll.write_bytes(original)

    def test_mali_managed_payload_never_falls_back_to_legacy_architectures(self):
        with tempfile.TemporaryDirectory() as tmp:
            runtime, _ = self.runtime(Path(tmp), "dxvk-mali")
            command = ["/proton", "waitforexitandrun", "game.exe"]
            env = {"DROIDDECK_SYSTEM_VULKAN_RUNTIME": str(runtime), "DROIDDECK_SYSTEM_VULKAN_GPU": "mali",
                   "DROIDDECK_SYSTEM_VULKAN_DXVK": "managed", "DROIDDECK_HYBRIS_DXVK": "dxvk-mali",
                   "STEAM_COMPAT_DATA_PATH": "/compatdata/42"}
            with patch.object(os, "execvpe") as execute:
                with self.assertRaisesRegex(MODULE["SystemGraphicsError"], "dxvk-mali/i386-windows"):
                    MODULE["exec_proton"](command, env)
                execute.assert_not_called()

    def test_generic_runtime_supports_mali_without_changing_powervr_presentation(self):
        with tempfile.TemporaryDirectory() as tmp:
            runtime, _ = self.runtime(Path(tmp))
            command = ["/selected/proton", "waitforexitandrun", "game with spaces.exe"]
            for gpu in ("mali", "powervr"):
                with self.subTest(gpu=gpu):
                    env = {"DROIDDECK_SYSTEM_VULKAN_RUNTIME": str(runtime), "DROIDDECK_SYSTEM_VULKAN_GPU": gpu,
                           "DROIDDECK_POWERVR_RUNTIME": "/old-alias-must-not-be-used",
                           "STEAM_COMPAT_DATA_PATH": "/compatdata/42", "WSI_X11_FORCE_SHM": "0",
                           "FEX_TSOENABLED": "1", "WINESERVER": "/selected/wineserver",
                           "CUSTOM": "literal $value", "HYBRIS_VULKAN_HAL": "/vendor/lib64/hw/vulkan.so",
                           "HYBRIS_BC_TEXTURES": "custom", "PROTON_USE_WINED3D": "1"}
                    saved = dict(env)
                    launch, result = MODULE["system_graphics_launch"](command, env)
                    self.assertEqual(launch, command if gpu == "mali" else [str(runtime / "run-proton.py"), *command])
                    self.assertEqual(result["VK_LAYER_PATH"], f"{runtime}/wsi:{runtime}/lib")
                    self.assertEqual(result["VK_INSTANCE_LAYERS"],
                                     "VK_LAYER_window_system_integration:VK_LAYER_HYBRIS_compat")
                    self.assertEqual(result["WSI_X11_FORCE_SHM"], "1" if gpu == "mali" else "0")
                    self.assertEqual(result["HYBRIS_BC_TEXTURES"], "custom" if gpu == "mali" else "dxvk")
                    self.assertEqual(result.get("PROTON_USE_WINED3D"), "1" if gpu == "mali" else None)
                    self.assertEqual(result["ENABLE_GAMESCOPE_WSI"], "0")
                    for key in ("FEX_TSOENABLED", "WINESERVER", "CUSTOM", "HYBRIS_VULKAN_HAL"):
                        self.assertEqual(result[key], env[key])
                    self.assertEqual(env, saved)

    def test_presentation_and_dxvk_capabilities_are_independent_of_gpu_identity(self):
        with tempfile.TemporaryDirectory() as tmp:
            runtime, _ = self.runtime(Path(tmp))
            command = ["/selected/proton", "waitforexitandrun", "game.exe"]
            for gpu in ("mali", "future-vendor"):
                for presentation in ("native", "x11-shm"):
                    for dxvk in ("managed", "user"):
                        with self.subTest(gpu=gpu, presentation=presentation, dxvk=dxvk):
                            env = {"DROIDDECK_SYSTEM_VULKAN_RUNTIME": str(runtime),
                                   "DROIDDECK_SYSTEM_VULKAN_GPU": gpu,
                                   "DROIDDECK_SYSTEM_VULKAN_PRESENTATION": presentation,
                                   "DROIDDECK_SYSTEM_VULKAN_DXVK": dxvk,
                                   "DROIDDECK_HYBRIS_LAYERS": "VK_LAYER_vendor:VK_LAYER_HYBRIS_compat",
                                   "DROIDDECK_HYBRIS_LAYER_PATH": "/vendor layers",
                                   "STEAM_COMPAT_DATA_PATH": "/compatdata/42",
                                   "WSI_X11_FORCE_SHM": "0", "HYBRIS_BC_TEXTURES": "custom",
                                   "PROTON_USE_WINED3D": "1"}
                            saved = dict(env)
                            launch, result = MODULE["system_graphics_launch"](command, env)
                            self.assertEqual(launch, [str(runtime / "run-proton.py"), *command]
                                             if dxvk == "managed" else command)
                            self.assertEqual(result["WSI_X11_FORCE_SHM"],
                                             "1" if presentation == "x11-shm" else "0")
                            self.assertEqual(result["HYBRIS_BC_TEXTURES"], "dxvk" if dxvk == "managed" else "custom")
                            self.assertEqual(result.get("PROTON_USE_WINED3D"), None if dxvk == "managed" else "1")
                            self.assertEqual(result["VK_INSTANCE_LAYERS"],
                                             "VK_LAYER_window_system_integration:VK_LAYER_vendor:VK_LAYER_HYBRIS_compat")
                            self.assertEqual(result["VK_LAYER_PATH"], f"{runtime}/wsi:{runtime}/lib:/vendor layers")
                            self.assertEqual(env, saved)

    def test_software_steam_cef_keeps_native_and_proton_game_graphics(self):
        with tempfile.TemporaryDirectory() as tmp:
            runtime, _ = self.runtime(Path(tmp))
            env = {"DROIDDECK_SYSTEM_VULKAN_RUNTIME": str(runtime),
                   "DROIDDECK_SYSTEM_VULKAN_GPU": "powervr",
                   "DROIDDECK_SYSTEM_VULKAN_PRESENTATION": "native",
                   "DROIDDECK_SYSTEM_VULKAN_DXVK": "managed",
                   "DROIDDECK_HYBRIS_DXVK": "dxvk",
                   "STEAM_COMPAT_DATA_PATH": "/compatdata/42",
                   "VK_LAYER_PATH": f"{runtime}/wsi:{runtime}/lib",
                   "VK_INSTANCE_LAYERS": "VK_LAYER_window_system_integration:VK_LAYER_HYBRIS_compat"}
            for command in (["/selected/proton", "waitforexitandrun", "game.exe"],
                            ["/native/game", "--fullscreen"]):
                with self.subTest(command=command):
                    baseline_command, baseline_env = MODULE["system_graphics_launch"](command, env)
                    software_env = dict(env, BL_STEAM_CEF_SOFTWARE="1")
                    game_command, game_env = MODULE["system_graphics_launch"](command, software_env)
                    self.assertEqual(game_command, baseline_command)
                    self.assertEqual({key: value for key, value in game_env.items()
                                      if key != "BL_STEAM_CEF_SOFTWARE"}, baseline_env)
                    self.assertNotIn("LIBGL_ALWAYS_SOFTWARE", game_env)
                    self.assertNotIn("GALLIUM_DRIVER", game_env)

    def test_invalid_system_capabilities_fail_before_proton_exec(self):
        with tempfile.TemporaryDirectory() as tmp:
            runtime, _ = self.runtime(Path(tmp))
            env = {"DROIDDECK_SYSTEM_VULKAN_RUNTIME": str(runtime), "STEAM_COMPAT_DATA_PATH": "/compatdata/42"}
            command = ["/proton", "waitforexitandrun", "game.exe"]
            for key in ("DROIDDECK_SYSTEM_VULKAN_PRESENTATION", "DROIDDECK_SYSTEM_VULKAN_DXVK"):
                with self.subTest(key=key), patch.object(os, "execvpe") as execute:
                    with self.assertRaises(MODULE["SystemGraphicsError"]):
                        MODULE["exec_proton"](command, dict(env, **{key: "unsupported"}))
                    execute.assert_not_called()

    def test_mali_profile_keeps_legacy_runtime_and_leaves_proton_probes_unchanged(self):
        with tempfile.TemporaryDirectory() as tmp:
            runtime, _ = self.runtime(Path(tmp))
            env = {"DROIDDECK_POWERVR_RUNTIME": str(runtime), "DROIDDECK_SYSTEM_VULKAN_GPU": "mali",
                   "STEAM_COMPAT_DATA_PATH": "/compatdata/42"}
            command = ["/proton", "waitforexitandrun", "game.exe"]
            launch, result = MODULE["system_graphics_launch"](command, env)
            self.assertIs(launch, command)
            self.assertEqual(result["WSI_X11_FORCE_SHM"], "1")
            env["DROIDDECK_SYSTEM_VULKAN_RUNTIME"] = "/not-installed"
            for args, probe in ((["/proton", "run", "vulkantest.exe"], env),
                                (["/proton", "getcompatpath", "x"], env),
                                (command, dict(env, STEAM_COMPAT_DATA_PATH="/compatdata/0"))):
                with self.subTest(args=args, prefix=probe["STEAM_COMPAT_DATA_PATH"]):
                    actual, inherited = MODULE["system_graphics_launch"](args, probe)
                    self.assertIs(actual, args)
                    self.assertIs(inherited, probe)

    def test_mali_needs_only_presentation_files_and_keeps_selected_game_components(self):
        with tempfile.TemporaryDirectory() as tmp:
            runtime, files = self.runtime(Path(tmp))
            required = [name for name in files if name.startswith(("wsi/", "lib/"))]
            for name in files:
                if name not in required:
                    (runtime / name).unlink()
            env = {"DROIDDECK_SYSTEM_VULKAN_RUNTIME": str(runtime), "DROIDDECK_SYSTEM_VULKAN_GPU": "mali",
                   "STEAM_COMPAT_DATA_PATH": "/compatdata/42", "WINEDLLOVERRIDES": "dxgi=n;custom=n",
                   "WINESERVER": "/selected/wineserver", "PROTON_USE_WINED3D": "1"}
            command = ["/custom/proton", "waitforexitandrun", "game.exe"]
            actual, result = MODULE["system_graphics_launch"](command, env)
            self.assertIs(actual, command)
            for key in ("WINEDLLOVERRIDES", "WINESERVER", "PROTON_USE_WINED3D"):
                self.assertEqual(result[key], env[key])
            self.assertNotIn("HYBRIS_BC_TEXTURES", result)
            self.assertEqual(result["WSI_X11_FORCE_SHM"], "1")
            for name in required:
                with self.subTest(missing=name):
                    target = runtime / name
                    original = target.read_bytes()
                    target.unlink()
                    with patch.object(os, "execvpe") as execute:
                        with self.assertRaises(MODULE["SystemGraphicsError"]):
                            MODULE["exec_proton"](command, env)
                        execute.assert_not_called()
                    target.write_bytes(original)

    def test_incomplete_runtime_fails_before_any_proton_exec(self):
        with tempfile.TemporaryDirectory() as tmp:
            runtime, files = self.runtime(Path(tmp))
            env = {"DROIDDECK_SYSTEM_VULKAN_RUNTIME": str(runtime), "DROIDDECK_SYSTEM_VULKAN_GPU": "powervr",
                   "STEAM_COMPAT_DATA_PATH": "/compatdata/42"}
            command = ["/proton", "waitforexitandrun", "game.exe"]
            for name in files:
                target = runtime / name
                original = target.read_bytes()
                for missing in (False, True):
                    with self.subTest(name=name, missing=missing):
                        target.unlink() if missing else target.write_bytes(b"")
                        with patch.object(os, "execvpe") as execute:
                            with self.assertRaises(MODULE["SystemGraphicsError"]):
                                MODULE["exec_proton"](command, env)
                            execute.assert_not_called()
                        target.write_bytes(original)
                        if name == "run-proton.py":
                            target.chmod(0o755)
            (runtime / "run-proton.py").chmod(0o644)
            with self.assertRaises(MODULE["SystemGraphicsError"]):
                MODULE["system_graphics_launch"](command, env)
            with self.assertRaises(MODULE["SystemGraphicsError"]):
                MODULE["system_graphics_launch"](command, dict(env, DROIDDECK_SYSTEM_VULKAN_RUNTIME="relative"))

    def test_wrapper_receives_the_sync_selected_proton(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            runtime, _ = self.runtime(root)
            env = {"HOME": tmp, "DROIDDECK_POWERVR_RUNTIME": str(runtime),
                   "STEAM_COMPAT_DATA_PATH": "/compatdata/42"}
            command = ["/stock/proton", "waitforexitandrun", "game.exe"]
            selected = ["/sync-pack/proton", *command[1:]]
            selected_env = dict(env, WINESERVER="/sync-pack/wineserver", BL_SYNC_PACK="selected")
            main = MODULE["main"]
            with patch.dict(os.environ, env, clear=True), patch.object(sys, "argv", ["game-env", *command]), \
                    patch.dict(main.__globals__, {"load_sync": lambda: {},
                                                  "select_sync": lambda *args: (selected, selected_env),
                                                  "win_components": lambda *args: ""}), \
                    patch.object(os, "execvpe", side_effect=SystemExit(23)) as execute:
                with self.assertRaises(SystemExit) as stopped:
                    main()
            self.assertEqual(stopped.exception.code, 23)
            execute.assert_called_once()
            path, args, actual_env = execute.call_args.args
            self.assertEqual(path, str(runtime / "run-proton.py"))
            self.assertEqual(args, [path, *selected])
            self.assertEqual(actual_env["WINESERVER"], "/sync-pack/wineserver")
            self.assertEqual(actual_env["BL_SYNC_PACK"], "selected")


class GameDebugLoggingTest(unittest.TestCase):
    def test_real_game_launch_without_config_file_keeps_the_base_environment(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            probe = home / "proton"
            probe.write_text("#!" + sys.executable + "\nimport os\nprint(os.environ['PROTON_LOG'])\n")
            probe.chmod(0o755)
            result = subprocess.run(
                [sys.executable, str(BIN / "droiddeck-game-env"), str(probe), "waitforexitandrun"],
                env={**os.environ, "HOME": tmp, "STEAM_COMPAT_DATA_PATH": str(home / "compatdata/42"),
                     "BL_DEBUG_DIR": tmp, "PROTON_LOG": "0"},
                text=True, capture_output=True, check=True,
            )
            self.assertEqual(result.stdout.strip(), "0")

    def test_game_debug_logs_override_only_diagnostic_values_and_keep_raw_settings(self):
        with tempfile.TemporaryDirectory(prefix="game logs '") as debug_dir:
            inherited = {"BL_DEBUG_DIR": debug_dir, "PROTON_LOG": "0", "WINEDEBUG": "-all,+relay",
                         "PROTON_LOG_DIR": "/old", "PROTON_CRASH_REPORT_DIR": "/old-crashes",
                         "DXVK_LOG_PATH": "/old-dxvk", "DXVK_LOG_LEVEL": "none", "VKD3D_DEBUG": "none",
                         "CUSTOM": "$(touch never); 'quoted' = literal", "DXVK_CONFIG": "dxvk.tearFree = True"}
            config = {"version": 1, "gameDebugLogs": True,
                      "shared": {"PROTON_LOG": "0", "BL_DEBUG_DIR": "/profile-redirect"},
                      "games": {"42": {"PROTON_LOG_DIR": None, "DXVK_LOG_PATH": "/game-redirect"}}}
            normal = MODULE["apply_config"](inherited, config, "42")
            debug = MODULE["apply_game_debug_logs"](normal, config, inherited["BL_DEBUG_DIR"])
            self.assertEqual(debug["PROTON_LOG"], "1")
            for key in ("PROTON_LOG_DIR", "PROTON_CRASH_REPORT_DIR", "DXVK_LOG_PATH"):
                self.assertEqual(debug[key], debug_dir)
            self.assertEqual(debug["DXVK_LOG_LEVEL"], "debug")
            self.assertEqual(debug["VKD3D_DEBUG"], "info")
            self.assertIn("+relay", debug["WINEDEBUG"].split(","))
            self.assertTrue(debug["WINEDEBUG"].endswith("+loaddll,+mscoree"))
            for key in ("CUSTOM", "DXVK_CONFIG", "BL_DEBUG_DIR"):
                self.assertEqual(debug[key], normal[key])
            self.assertEqual(normal["PROTON_LOG"], "0")
            self.assertEqual(inherited["PROTON_LOG_DIR"], "/old")
            self.assertIs(MODULE["apply_game_debug_logs"](normal, {**config, "gameDebugLogs": False}, debug_dir), normal)
            self.assertEqual(MODULE["apply_game_debug_logs"](debug, config, debug_dir), debug)

    def test_wine_debug_channels_are_formatted_deduplicated_and_diagnostics_win(self):
        formatted = MODULE["game_debug_channels"](
            " +relay, trace-vulkan, -all, -seh, +relay, trace+vulkan, garbage, $(touch never) ",
            "+seh,warn-foo,warn+foo,+relay",
        )
        self.assertEqual(formatted, "-all,trace+vulkan,warn+foo,+relay," +
                         "+timestamp,+pid,+tid,+seh,+unwind,+threadname,+debugstr,+loaddll,+mscoree")
        self.assertEqual(MODULE["game_debug_channels"](formatted, "1"), formatted)
        self.assertNotIn("-seh", formatted)
        for value in ("x\0,+relay", "+" + "x" * 8192, 1, None):
            self.assertEqual(MODULE["game_debug_channels"](value), MODULE["game_debug_channels"]())
        oversized = ",".join("+channel_" + str(i) for i in range(550))
        other = ",".join("+other_" + str(i) for i in range(550))
        self.assertLessEqual(len(MODULE["game_debug_channels"](oversized, other)), 8192)
        self.assertTrue(MODULE["game_debug_channels"](oversized, other).endswith("+loaddll,+mscoree"))

    def test_game_debug_logs_require_opt_in_and_a_real_session_log_directory(self):
        env = {"PROTON_LOG": "user", "CUSTOM": "keep"}
        for config in ({}, {"version": 1}, {"version": 1, "gameDebugLogs": False},
                       {"version": 1, "gameDebugLogs": "true"}, {"version": 2, "gameDebugLogs": True}):
            self.assertIs(MODULE["apply_game_debug_logs"](env, config, "/"), env)
        with tempfile.TemporaryDirectory() as tmp:
            not_a_directory = Path(tmp) / "file"
            not_a_directory.write_text("file")
            for path in (None, "", "relative", "bad\0path", str(not_a_directory), str(Path(tmp) / "missing")):
                with self.subTest(path=path):
                    self.assertIs(MODULE["apply_game_debug_logs"](env, {"version": 1, "gameDebugLogs": True}, path), env)

    def test_game_debug_toggle_is_live_preserves_command_and_excludes_probes(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            config_path = home / ".config/droiddeck/game-environment.json"
            config_path.parent.mkdir(parents=True)
            debug_dir = home / "session logs '$literal; directory'"
            debug_dir.mkdir()
            probe = home / "fake proton"
            probe.write_text("#!" + sys.executable + "\nimport json, os, sys\n"
                             "print(json.dumps({'env': dict(os.environ), 'argv': sys.argv[1:]}))\n")
            probe.chmod(0o755)
            literal = "$(touch " + str(home / "never-created") + "); 'literal'=value"
            config = {"version": 1, "gameDebugLogs": True,
                      "shared": {"PROTON_LOG": "0", "WINEDEBUG": "-all,+relay", "CUSTOM": literal,
                                 "BL_DEBUG_DIR": "/profile-must-not-redirect"}}
            env = {**os.environ, "HOME": tmp, "STEAM_COMPAT_DATA_PATH": str(home / "compatdata/42"),
                   "BL_DEBUG_DIR": str(debug_dir), "PROTON_LOG": "0", "WINEDEBUG": "-all"}
            command = [str(probe), "waitforexitandrun", "game 'with quotes'.exe", "argument with spaces", "$(literal)", "%command%"]

            def launch(arguments, environment):
                result = subprocess.run([sys.executable, str(BIN / "droiddeck-game-env"), *arguments],
                                        env=environment, text=True, capture_output=True, check=True)
                return json.loads(result.stdout)

            for enabled in (True, False, True):
                config_path.write_text(json.dumps({**config, "gameDebugLogs": enabled}))
                result = launch(command, env)
                self.assertEqual(result["argv"], command[1:])
                self.assertEqual(result["env"]["CUSTOM"], literal)
                self.assertEqual(result["env"]["PROTON_LOG"], "1" if enabled else "0")
                if enabled:
                    self.assertEqual(result["env"]["PROTON_LOG_DIR"], str(debug_dir))
                    self.assertIn("+relay", result["env"]["WINEDEBUG"].split(","))
                    self.assertIn("+seh", result["env"]["WINEDEBUG"].split(","))
                else:
                    self.assertEqual(result["env"]["WINEDEBUG"], "-all,+relay")
            for verb, prefix in (("run", "42"), ("waitforexitandrun", "0"), ("waitforexitandrun", "not-a-game")):
                result = launch([str(probe), verb], {**env, "STEAM_COMPAT_DATA_PATH": str(home / "compatdata" / prefix)})
                self.assertEqual(result["env"]["PROTON_LOG"], "0")
                self.assertEqual(result["env"]["WINEDEBUG"], "-all")
            # Non-Steam shortcuts use the same normalized unsigned compatdata identity.
            result = launch(command, {**env, "STEAM_COMPAT_DATA_PATH": str(home / "compatdata/-1")})
            self.assertEqual(result["env"]["PROTON_LOG"], "1")
            self.assertFalse((home / "never-created").exists())
            self.assertEqual(json.loads(config_path.read_text())["shared"], config["shared"])


class PlacedMemoryLaunchTest(unittest.TestCase):
    runtime = SystemGraphicsLaunchTest.runtime
    setting = "DROIDDECK_EXPERIMENTAL_PLACED_MEMORY"
    layer = "VK_LAYER_DROIDDECK_placed_memory"
    library = "libVkLayer_DROIDDECK_placed_memory.so"
    manifest = "VkLayer_DROIDDECK_placed_memory.json"

    def executable(self, root, machine=0x14c, magic=0x10b, name="game.exe"):
        # One PE section and the full standard PE32 optional-header footprint.
        data = bytearray(513)
        data[:2] = b"MZ"
        struct.pack_into("<I", data, 60, 128)
        data[128:132] = b"PE\0\0"
        struct.pack_into("<HHIIIHH", data, 132, machine, 1, 0, 0, 0, 224, 0x102)
        struct.pack_into("<H", data, 152, magic)
        struct.pack_into("<I", data, 152 + 92, 16)
        data[376:381] = b".text"
        path = root / name
        path.write_bytes(data)
        return path

    def components(self, runtime):
        (runtime / "lib" / self.library).write_bytes(b"native component")
        (runtime / "lib" / self.manifest).write_text(json.dumps({
            "file_format_version": "1.2.0", "layer": {
                "name": self.layer, "type": "GLOBAL", "library_path": "./" + self.library,
            },
        }))

    def environment(self, runtime, gpu="powervr", **extra):
        return dict({"DROIDDECK_SYSTEM_VULKAN_RUNTIME": str(runtime),
                     "DROIDDECK_SYSTEM_VULKAN_GPU": gpu,
                     "STEAM_COMPAT_DATA_PATH": "/compatdata/42"}, **extra)

    def mali_profile(self, root):
        runtime, _ = self.runtime(root, "dxvk-mali", ("i386-windows", "x86_64-windows", "aarch64-windows"))
        return runtime, self.environment(runtime, "mali", DROIDDECK_SYSTEM_VULKAN_DXVK="managed",
                                         DROIDDECK_HYBRIS_DXVK="dxvk-mali", DXVK_MALI_COMPAT="1")

    def launch(self, exe, env, proton="/selected/proton", verb="waitforexitandrun"):
        return MODULE["system_graphics_launch"]([proton, verb, str(exe)], env)

    def test_powervr_i386_activates_below_compat_without_mutating_session(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            runtime, _ = self.runtime(root)
            self.components(runtime)
            exe = self.executable(root, name="game with spaces.dat")
            env = self.environment(runtime, DROIDDECK_HYBRIS_SESSION_LAYERS="VK_LAYER_HYBRIS_compat",
                                   VK_INSTANCE_LAYERS="VK_LAYER_HYBRIS_compat",
                                   VK_ICD_FILENAMES=str(runtime / "icd.json"))
            saved = dict(env)
            command, actual = self.launch(exe, env)
            self.assertEqual(command, [str(runtime / "run-proton.py"), "/selected/proton",
                                       "waitforexitandrun", str(exe)])
            self.assertEqual(actual[self.setting], "1")
            self.assertEqual(actual["VK_INSTANCE_LAYERS"],
                             "VK_LAYER_window_system_integration:VK_LAYER_HYBRIS_compat:" + self.layer)
            self.assertEqual(actual["DROIDDECK_HYBRIS_SESSION_LAYERS"], "VK_LAYER_HYBRIS_compat")
            self.assertEqual(env, saved)

    def test_opt_out_and_unknown_settings_leave_old_runtime_usable(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            runtime, _ = self.runtime(root)
            exe = self.executable(root)
            for setting in ("0", "", "invalid"):
                with self.subTest(setting=setting):
                    env = self.environment(runtime, **{self.setting: setting})
                    _, actual = self.launch(exe, env)
                    self.assertNotIn(self.layer, actual["VK_INSTANCE_LAYERS"])
                    self.assertEqual(actual[self.setting], setting)

    def test_legacy_mali_and_other_system_profiles_require_explicit_opt_in(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            runtime, _ = self.runtime(root)
            self.components(runtime)
            exe = self.executable(root)
            for gpu in ("mali", "xclipse", "future-vendor", ""):
                with self.subTest(gpu=gpu):
                    env = self.environment(runtime, gpu)
                    _, default = self.launch(exe, env)
                    self.assertNotIn(self.layer, default["VK_INSTANCE_LAYERS"])
                    _, requested = self.launch(exe, dict(env, **{self.setting: "1"}))
                    self.assertTrue(requested["VK_INSTANCE_LAYERS"].endswith(":" + self.layer))

    def test_managed_mali_i386_activates_without_a_game_override(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            runtime, env = self.mali_profile(root)
            self.components(runtime)
            exe = self.executable(root, name="hl2.exe")
            env.update(VK_ICD_FILENAMES=str(runtime / "icd.json"),
                       VK_INSTANCE_LAYERS="VK_LAYER_HYBRIS_compat")
            saved = dict(env)
            command, actual = self.launch(exe, env)
            self.assertEqual(command, [str(runtime / "run-proton.py"), "/selected/proton",
                                       "waitforexitandrun", str(exe)])
            self.assertEqual(actual[self.setting], "1")
            self.assertEqual(actual["VK_INSTANCE_LAYERS"],
                             "VK_LAYER_window_system_integration:VK_LAYER_HYBRIS_compat:" + self.layer)
            self.assertEqual(env, saved)

    def test_xbox_game_uses_selected_sync_pack_and_managed_gpu_profile(self):
        with tempfile.TemporaryDirectory() as tmp:
            for gpu in ("powervr", "mali"):
                with self.subTest(gpu=gpu):
                    root = Path(tmp) / gpu
                    root.mkdir()
                    if gpu == "mali":
                        runtime, env = self.mali_profile(root)
                    else:
                        runtime, _ = self.runtime(root)
                        env = self.environment(runtime)
                    self.components(runtime)
                    prepared = self.executable(root, name="prepared.exe")
                    env.update(HOME=str(root), VK_ICD_FILENAMES=str(runtime / "icd.json"))
                    command = ["/stock/proton", "waitforexitandrun", "/encrypted/Game.exe", "--custom"]
                    selected_proton = root / "sync-pack/proton"
                    selected_proton.parent.mkdir()
                    selected_proton.write_text("#!/usr/bin/python3\n")
                    selected_proton.chmod(0o755)
                    selected = [str(selected_proton), *command[1:]]
                    selected_env = dict(env, WINESERVER="/sync-pack/wineserver", BL_SYNC_PACK="selected")
                    detected = []

                    def xbox_launch(actual, actual_env, runner):
                        detected.append((actual, actual_env))
                        return runner([actual[0], actual[1], str(prepared), *actual[3:]], actual_env)

                    main = MODULE["main"]
                    with patch.dict(os.environ, env, clear=True), patch.object(sys, "argv", ["game-env", *command]), \
                            patch.dict(main.__globals__, {"load_sync": lambda: {},
                                                         "select_sync": lambda *args: (selected, selected_env),
                                                         "win_components": lambda *args: ""}), \
                            patch("runpy.run_path", return_value={"launch": xbox_launch}), \
                            patch.object(subprocess, "call", return_value=7) as call, \
                            patch.object(os, "execvpe") as execute:
                        self.assertEqual(main(), 7)

                    self.assertEqual(detected[0][0], selected)
                    self.assertEqual(detected[0][1]["BL_SYNC_PACK"], "selected")
                    execute.assert_not_called()
                    launch = call.call_args.args[0]
                    actual_env = call.call_args.kwargs["env"]
                    self.assertEqual(launch, [str(runtime / "run-proton.py"), str(selected_proton),
                                              "waitforexitandrun", str(prepared), "--custom"])
                    self.assertEqual(actual_env["WINESERVER"], "/sync-pack/wineserver")
                    self.assertEqual(actual_env[self.setting], "1")
                    self.assertTrue(actual_env["VK_INSTANCE_LAYERS"].endswith(":" + self.layer))

    def test_xbox_unlaunchable_selected_sync_uses_stock_inside_the_lease(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            runtime, env = self.mali_profile(root)
            self.components(runtime)
            prepared = self.executable(root, name="prepared.exe")
            env.update(HOME=tmp, VK_ICD_FILENAMES=str(runtime / "icd.json"))
            command = ["/stock/proton", "waitforexitandrun", "/encrypted/Game.exe"]
            selected = [str(root / "missing-sync-pack/proton"), *command[1:]]
            selected_env = dict(env, WINESERVER="/sync-pack/wineserver", BL_SYNC_PACK="selected")
            released = unittest.mock.Mock()

            def xbox_launch(actual, actual_env, runner):
                owned_env = dict(actual_env, XODUS_BRIDGE_KEY="lease-key")
                try:
                    return runner([actual[0], actual[1], str(prepared)], owned_env)
                finally:
                    released()

            main = MODULE["main"]
            record = unittest.mock.Mock()
            with patch.dict(os.environ, env, clear=True), patch.object(sys, "argv", ["game-env", *command]), \
                    patch.dict(main.__globals__, {"load_sync": lambda: {},
                                                 "select_sync": lambda *args: (selected, selected_env),
                                                 "record_stock": record, "win_components": lambda *args: ""}), \
                    patch("runpy.run_path", return_value={"launch": xbox_launch}), \
                    patch.object(subprocess, "call", return_value=7) as call:
                self.assertEqual(main(), 7)

            released.assert_called_once()
            call.assert_called_once()
            stock_call = call.call_args
            self.assertEqual(stock_call.args[0], [str(runtime / "run-proton.py"),
                                                   "/stock/proton", "waitforexitandrun", str(prepared)])
            self.assertNotIn("WINESERVER", stock_call.kwargs["env"])
            self.assertNotIn("BL_SYNC_PACK", stock_call.kwargs["env"])
            self.assertEqual(stock_call.kwargs["env"]["XODUS_BRIDGE_KEY"], "lease-key")
            self.assertTrue(stock_call.kwargs["env"]["VK_INSTANCE_LAYERS"].endswith(":" + self.layer))
            record.assert_called_once()
            self.assertEqual(record.call_args.args[1],
                             ["/stock/proton", "waitforexitandrun", str(prepared)])

    def test_mali_default_requires_the_managed_experimental_profile(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            runtime, env = self.mali_profile(root)
            self.runtime(root)
            exe = self.executable(root)
            variants = {"DROIDDECK_SYSTEM_VULKAN_GPU": (None, "", "xclipse", "future-vendor"),
                        "DROIDDECK_SYSTEM_VULKAN_DXVK": (None, "", "user"),
                        "DROIDDECK_HYBRIS_DXVK": (None, "", "dxvk"),
                        "DXVK_MALI_COMPAT": (None, "", "0")}
            for key, values in variants.items():
                for value in values:
                    with self.subTest(key=key, value=value):
                        selected = dict(env)
                        if value is None:
                            selected.pop(key)
                        else:
                            selected[key] = value
                        _, actual = self.launch(exe, selected)
                        self.assertNotIn(self.layer, actual["VK_INSTANCE_LAYERS"])
            for setting in ("0", "", "invalid"):
                with self.subTest(setting=setting):
                    _, actual = self.launch(exe, dict(env, **{self.setting: setting}))
                    self.assertNotIn(self.layer, actual["VK_INSTANCE_LAYERS"])
                    self.assertEqual(actual[self.setting], setting)

    def test_managed_mali_default_keeps_game_architecture_and_driver_guards(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            runtime, env = self.mali_profile(root)
            exe = self.executable(root)
            for selection in ({"VK_ICD_FILENAMES": "/manual/icd.json"},
                              {"VK_DRIVER_FILES": "/manual/icd.json",
                               "VK_ICD_FILENAMES": str(runtime / "icd.json")},
                              {"VK_ADD_DRIVER_FILES": "/manual/icd.json"},
                              {"VK_ICD_FILENAMES": str(runtime / "icd.json") + ":/manual/icd.json"},
                              {"STEAM_COMPAT_DATA_PATH": "/compatdata/0"}):
                with self.subTest(selection=selection):
                    _, actual = self.launch(exe, dict(env, **selection))
                    self.assertNotIn(self.layer, actual.get("VK_INSTANCE_LAYERS", ""))
            for machine in (0x8664, 0xaa64, 0xa641, 0xa64e):
                with self.subTest(machine=hex(machine)):
                    _, actual = self.launch(self.executable(root, machine, 0x20b), env)
                    self.assertNotIn(self.layer, actual["VK_INSTANCE_LAYERS"])
            for name in ("steam.exe", "steamwebhelper.exe", "iscriptevaluator.exe"):
                with self.subTest(name=name):
                    _, actual = self.launch(self.executable(root, name=name), env)
                    self.assertNotIn(self.layer, actual["VK_INSTANCE_LAYERS"])
            native = root / "native-game"
            native.write_bytes(b"\x7fELF" + b"\0" * 600)
            _, actual = self.launch(native, env)
            self.assertNotIn(self.layer, actual["VK_INSTANCE_LAYERS"])
            command = [str(native), "--fullscreen"]
            self.assertEqual(MODULE["system_graphics_launch"](command, env), (command, env))
            for proton, verb in (("/selected/not-proton", "waitforexitandrun"),
                                 ("/selected/proton", "run"), ("/selected/proton", "getcompatpath")):
                with self.subTest(proton=proton, verb=verb):
                    _, actual = self.launch(self.executable(root), env, proton, verb)
                    self.assertNotIn(self.layer, actual.get("VK_INSTANCE_LAYERS", ""))

    def test_managed_mali_default_requires_the_bundled_placed_memory_components(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            runtime, env = self.mali_profile(root)
            exe = self.executable(root)
            for name in (self.library, self.manifest):
                self.components(runtime)
                (runtime / "lib" / name).unlink()
                with self.subTest(missing=name), self.assertRaisesRegex(MODULE["SystemGraphicsError"], name):
                    self.launch(exe, env)
                self.launch(exe, dict(env, **{self.setting: "0"}))

    def test_x64_arm64_and_arm64ec_never_activate_even_when_requested(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            runtime, _ = self.runtime(root)
            for machine in (0x8664, 0xaa64, 0xa641, 0xa64e):
                with self.subTest(machine=hex(machine)):
                    exe = self.executable(root, machine, 0x20b)
                    for extra in ({}, {self.setting: "1"}):
                        _, actual = self.launch(exe, self.environment(runtime, **extra))
                        self.assertNotIn(self.layer, actual["VK_INSTANCE_LAYERS"])

    def test_non_game_native_and_other_entry_points_do_not_activate(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            runtime, _ = self.runtime(root)
            exe = self.executable(root)
            env = self.environment(runtime, **{self.setting: "1"})
            for proton, verb in (("/selected/not-proton", "waitforexitandrun"),
                                 ("/selected/proton", "run"), ("/selected/proton", "getcompatpath")):
                with self.subTest(proton=proton, verb=verb):
                    _, actual = self.launch(exe, env, proton, verb)
                    self.assertNotIn(self.layer, actual.get("VK_INSTANCE_LAYERS", ""))
            native = [str(exe), "--fullscreen"]
            self.assertEqual(MODULE["system_graphics_launch"](native, env), (native, env))
            _, actual = self.launch(exe, dict(env, STEAM_COMPAT_DATA_PATH="/compatdata/0"))
            self.assertNotIn(self.layer, actual.get("VK_INSTANCE_LAYERS", ""))
            for name in ("steam.exe", "steamwebhelper.exe", "iscriptevaluator.exe"):
                _, actual = self.launch(self.executable(root, name=name), env)
                self.assertNotIn(self.layer, actual["VK_INSTANCE_LAYERS"])

    def test_manual_icds_and_non_system_launches_remain_unmodified(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            runtime, _ = self.runtime(root)
            exe = self.executable(root)
            for selection in ({"VK_ICD_FILENAMES": "/manual/icd.json"},
                              {"VK_DRIVER_FILES": "/manual/icd.json",
                               "VK_ICD_FILENAMES": str(runtime / "icd.json")},
                              {"VK_ADD_DRIVER_FILES": "/manual/icd.json"},
                              {"VK_ICD_FILENAMES": str(runtime / "icd.json") + ":/manual/icd.json"}):
                with self.subTest(selection=selection):
                    env = self.environment(runtime, **selection, **{self.setting: "1"})
                    _, actual = self.launch(exe, env)
                    self.assertNotIn(self.layer, actual["VK_INSTANCE_LAYERS"])
                    for name, value in selection.items():
                        self.assertEqual(actual[name], value)
            env = {"STEAM_COMPAT_DATA_PATH": "/compatdata/42", self.setting: "1",
                   "VK_ICD_FILENAMES": "/adreno/icd.json"}
            command = ["/selected/proton", "waitforexitandrun", str(exe)]
            self.assertEqual(MODULE["system_graphics_launch"](command, env), (command, env))

    def test_layer_overrides_keep_external_layers_and_search_paths(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            runtime, _ = self.runtime(root)
            self.components(runtime)
            exe = self.executable(root)
            env = self.environment(runtime,
                                   VK_INSTANCE_LAYERS="VK_LAYER_before:VK_LAYER_HYBRIS_compat:VK_LAYER_after:" + self.layer,
                                   DROIDDECK_HYBRIS_LAYERS="VK_LAYER_HYBRIS_compat:VK_LAYER_extra",
                                   VK_LAYER_PATH=f"/custom:{runtime}/lib:/second",
                                   DROIDDECK_HYBRIS_LAYER_PATH="/vendor:/custom")
            _, actual = self.launch(exe, env)
            self.assertEqual(actual["VK_INSTANCE_LAYERS"],
                             "VK_LAYER_window_system_integration:VK_LAYER_before:VK_LAYER_HYBRIS_compat:"
                             "VK_LAYER_after:VK_LAYER_extra:" + self.layer)
            self.assertEqual(actual["VK_LAYER_PATH"], f"{runtime}/wsi:{runtime}/lib:/vendor:/custom:/second")
            self.assertEqual(actual["VK_INSTANCE_LAYERS"].split(":").count(self.layer), 1)

    def test_missing_or_invalid_new_components_fail_only_when_activation_is_requested(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            runtime, _ = self.runtime(root)
            exe = self.executable(root)
            env = self.environment(runtime)
            for name in (self.library, self.manifest):
                self.components(runtime)
                (runtime / "lib" / name).unlink()
                with self.subTest(missing=name), self.assertRaisesRegex(MODULE["SystemGraphicsError"], name):
                    self.launch(exe, env)
                self.launch(exe, dict(env, **{self.setting: "0"}))
                self.launch(self.executable(root, 0x8664, 0x20b, "x64.exe"), env)
            self.components(runtime)
            path = runtime / "lib" / self.manifest
            valid = json.loads(path.read_text())
            variants = [b"not json", b"[]", b" " * 65537]
            for field, value in (("name", "VK_LAYER_other"), ("type", "DEVICE"),
                                 ("library_path", "../other.so"), ("library_path", "/outside/other.so")):
                changed = {**valid, "layer": {**valid["layer"], field: value}}
                variants.append(json.dumps(changed).encode())
            for data in variants:
                with self.subTest(manifest=data[:80]):
                    path.write_bytes(data)
                    with self.assertRaises(MODULE["SystemGraphicsError"]):
                        self.launch(exe, env)

    def test_game_layer_override_can_replace_the_inherited_session_stack(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            runtime, _ = self.runtime(root)
            self.components(runtime)
            exe = self.executable(root)
            for configured in ("", "VK_LAYER_custom"):
                with self.subTest(configured=configured):
                    env = self.environment(runtime, DROIDDECK_HYBRIS_LAYERS=configured,
                                           DROIDDECK_HYBRIS_SESSION_LAYERS="VK_LAYER_HYBRIS_compat",
                                           VK_INSTANCE_LAYERS="VK_LAYER_HYBRIS_compat")
                    _, actual = self.launch(exe, env)
                    self.assertEqual(actual["VK_INSTANCE_LAYERS"], ":".join(filter(None, (
                        "VK_LAYER_window_system_integration", configured, self.layer))))

    def test_pe_classification_rejects_malformed_headers_and_filename_guesses(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            exe = self.executable(root)
            valid = exe.read_bytes()
            cases = [b"", b"MZ", b"\x7fELF" + b"\0" * 600, valid[:64], valid[:160],
                     b"MZ" + b"This program cannot be run in DOS mode." + b"\0" * 512]
            for offset, form, value in ((60, "I", 0xffffffff), (60, "I", 63),
                                        (134, "H", 97), (148, "H", 4097), (148, "H", 95),
                                        (150, "H", 0x2102), (150, "H", 0x100),
                                        (152, "H", 0x20b), (244, "I", 17)):
                data = bytearray(valid)
                struct.pack_into("<" + form, data, offset, value)
                cases.append(bytes(data))
            runtime, _ = self.runtime(root)
            for index, data in enumerate(cases):
                with self.subTest(index=index):
                    exe.write_bytes(data)
                    self.assertFalse(MODULE["is_i386_pe_executable"](str(exe)))
                    _, actual = self.launch(exe, self.environment(runtime))
                    self.assertNotIn(self.layer, actual["VK_INSTANCE_LAYERS"])
            self.assertFalse(MODULE["is_i386_pe_executable"](str(root / "missing.exe")))
            self.assertFalse(MODULE["is_i386_pe_executable"](str(root)))
            fifo = root / "pipe.exe"
            os.mkfifo(fifo)
            self.assertFalse(MODULE["is_i386_pe_executable"](str(fifo)))


class ReadConfigMappingTest(unittest.TestCase):
    def test_missing_parent_or_unbalanced_vdf_is_not_treated_as_absent_choices(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = Path(tmp) / "config.vdf"
            config.write_text('"InstallConfigStore" { "Software" { "Other" {} } }')
            self.assertIsNone(COMPAT["read_config_mapping"](str(config)))
            config.write_text('"InstallConfigStore" { "Software" { "Valve" { "Steam" {} }')
            self.assertIsNone(COMPAT["read_config_mapping"](str(config)))

    def test_valid_steam_parent_without_compat_mapping_has_no_per_app_choices(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = Path(tmp) / "config.vdf"
            config.write_text('"InstallConfigStore" { "Software" { "Valve" { "Steam" {} } } }')
            self.assertEqual(COMPAT["read_config_mapping"](str(config)), {})


class GameEnvironmentTest(unittest.TestCase):
    def test_optional_xbox_loader_failure_keeps_stock_launch(self):
        from unittest.mock import patch
        for error in (FileNotFoundError('missing'), RuntimeError('module initialization failed')):
            calls = []
            with patch.dict(os.environ, {}, clear=True), patch('sys.argv', ['droiddeck-game-env', '/stock', 'run']), \
                 patch('runpy.run_path', side_effect=error), patch('os.execvpe', side_effect=lambda *args: calls.append(args)), \
                 patch.dict(MODULE['main'].__globals__, load_sync=lambda: None, select_sync=lambda command, env, sync: (command, env)):
                MODULE['main']()
            self.assertEqual(calls[0][0:2], ('/stock', ['/stock', 'run']))


    def test_directaudio_capture_follows_the_session_microphone_setting(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            probe = home / "probe"
            probe.write_text("#!" + sys.executable + "\nimport os\nprint(os.environ.get('BANNER_AUDIO_DIRECT_MIC', 'unset'))\n")
            probe.chmod(0o755)
            config = home / ".config/droiddeck/game-environment.json"
            config.parent.mkdir(parents=True)
            for enabled, override, expected in (("1", None, "1"), ("0", "1", "0"), ("1", "0", "0")):
                with self.subTest(enabled=enabled, override=override):
                    profile = {} if override is None else {
                        "BANNER_AUDIO_DIRECT_MIC": override, "BL_MIC_ENABLED": override,
                    }
                    config.write_text(json.dumps({"version": 1, "games": {"42": profile}}))
                    env = dict(os.environ, HOME=tmp, BL_MIC_ENABLED=enabled,
                               BANNER_AUDIO_DIRECT_MIC="1", STEAM_COMPAT_DATA_PATH="/compatdata/42")
                    result = subprocess.run([sys.executable, str(BIN / "droiddeck-game-env"),
                                             str(probe), "waitforexitandrun"],
                                            env=env, text=True, capture_output=True, check=True)
                    self.assertEqual(result.stdout.strip(), expected)

    def test_desktop_games_restart_on_the_gpu_with_steams_preloads_handed_back(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            gpu = home / "gpu"
            gpu.write_text("#!/bin/bash\necho \"gpu LD_PRELOAD=${LD_PRELOAD-unset}\"\nexport BL_DESKTOP_GPU_CHILD=1\nexec \"$@\"\n")
            gpu.chmod(0o755)
            launcher = home / "launcher"
            setup = COMPAT["BL_DESKTOP_GPU_SETUP"].replace("/usr/local/bin/droiddeck-gpu", str(gpu))
            launcher.write_text("#!/bin/bash\nverb=$1; shift\n" + setup + "echo \"game $verb $* LD_PRELOAD=${LD_PRELOAD-unset} saved=${BL_GPU_SAVED_LD_PRELOAD-unset}\"\n")
            launcher.chmod(0o755)
            base = {key: value for key, value in os.environ.items() if not key.startswith("BL_")}
            base["LD_PRELOAD"] = "overlay.so"
            for extra, args, expected in (
                ({"BL_DESKTOP": "1"}, ["waitforexitandrun", "game.exe"],
                 ["gpu LD_PRELOAD=unset", "game waitforexitandrun game.exe LD_PRELOAD=overlay.so saved=unset"]),
                ({"BL_DESKTOP": "1", "BL_DESKTOP_GPU": "Off"}, ["waitforexitandrun", "game.exe"],
                 ["game waitforexitandrun game.exe LD_PRELOAD=overlay.so saved=unset"]),
                ({"BL_DESKTOP": "1"}, ["run", "setup.exe"],
                 ["game run setup.exe LD_PRELOAD=overlay.so saved=unset"]),
                ({"BL_DESKTOP": "1"}, ["waitforexitandrun", "iscriptevaluator.exe"],
                 ["game waitforexitandrun iscriptevaluator.exe LD_PRELOAD=overlay.so saved=unset"]),
                ({}, ["waitforexitandrun", "game.exe"],
                 ["game waitforexitandrun game.exe LD_PRELOAD=overlay.so saved=unset"]),
            ):
                with self.subTest(extra=extra, args=args):
                    result = subprocess.run([str(launcher)] + args, env=dict(base, **extra),
                                            text=True, capture_output=True, check=True)
                    self.assertEqual(result.stdout.splitlines(), expected)

    def test_the_install_script_evaluator_gets_its_scripts_marked_first(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            script = root / "legacycompat/evaluatorscript_409710.vdf"
            script.parent.mkdir(parents=True)
            script.write_text("{}")
            calls = []
            run = MODULE["mark_install_scripts"].__globals__["subprocess"].run
            MODULE["mark_install_scripts"].__globals__["subprocess"].run = lambda args, **kw: calls.append((args, kw["env"]))
            try:
                env = {"STEAM_COMPAT_CLIENT_INSTALL_PATH": str(root)}
                MODULE["mark_install_scripts"](["/proton", "run", str(root / "legacycompat/iscriptevaluator.exe"), "legacycompat\\evaluatorscript_409710.vdf"], env)
                MODULE["mark_install_scripts"](["/proton", "waitforexitandrun", "/game/Game.exe"], env)
            finally:
                MODULE["mark_install_scripts"].__globals__["subprocess"].run = run
            self.assertEqual(len(calls), 1)
            self.assertEqual(calls[0][0][1], str(root))
            self.assertEqual(calls[0][1]["DROIDDECK_SEED_SCRIPTS"], str(script))

    SCRIPT = ('"evaluatorscript" { "0" { "appid" "228980" "compat_installscript" { "run process" { '
              '"x64 14.28.29334.0" { "hasrunkey" "HKEY_LOCAL_MACHINE\\\\Software\\\\Valve\\\\Steam\\\\Apps\\\\CommonRedist\\\\vcredist\\\\2019" '
              '"process 1" "%INSTALLDIR%\\\\x64.cmd" "requirement_os" { "Is64BitWindows" "1" } } '
              '"Setup" { "process 1" "%INSTALLDIR%\\\\setup.exe" } } } } }')
    REDIST = "[Software\\\\Wow6432Node\\\\Valve\\\\Steam\\\\Apps\\\\CommonRedist\\\\vcredist\\\\2019] 1\n\"x64 14.28.29334.0\"=dword:00000001\n"
    SETUP = "[Software\\\\Valve\\\\Steam\\\\Apps\\\\228980] 1\n\"Setup\"=dword:00000001\n"

    def test_install_script_run_keys_default_to_the_app_key(self):
        keys = MODULE["run_keys"](MODULE["read_vdf"](self.SCRIPT))
        self.assertEqual(keys, {
            ("hkey_local_machine\\software\\valve\\steam\\apps\\commonredist\\vcredist\\2019", "x64 14.28.29334.0"),
            ("hkey_local_machine\\software\\valve\\steam\\apps\\228980", "setup"),
        })
        with self.assertRaises(ValueError):
            MODULE["read_vdf"]('"a" { "b" }')

    def test_install_scripts_count_as_done_only_when_recorded_and_still_marked(self):
        with tempfile.TemporaryDirectory() as tmp:
            script = Path(tmp) / "evaluatorscript_1.vdf"
            script.write_text(self.SCRIPT)
            prefix = Path(tmp) / "compat/pfx"
            env = {"STEAM_COMPAT_DATA_PATH": str(prefix.parent)}
            done = MODULE["install_scripts_done"]
            self.assertFalse(done([str(script)], env))
            prefix.mkdir(parents=True)
            MODULE["record_install_scripts"](MODULE["scripts_digest"]([str(script)]), env)
            self.assertFalse(done([str(script)], env))
            (prefix / "system.reg").write_text(self.REDIST)
            self.assertFalse(done([str(script)], env))
            (prefix / "system.reg").write_text(self.REDIST + self.SETUP)
            self.assertTrue(done([str(script)], env))
            script.write_text(self.SCRIPT.replace("x64.cmd", "x64-update.cmd"))
            self.assertFalse(done([str(script)], env))
            script.write_text('"InstallScript" { "Registry" { "HKEY_LOCAL_MACHINE\\\\Software\\\\Game" { "string" { "Path" "%INSTALLDIR%" } } } }')
            MODULE["record_install_scripts"](MODULE["scripts_digest"]([str(script)]), env)
            self.assertFalse(done([str(script)], env))

    def test_the_evaluator_is_skipped_once_it_finished_the_same_scripts(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "legacycompat").mkdir()
            evaluator = root / "legacycompat/iscriptevaluator.exe"
            evaluator.write_bytes(b"evaluator")
            script = root / "legacycompat/evaluatorscript_1.vdf"
            prefix = root / "compatdata/1/pfx"
            prefix.mkdir(parents=True)
            (prefix / "system.reg").write_text(self.REDIST + self.SETUP)
            runs = root / "runs"
            proton = root / "proton"
            proton.write_text("#!/bin/sh\necho run >> %s\nexit ${EXIT_CODE:-0}\n" % shlex.quote(str(runs)))
            proton.chmod(0o755)

            def evaluate(exit_code="0"):
                script.write_text(self.SCRIPT if exit_code != "changed" else self.SCRIPT.replace("setup.exe", "setup-update.exe"))
                env = dict(os.environ, HOME=tmp, STEAM_COMPAT_CLIENT_INSTALL_PATH=str(root),
                           STEAM_COMPAT_DATA_PATH=str(prefix.parent), EXIT_CODE="0" if exit_code == "changed" else exit_code)
                result = subprocess.run([sys.executable, str(BIN / "droiddeck-game-env"), str(proton), "run",
                                         str(evaluator), "legacycompat\\evaluatorscript_1.vdf"],
                                        env=env, text=True, capture_output=True)
                return result.returncode, len(runs.read_text().splitlines()) if runs.exists() else 0

            self.assertEqual(evaluate("3"), (3, 1))
            self.assertFalse((prefix / ".droiddeck-install-scripts").exists())
            self.assertEqual(evaluate(), (0, 2))
            self.assertEqual(evaluate(), (0, 2))
            self.assertEqual(evaluate("changed"), (0, 3))

    def test_profile_precedence_and_unset(self):
        env = {"KEEP": "inherited", "REMOVE": "inherited", "CUSTOM": "launch option"}
        config = {"version": 1, "shared": {"CUSTOM": "shared"}, "games": {"42": {"REMOVE": None, "CUSTOM": "game", "EMPTY": ""}}}
        self.assertEqual(MODULE["apply_config"](env, config, "42"), {"KEEP": "inherited", "CUSTOM": "game", "EMPTY": ""})
        self.assertEqual(env["REMOVE"], "inherited")
        self.assertEqual(MODULE["apply_config"](env, config, "43")["CUSTOM"], "shared")

    def test_games_get_their_own_turnip_debug_and_a_game_profile_still_wins(self):
        gpu = MODULE["game_gpu"]
        self.assertEqual(gpu({"TU_DEBUG": "sysmem", "DROIDDECK_GAME_TU_DEBUG": "", "KEEP": "1"}), {"KEEP": "1"})
        self.assertEqual(gpu({"TU_DEBUG": "sysmem", "DROIDDECK_GAME_TU_DEBUG": "sysmem"}), {"TU_DEBUG": "sysmem"})
        self.assertEqual(gpu({"DROIDDECK_GAME_TU_DEBUG": "nolrz"}), {"TU_DEBUG": "nolrz"})
        self.assertEqual(gpu({"TU_DEBUG": "sysmem"}), {"TU_DEBUG": "sysmem"})
        config = {"version": 1, "games": {"42": {"TU_DEBUG": "sysmem"}}}
        env = gpu({"TU_DEBUG": "sysmem", "DROIDDECK_GAME_TU_DEBUG": ""})
        self.assertEqual(MODULE["apply_config"](env, config, "42")["TU_DEBUG"], "sysmem")
        self.assertNotIn("TU_DEBUG", MODULE["apply_config"](env, config, "43"))

    def test_the_vram_cap_comes_before_the_games_own_dxvk_settings(self):
        cap = MODULE["vram_cap"]
        self.assertEqual(cap({"BL_VRAM_MB": "4096"})["DXVK_CONFIG"], "dxgi.maxDeviceMemory = 4096")
        self.assertEqual(cap({"BL_VRAM_MB": "2048", "DXVK_CONFIG": "dxvk.maxFrameRate = 60;"})["DXVK_CONFIG"],
                         "dxgi.maxDeviceMemory = 2048; dxvk.maxFrameRate = 60")
        for off in ({}, {"BL_VRAM_MB": "0"}, {"BL_VRAM_MB": "lots"}, {"BL_VRAM_MB": "²"}, {"BL_VRAM_MB": "-1"}):
            self.assertNotIn("DXVK_CONFIG", cap(off))

    def test_a_games_wine_sync_choice_also_reaches_its_setup_runs(self):
        env = {"BL_SYNC": "1", "BL_SYNC_FALLBACK": "1", "BL_FSYNC_FIRST": "0", "OTHER": "kept"}
        config = {"version": 1, "games": {"42": {"BL_SYNC": "0", "BL_FSYNC_FIRST": "1", "PROTON_NO_ESYNC": "1", "FEX_TSOENABLED": "1"}}}
        chosen = MODULE["apply_config"](env, MODULE["sync_config"](config, "42"), "42")
        self.assertEqual(chosen, {"BL_SYNC": "0", "BL_SYNC_FALLBACK": "1", "BL_FSYNC_FIRST": "1", "PROTON_NO_ESYNC": "1", "OTHER": "kept"})

    def test_component_profiles_override_general_and_restore_it_for_the_next_game(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            home = root / "home"
            component_dir = home / ".local/share/droiddeck-components"
            base = root / "proton"
            target = base / "files/lib/wine/dxvk/aarch64-windows/d3d11.dll"
            leftover = target.with_name("d3d8.dll")
            component_dir.mkdir(parents=True)
            target.parent.mkdir(parents=True)
            (base / "version").write_text("1 build-1\n")
            general = root / "general.dll"
            game = root / "game.dll"
            general.write_text("general")
            game.write_text("game")
            target.write_text("old")
            leftover.write_text("old")
            digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
            (component_dir / "desired.tsv").write_text(
                "*\tproton\tbuild-1\tdxvk\tfiles/lib/wine/dxvk/aarch64-windows/d3d11.dll\t%s\t%s\n"
                "42\tproton\tbuild-1\tdxvk\tfiles/lib/wine/dxvk/aarch64-windows/d3d11.dll\t%s\t%s\n"
                "42\tproton\tbuild-1\tdxvk\tfiles/lib/wine/dxvk/aarch64-windows/d3d8.dll\t-\t-\n"
                % (digest(general), general, digest(game), game)
            )
            script = COMPAT["BL_COMPONENTS_SETUP"] + '\nverb=waitforexitandrun; bl_components "$1"\n'
            env = dict(os.environ, HOME=str(home), STEAM_COMPAT_DATA_PATH="/steam/compatdata/42")
            subprocess.run(["bash", "-c", script, "test", str(base)], env=env, check=True)
            self.assertEqual(target.read_text(), "game")
            self.assertFalse(leftover.exists())

            env["STEAM_COMPAT_DATA_PATH"] = "/steam/compatdata/43"
            subprocess.run(["bash", "-c", script, "test", str(base)], env=env, check=True)
            self.assertEqual(target.read_text(), "general")

    def test_system_vulkan_preserves_dxvk_profiles_without_applying_them(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            home = root / "home"
            component_dir = home / ".local/share/droiddeck-components"
            component_dir.mkdir(parents=True)
            base = root / "proton"
            paths = {
                "dxvk": "files/lib/wine/dxvk/aarch64-windows/d3d11.dll",
                "fex": "files/lib/wine/aarch64-windows/libarm64ecfex.dll",
                "vkd3d": "files/lib/wine/vkd3d-proton/aarch64-windows/d3d12.dll",
            }
            rows = []
            for comp, rel in paths.items():
                target = base / rel
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text("bundled " + comp)
                for scope in ("*", "42"):
                    source = root / (comp + ("-general" if scope == "*" else "-game"))
                    source.write_text(source.name)
                    rows.append("%s\tproton\tbuild-1\t%s\t%s\t%s\t%s\n" % (
                        scope, comp, rel, hashlib.sha256(source.read_bytes()).hexdigest(), source,
                    ))
            leftover = (base / paths["dxvk"]).with_name("d3d8.dll")
            leftover.write_text("bundled d3d8")
            rows.append("42\tproton\tbuild-1\tdxvk\t%s\t-\t-\n" % leftover.relative_to(base))
            (base / "version").write_text("1 build-1\n")
            selection = component_dir / "desired.tsv"
            selection.write_text("".join(rows))
            saved = selection.read_bytes()
            script = COMPAT["BL_COMPONENTS_SETUP"] + '\nverb=waitforexitandrun; bl_components "$1"\n'
            env = dict(os.environ, HOME=str(home), STEAM_COMPAT_DATA_PATH="/steam/compatdata/42")
            for key in ("DROIDDECK_SYSTEM_VULKAN_RUNTIME", "DROIDDECK_POWERVR_RUNTIME",
                        "DROIDDECK_SYSTEM_VULKAN_GPU", "DROIDDECK_SYSTEM_VULKAN_DXVK"):
                env.pop(key, None)
            managed = [dict(env, **{key: "/system_vulkan/runtime"})
                       for key in ("DROIDDECK_SYSTEM_VULKAN_RUNTIME", "DROIDDECK_POWERVR_RUNTIME")]
            managed += [dict(env, DROIDDECK_SYSTEM_VULKAN_RUNTIME="/system_vulkan/runtime",
                             DROIDDECK_SYSTEM_VULKAN_GPU=gpu, DROIDDECK_SYSTEM_VULKAN_DXVK="managed")
                        for gpu in ("xclipse", "mali", "future-vendor")]
            for selected in managed:
                with self.subTest(profile=selected.get("DROIDDECK_SYSTEM_VULKAN_GPU", "legacy")):
                    subprocess.run(["bash", "-c", script, "test", str(base)], env=selected, check=True)

                    self.assertEqual((base / paths["dxvk"]).read_text(), "bundled dxvk")
                    self.assertEqual(leftover.read_text(), "bundled d3d8")
                    for comp in ("fex", "vkd3d"):
                        self.assertEqual((base / paths[comp]).read_text(), comp + "-game")
                    self.assertEqual(selection.read_bytes(), saved)

            # User-managed profiles and manual drivers apply the saved per-game DXVK choice.
            user_managed = [dict(env, DROIDDECK_SYSTEM_VULKAN_RUNTIME="/system_vulkan/runtime",
                                 DROIDDECK_SYSTEM_VULKAN_GPU="mali"), env]
            user_managed += [dict(env, DROIDDECK_SYSTEM_VULKAN_RUNTIME="/system_vulkan/runtime",
                                 DROIDDECK_SYSTEM_VULKAN_GPU=gpu, DROIDDECK_SYSTEM_VULKAN_DXVK="user")
                            for gpu in ("powervr", "xclipse", "future-vendor")]
            for profile in user_managed:
                with self.subTest(gpu=profile.get("DROIDDECK_SYSTEM_VULKAN_GPU", "manual")):
                    (base / paths["dxvk"]).write_text("bundled dxvk")
                    leftover.write_text("bundled d3d8")
                    subprocess.run(["bash", "-c", script, "test", str(base)], env=profile, check=True)
                    self.assertEqual((base / paths["dxvk"]).read_text(), "dxvk-game")
                    self.assertFalse(leftover.exists())
                    self.assertEqual(selection.read_bytes(), saved)

    def test_component_scope_matches_environment_scope_for_shortcut_ids(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            component_dir = root / ".local/share/droiddeck-components"
            base = root / "proton"
            target = base / "files/lib/wine/dxvk/aarch64-windows/d3d11.dll"
            component_dir.mkdir(parents=True)
            target.parent.mkdir(parents=True)
            (base / "version").write_text("1 build-1\n")
            rows = []
            for scope in ("*", "42", "4294967295", "2147483648"):
                source = root / ("default" if scope == "*" else scope)
                source.write_text(scope)
                rows.append("%s\tproton\tbuild-1\tdxvk\t%s\t%s\t%s\n" % (
                    scope, target.relative_to(base),
                    hashlib.sha256(source.read_bytes()).hexdigest(), source,
                ))
            (component_dir / "desired.tsv").write_text("".join(rows))
            script = COMPAT["BL_COMPONENTS_SETUP"] + '\nverb=waitforexitandrun; bl_components "$1"\n'
            for value in (
                "42", "00042", "43", "-1", "-0001", "4294967295", "-2147483648",
                "2147483648", "-1///", "0", "-0", "000", "-2147483649", "4294967296",
                "999999999999999999999999999", "not-an-id", "1+1", "",
            ):
                with self.subTest(prefix=value):
                    env = dict(os.environ, HOME=str(root), STEAM_COMPAT_DATA_PATH="/steam/compatdata/" + value)
                    target.write_text("untouched")
                    subprocess.run(["bash", "-c", script, "test", str(base)], env=env, check=True, capture_output=True)
                    scope = MODULE["game_id"](env)
                    expected = "untouched" if scope is None else scope if scope in {"42", "4294967295", "2147483648"} else "*"
                    self.assertEqual(target.read_text(), expected)

    def test_engine_fixes_follow_the_games_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            game = Path(tmp) / "Godot Game"
            (game / "data_game_windows_x86_64").mkdir(parents=True)
            for name in ("Game.exe", "Game.pck", "libsentry.windows.release.x86_64.dll", "data_game_windows_x86_64/coreclr.dll"):
                (game / name).touch()
            env, extra, notes = MODULE["engine_fixes"](str(game / "Game.exe"), [])
            self.assertEqual(env["FEX_TSOENABLED"], "1")
            self.assertEqual(env["FEX_MULTIBLOCK"], "0")
            self.assertEqual(env["WINEDLLOVERRIDES"], "libsentry.windows.release.x86_64=d")
            self.assertEqual(extra, ["--rendering-driver", "vulkan"])
            self.assertEqual(MODULE["engine_fixes"](str(game / "Game.exe"), ["--rendering-driver", "opengl3"])[1], [])
            other = Path(tmp) / "Other"
            other.mkdir()
            (other / "Other.exe").touch()
            self.assertEqual(MODULE["engine_fixes"](str(other / "Other.exe"), []), ({}, [], []))

    def test_easy_anti_cheat_games_start_offline_only_when_opted_in(self):
        with tempfile.TemporaryDirectory() as tmp:
            game = Path(tmp) / "ELDEN RING/Game"
            (game / "EasyAntiCheat").mkdir(parents=True)
            for name in ("start_protected_game.exe", "eldenring.exe"):
                (game / name).touch()
            (game / "EasyAntiCheat/settings.json").write_text('\ufeff{ "executable" : "eldenring.exe" }', encoding="utf-8")
            command = ["proton", "waitforexitandrun", str(game / "start_protected_game.exe"), "-arg"]
            offline = MODULE["offline_without_anticheat"]
            self.assertEqual(offline(command, {}), command)
            self.assertEqual(offline(command, {"DROIDDECK_ANTICHEAT_OFFLINE": "0"}), command)
            self.assertEqual(offline(command, {"DROIDDECK_ANTICHEAT_OFFLINE": "1"}),
                             ["proton", "waitforexitandrun", str(game / "eldenring.exe"), "-arg"])
            (game / "EasyAntiCheat/settings.json").write_text('{ "executable" : "../../escape.exe" }')
            (Path(tmp) / "escape.exe").touch()
            self.assertEqual(offline(command, {"DROIDDECK_ANTICHEAT_OFFLINE": "1"}), command)
            (game / "EasyAntiCheat/settings.json").write_text('{ "executable" : "missing.exe" }')
            self.assertEqual(offline(command, {"DROIDDECK_ANTICHEAT_OFFLINE": "1"}), command)
            (game / "EasyAntiCheat/settings.json").write_text('{ "executable" : "link/escape.exe" }')
            outside = Path(tmp) / "outside"
            outside.mkdir()
            (outside / "escape.exe").touch()
            (game / "link").symlink_to(outside, target_is_directory=True)
            self.assertEqual(offline(command, {"DROIDDECK_ANTICHEAT_OFFLINE": "1"}), command)
            (game / "EasyAntiCheat/settings.json").write_text('{ "executable" : "eldenring.exe" }')
            plain = ["proton", "waitforexitandrun", str(game / "eldenring.exe")]
            self.assertEqual(offline(plain, {"DROIDDECK_ANTICHEAT_OFFLINE": "1"}), plain)
            self.assertEqual(offline(["proton", "run", command[2]],
                                     {"DROIDDECK_ANTICHEAT_OFFLINE": "1"}),
                             ["proton", "run", command[2]])

    def test_easy_anti_cheat_forwarding_through_entrypoint(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            game = home / "ELDEN RING/Game"
            (game / "EasyAntiCheat").mkdir(parents=True)
            bootstrapper = game / "start_protected_game.exe"
            target = game / "eldenring.exe"
            bootstrapper.touch()
            target.touch()
            (game / "EasyAntiCheat/settings.json").write_text(json.dumps({"executable": "eldenring.exe"}))
            proton = home / "fake-proton"
            proton.write_text("#!" + sys.executable + "\nimport json, sys\nprint(json.dumps(sys.argv[1:]))\n")
            proton.chmod(0o755)
            config = home / ".config/droiddeck/game-environment.json"
            config.parent.mkdir(parents=True)
            env = dict(os.environ, HOME=tmp, STEAM_COMPAT_DATA_PATH="/compatdata/42")
            env.pop("DROIDDECK_ANTICHEAT_OFFLINE", None)
            for setting, expected in (("1", target), ("0", bootstrapper), (None, bootstrapper)):
                with self.subTest(setting=setting):
                    values = {} if setting is None else {"DROIDDECK_ANTICHEAT_OFFLINE": setting}
                    config.write_text(json.dumps({"version": 1, "games": {"42": values}}))
                    result = subprocess.run([sys.executable, str(BIN / "droiddeck-game-env"),
                                             str(proton), "waitforexitandrun", str(bootstrapper), "-arg"],
                                            env=env, text=True, capture_output=True, check=True)
                    self.assertEqual(json.loads(result.stdout.strip()),
                                     ["waitforexitandrun", str(expected), "-arg"])

    def test_deep_rock_gdk_avoids_the_raytracing_pipeline_crash(self):
        game = "/game/FSD/Binaries/WinGDK/FSD-WinGDK-Shipping.exe"
        env, extra, notes = MODULE["engine_fixes"](game, [])
        self.assertEqual(extra, ["-noraytracing", "-ini:Engine:[/Script/Engine.RendererSettings]:r.RayTracing=False"])
        self.assertEqual(env, {})
        self.assertTrue(notes)
        for choice in ("-raytracing", "-noraytracing"):
            self.assertEqual(MODULE["engine_fixes"](game, [choice]), ({}, [], []))
        self.assertEqual(MODULE["engine_fixes"]("/game/FSD-Win64-Shipping.exe", []), ({}, [], []))
        self.assertEqual(MODULE["engine_fixes"]("/game/Other-WinGDK-Shipping.exe", []), ({}, [], []))

    def test_arkham_city_gets_full_x87_precision_over_the_preset(self):
        with tempfile.TemporaryDirectory() as tmp:
            city = Path(tmp) / "Batman Arkham City GOTY/Binaries/Win32"
            city.mkdir(parents=True)
            for name in ("BatmanAC.exe", "BmLauncher.exe"):
                (city / name).touch()
            for exe in ("BatmanAC.exe", "BmLauncher.exe"):
                env, extra, notes = MODULE["engine_fixes"](str(city / exe), [])
                self.assertEqual((env, extra), ({"FEX_X87REDUCEDPRECISION": "0"}, []))
            config = {"version": 1, "shared": {"FEX_X87REDUCEDPRECISION": "1"}}
            self.assertEqual(MODULE["apply_config"]({}, config, "200260", env)["FEX_X87REDUCEDPRECISION"], "0")
            config["games"] = {"200260": {"FEX_X87REDUCEDPRECISION": "1"}}
            self.assertEqual(MODULE["apply_config"]({}, config, "200260", env)["FEX_X87REDUCEDPRECISION"], "1")
            asylum = Path(tmp) / "Batman Arkham Asylum GOTY/Binaries"
            asylum.mkdir(parents=True)
            for name in ("ShippingPC-BmGame.exe", "BmLauncher.exe"):
                (asylum / name).touch()
            self.assertEqual(MODULE["engine_fixes"](str(asylum / "BmLauncher.exe"), []), ({}, [], []))

    def test_black_ops_iii_and_dark_souls_remastered_skip_protonfixes(self):
        for exe in ("/game/Call of Duty Black Ops III/BlackOps3.exe", "/game/DARK SOULS REMASTERED/DarkSoulsRemastered.exe"):
            env, extra, notes = MODULE["engine_fixes"](exe, [])
            self.assertEqual((env, extra), ({"PROTONFIXES_DISABLE": "1"}, []))
            self.assertTrue(notes)
        self.assertEqual(MODULE["engine_fixes"]("/game/Game/DarkSoulsII.exe", []), ({}, [], []))

    def test_game_cores_are_parsed_from_lists_and_ranges(self):
        parse = MODULE["parse_cores"]
        self.assertEqual(parse("0-3,6"), {0, 1, 2, 3, 6})
        self.assertEqual(parse(" 4, 5 "), {4, 5})
        for bad in ("", "a", "3-1", "1,,2", "-1", "1-"):
            self.assertIsNone(parse(bad), bad)

    def test_a_profiles_game_cores_pin_the_game_within_the_online_cores(self):
        calls = []
        os_mod = MODULE["os"]
        with mock.patch.dict(MODULE["game_cores"].__globals__, {"online_cores": lambda: {0, 1, 2, 3, 4, 5, 6, 7}}), \
                mock.patch.object(os_mod, "sched_setaffinity", side_effect=lambda pid, cores: calls.append(set(cores)), create=True):
            MODULE["game_cores"]({"DROIDDECK_GAME_CORES": "0-2,9"})
            MODULE["game_cores"]({"DROIDDECK_GAME_CORES": "junk"})
            MODULE["game_cores"]({"DROIDDECK_GAME_CORES": "12-13"})
            MODULE["game_cores"]({})
        self.assertEqual(calls, [{0, 1, 2}])
        self.assertEqual(MODULE["parse_cores"]("0,2-7"), {0, 2, 3, 4, 5, 6, 7})

    def test_caravan_sandwitch_robocop_and_cyberpunk_run_at_feature_level_12_1(self):
        with tempfile.TemporaryDirectory() as tmp:
            caravan = Path(tmp) / "Caravan SandWitch"
            shipping = caravan / "CaravanSandWitch/Binaries/Win64"
            shipping.mkdir(parents=True)
            for name in ("CaravanSandWitch.exe", ".droiddeck-launch.bat"):
                (caravan / name).touch()
            (shipping / "CaravanSandWitch-Win64-Shipping.exe").touch()
            cyberpunk = Path(tmp) / "Cyberpunk 2077"
            (cyberpunk / "bin/x64").mkdir(parents=True)
            for name in ("REDprelauncher.exe", "bin/x64/Cyberpunk2077.exe"):
                (cyberpunk / name).touch()
            robocop = Path(tmp) / "RoboCop Rogue City"
            (robocop / "RoboCop/Binaries/Win64").mkdir(parents=True)
            for name in ("RoboCop.exe", "RoboCop/Binaries/Win64/RoboCop-Win64-Shipping.exe"):
                (robocop / name).touch()
            games = [caravan / "CaravanSandWitch.exe", caravan / ".droiddeck-launch.bat",
                     shipping / "CaravanSandWitch-Win64-Shipping.exe",
                     robocop / "RoboCop.exe", robocop / "RoboCop/Binaries/Win64/RoboCop-Win64-Shipping.exe",
                     cyberpunk / "REDprelauncher.exe", cyberpunk / "bin/x64/Cyberpunk2077.exe"]
            for exe in games:
                env, extra, notes = MODULE["engine_fixes"](str(exe), [])
                self.assertEqual((env, extra), ({"VKD3D_FEATURE_LEVEL": "12_1"}, []), exe)
                self.assertTrue(notes)
            config = {"version": 1, "shared": {"VKD3D_FEATURE_LEVEL": "12_2"}}
            self.assertEqual(MODULE["apply_config"]({}, config, "1582650", env)["VKD3D_FEATURE_LEVEL"], "12_1")
            config["games"] = {"1582650": {"VKD3D_FEATURE_LEVEL": "12_2"}}
            self.assertEqual(MODULE["apply_config"]({}, config, "1582650", env)["VKD3D_FEATURE_LEVEL"], "12_2")
            other = Path(tmp) / "Other/Binaries/Win64"
            other.mkdir(parents=True)
            (other / "Other-Win64-Shipping.exe").touch()
            self.assertEqual(MODULE["engine_fixes"](str(other / "Other-Win64-Shipping.exe"), []), ({}, [], []))
            witcher = Path(tmp) / "The Witcher 3"
            witcher.mkdir()
            (witcher / "REDprelauncher.exe").touch()
            self.assertNotIn("VKD3D_FEATURE_LEVEL", MODULE["engine_fixes"](str(witcher / "REDprelauncher.exe"), [])[0])

    def test_unity_players_keep_tso_over_the_preset(self):
        with tempfile.TemporaryDirectory() as tmp:
            def game(*names):
                for name in names:
                    (Path(tmp) / name).parent.mkdir(parents=True, exist_ok=True)
                    (Path(tmp) / name).touch()
                return str(Path(tmp) / names[0])

            il2cpp = game("Megabonk/Megabonk.x86_64", "Megabonk/UnityPlayer.so", "Megabonk/GameAssembly.so")
            mono = game("Mono/Game.x86_64", "Mono/UnityPlayer.so")
            windows = game("Crab Game/Crab Game.exe", "Crab Game/UnityPlayer.dll", "Crab Game/GameAssembly.dll")
            windows_mono = game("RoR2/Risk of Rain 2.exe", "RoR2/UnityPlayer.dll", "RoR2/MonoBleedingEdge/mono-2.0-bdwgc.dll")
            for exe in (il2cpp, mono, windows, windows_mono):
                env, extra, notes = MODULE["engine_fixes"](exe, [])
                self.assertEqual((env, extra, notes), ({"FEX_TSOENABLED": "1"}, [], ["Unity: FEX with TSO"]))
            performance = {"version": 1, "shared": {"FEX_TSOENABLED": "0", "FEX_VECTORTSOENABLED": "0"}}
            result = MODULE["apply_config"]({}, performance, "3405340", env)
            self.assertEqual((result["FEX_TSOENABLED"], result["FEX_VECTORTSOENABLED"]), ("1", "0"))
            performance["games"] = {"3405340": {"FEX_TSOENABLED": "0"}}
            self.assertEqual(MODULE["apply_config"]({}, performance, "3405340", env)["FEX_TSOENABLED"], "0")

    def test_a_new_fix_only_needs_registering(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "Game.exe").touch()
            (Path(tmp) / "engine.dll").touch()
            check = lambda game: ({"ENGINE": "1"}, ["--flag"], "engine") if "engine.dll" in game.files else None
            MODULE["FIXES"].append(check)
            try:
                self.assertEqual(MODULE["engine_fixes"](str(Path(tmp) / "Game.exe"), []), ({"ENGINE": "1"}, ["--flag"], ["engine"]))
            finally:
                MODULE["FIXES"].remove(check)

    def test_engine_fixes_sit_between_shared_and_game_profiles(self):
        env = {"WINEDLLOVERRIDES": "dxgi=n"}
        fixes = {"FEX_MULTIBLOCK": "0", "WINEDLLOVERRIDES": "libsentry=d"}
        config = {"version": 1, "shared": {"FEX_MULTIBLOCK": "1"}, "games": {"42": {"FEX_TSOENABLED": "0"}}}
        result = MODULE["apply_config"](env, config, "42", {**fixes, "FEX_TSOENABLED": "1"})
        self.assertEqual(result["FEX_MULTIBLOCK"], "0")
        self.assertEqual(result["FEX_TSOENABLED"], "0")
        self.assertEqual(result["WINEDLLOVERRIDES"], "dxgi=n;libsentry=d")

    def test_invalid_configuration_is_atomic(self):
        env = {"ORIGINAL": "unchanged"}
        for entries in ({"A": "ok", "BAD=KEY": "x"}, {"A": "bad\0value"}, {"A": 1}):
            with self.assertRaises(ValueError):
                MODULE["apply_config"](env, {"version": 1, "shared": entries}, "42")
            self.assertEqual(env, {"ORIGINAL": "unchanged"})

    def test_texture_filtering_is_appended_to_dxvk_config(self):
        options = "d3d9.samplerAnisotropy = 16; d3d11.samplerAnisotropy = 16"
        config = {"version": 1, "shared": {}, "games": {}, "dxvkConfig": options}
        self.assertEqual(MODULE["apply_config"]({}, config, "42")["DXVK_CONFIG"], options)
        own = {"DXVK_CONFIG": "dxvk.maxFrameRate = 60; "}
        self.assertEqual(MODULE["apply_config"](own, config, "42")["DXVK_CONFIG"], "dxvk.maxFrameRate = 60; " + options)
        shared = {"version": 1, "shared": {"DXVK_CONFIG": "dxvk.tearFree = True"}, "games": {}, "dxvkConfig": options}
        self.assertEqual(MODULE["apply_config"]({}, shared, "42")["DXVK_CONFIG"], "dxvk.tearFree = True; " + options)
        self.assertNotIn("DXVK_CONFIG", MODULE["apply_config"]({}, {**config, "dxvkConfig": ""}, "42"))
        for bad in (1, "a\0b", "x" * 8193):
            with self.assertRaises(ValueError):
                MODULE["apply_config"]({}, {**config, "dxvkConfig": bad}, "42")

    def test_game_texture_filtering_overrides_default_without_leaking(self):
        default = "d3d9.samplerAnisotropy = 16"
        game = "d3d9.samplerAnisotropy = 4"
        config = {
            "version": 1,
            "shared": {},
            "games": {},
            "dxvkConfig": default,
            "dxvkConfigGames": {"42": game, "43": ""},
        }
        self.assertEqual(MODULE["apply_config"]({}, config, "42")["DXVK_CONFIG"], game)
        self.assertNotIn("DXVK_CONFIG", MODULE["apply_config"]({}, config, "43"))
        self.assertEqual(MODULE["apply_config"]({}, config, "44")["DXVK_CONFIG"], default)
        with self.assertRaises(ValueError):
            MODULE["apply_config"]({}, {**config, "dxvkConfigGames": []}, "42")

    def test_game_ids_and_probes(self):
        for prefix in ("", "/compatdata/0", "/compatdata/0-123", "/compatdata/nope", "/compatdata/4294967296"):
            self.assertIsNone(MODULE["game_id"]({"STEAM_COMPAT_DATA_PATH": prefix}))
        self.assertEqual(MODULE["game_id"]({"STEAM_COMPAT_DATA_PATH": "/a/compatdata/42/"}), "42")
        self.assertEqual(MODULE["game_id"]({"STEAM_COMPAT_DATA_PATH": "/a/compatdata/-1"}), "4294967295")

    def test_launch_reads_updates_preserves_argv_and_does_not_execute_values(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            config = home / ".config/droiddeck/game-environment.json"
            config.parent.mkdir(parents=True)
            probe = home / "fake-proton"
            probe.write_text("#!/usr/bin/python3\nimport json, os, sys\nprint(json.dumps([os.environ.get('CUSTOM'), sys.argv[1:]]))\n")
            probe.chmod(0o755)
            env = {**os.environ, "HOME": tmp, "STEAM_COMPAT_DATA_PATH": "/compatdata/42"}
            for value in ("first value", "$(touch " + str(home / "injected") + "); 'literal'=value"):
                config.write_text(json.dumps({"version": 1, "shared": {"CUSTOM": value}}))
                result = subprocess.check_output([sys.executable, str(BIN / "droiddeck-game-env"), str(probe), "waitforexitandrun", "path with spaces", "a=b"], env=env, text=True)
                self.assertEqual(json.loads(result), [value, ["waitforexitandrun", "path with spaces", "a=b"]])
            self.assertFalse((home / "injected").exists())
            for verb, prefix in (("run", "/compatdata/42"), ("waitforexitandrun", "/compatdata/0")):
                output = subprocess.check_output([sys.executable, str(BIN / "droiddeck-game-env"), str(probe), verb], env={**env, "STEAM_COMPAT_DATA_PATH": prefix, "CUSTOM": "original"}, text=True)
                self.assertEqual(json.loads(output)[0], "original")
            config.write_text("{broken")
            result = subprocess.run([sys.executable, str(BIN / "droiddeck-game-env"), str(probe), "waitforexitandrun"], env={**env, "CUSTOM": "original"}, text=True, capture_output=True, check=True)
            self.assertEqual(json.loads(result.stdout)[0], "original")

    def test_generated_valve_and_third_party_launchers_apply_configuration(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            steam = home / "Steam"
            tools = steam / "compatibilitytools.d"
            depot = steam / "steamapps/common" / COMPAT["SOURCES"][0]
            (depot / "files/bin-arm64").mkdir(parents=True)
            extra = tools / "custom-proton"
            extra.mkdir(parents=True)
            for base in (depot, extra):
                proton = base / "proton"
                proton.write_text("#!/usr/bin/python3\nimport json, os, sys\nprint(json.dumps([os.environ['CUSTOM'], sys.argv[1:]]))\nsys.exit(7)\n")
                proton.chmod(0o755)
            (extra / "toolmanifest.vdf").write_text('"manifest" { "commandline" "/proton %verb%" }')
            COMPAT["build_tool"](str(tools / COMPAT["TOOL"]), str(depot))
            COMPAT["adopt_extras"](str(tools))
            config = home / ".config/droiddeck/game-environment.json"
            config.parent.mkdir(parents=True)
            config.write_text(json.dumps({"version": 1, "shared": {"CUSTOM": "shared"}, "games": {"42": {"CUSTOM": "specific"}}}))
            wrappers = (tools / COMPAT["TOOL"] / COMPAT["LAUNCHER"], extra / COMPAT["EXTRA_WRAPPER"])
            for wrapper in wrappers:
                wrapper.write_text(wrapper.read_text().replace("/usr/local/bin/droiddeck-game-env", str(BIN / "droiddeck-game-env")))
                result = subprocess.run([str(wrapper), "waitforexitandrun", "game with spaces.exe"],
                    env={"PATH": os.defpath, "HOME": tmp, "STEAM_COMPAT_DATA_PATH": "/compatdata/42", "STEAM_COMPAT_CLIENT_INSTALL_PATH": str(steam)},
                    text=True, capture_output=True)
                self.assertEqual(result.returncode, 7, result.stderr)
                self.assertEqual(json.loads(result.stdout), ["specific", ["waitforexitandrun", "game with spaces.exe"]])

    def test_default_is_compatible_tool_and_labels_mark_it(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            label = home / "label"
            label.write_text("Compatible\n")
            COMPAT["display_name"].__globals__["LABEL_FILE"] = str(label)
            tools = home / "compatibilitytools.d"
            extra = tools / "proton-cachyos-11"
            extra.mkdir(parents=True)
            (extra / "proton").write_text("")
            (extra / "toolmanifest.vdf").write_text('"manifest" { "commandline" "/proton %verb%" }')
            vdf = tools / COMPAT["TOOL"] / "compatibilitytool.vdf"
            COMPAT["build_tool"](str(tools / COMPAT["TOOL"]), None)
            self.assertIn('"display_name" "Proton ARM64 (Compatible)"', vdf.read_text())
            for source in COMPAT["SOURCES"]:
                COMPAT["build_tool"](str(tools / COMPAT["TOOL"]), str(home / "steamapps/common" / source))
                self.assertIn('"display_name" "%s ARM64 (Compatible)"' % source.replace(" (ARM64)", ""), vdf.read_text())
            protect = COMPAT["adopt_extras"](str(tools))
            self.assertIn('"display_name" "proton-cachyos-11 (Compatible)"', (extra / "compatibilitytool.vdf").read_text())
            config = home / "config.vdf"
            mapping = "".join('"%s" { "name" "%s" "config" "" "priority" "%s" }' % entry for entry in (
                ("0", "GE-Proton10-25", "75"), ("42", "proton_experimental_arm64", "250"), ("43", "proton-cachyos-11", "250")))
            config.write_text('"InstallConfigStore" { "Software" { "Valve" { "Steam" { "CompatToolMapping" { %s } } } } }' % mapping)
            COMPAT["register_default"](str(config), ["44"], protect=protect)
            tokens = COMPAT["tokenize"](config.read_text())
            names = {tokens[i - 1].strip('"'): tokens[i + 2].strip('"') for i in range(1, len(tokens) - 2) if tokens[i] == "{" and tokens[i + 1] == '"name"'}
            self.assertEqual(names, {"0": COMPAT["TOOL"], "42": COMPAT["TOOL"], "43": "proton-cachyos-11", "44": COMPAT["TOOL"]})

    def test_existing_install_moves_to_new_tool_name(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            (home / "label").write_text("Compatible\n")
            COMPAT["main"].__globals__["LABEL_FILE"] = str(home / "label")
            steam = home / "Steam"
            depot = steam / "steamapps/common" / COMPAT["SOURCES"][0]
            (depot / "files/bin-arm64").mkdir(parents=True)
            (steam / "steamapps/appmanifest_42.acf").write_text("")
            legacy = steam / "compatibilitytools.d" / COMPAT["LEGACY_TOOL"]
            legacy.mkdir(parents=True)
            for name in COMPAT["OWN_FILES"]:
                (legacy / name).write_text("old")
            (steam / "config").mkdir()
            config = steam / "config/config.vdf"
            mapping = "".join('"%s" { "name" "%s" "config" "" "priority" "%s" }' % (app, COMPAT["LEGACY_TOOL"], priority)
                              for app, priority in (("0", "75"), ("42", "250")))
            config.write_text('"InstallConfigStore" { "Software" { "Valve" { "Steam" { "CompatToolMapping" { %s } } } } }' % mapping)
            argv = sys.argv
            try:
                sys.argv = ["steam-compatibility", str(steam)]
                COMPAT["main"]()
                migrated = config.read_text()
                COMPAT["main"]()
            finally:
                sys.argv = argv
            self.assertFalse(legacy.exists())
            self.assertIn('"display_name" "Proton Experimental ARM64 (Compatible)"',
                          (steam / "compatibilitytools.d" / COMPAT["TOOL"] / "compatibilitytool.vdf").read_text())
            self.assertNotIn(COMPAT["LEGACY_TOOL"], migrated)
            self.assertEqual(migrated.count('"%s"' % COMPAT["TOOL"]), 2)
            self.assertEqual(config.read_text(), migrated)

    def test_launchers_and_adopted_protons_from_before_the_rename_move_over(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            (home / "label").write_text("Compatible\n")
            COMPAT["main"].__globals__["LABEL_FILE"] = str(home / "label")
            tools = home / "compatibilitytools.d"
            own = tools / COMPAT["TOOL"]
            own.mkdir(parents=True)
            for name in ("toolmanifest.vdf", "compatibilitytool.vdf", COMPAT["LEGACY_LAUNCHER"]):
                (own / name).write_text("old")
            spare = tools / COMPAT["TOOL_11"]
            spare.mkdir()
            for name in ("toolmanifest.vdf", "compatibilitytool.vdf", COMPAT["LEGACY_LAUNCHER"]):
                (spare / name).write_text("old")
            extra = tools / "GE-Proton11-7"
            extra.mkdir()
            (extra / "proton").write_text("")
            (extra / "toolmanifest.vdf").write_text('"manifest" { "commandline" "/%s %%verb%%" }' % COMPAT["LEGACY_EXTRA_WRAPPER"])
            (extra / ("toolmanifest.vdf" + COMPAT["LEGACY_ORIGINAL_SUFFIX"])).write_text('"manifest" { "commandline" "/proton %verb%" }')
            (extra / COMPAT["LEGACY_EXTRA_WRAPPER"]).write_text("old")
            COMPAT["build_tool"](str(own), None)
            COMPAT["remove_tool"](str(spare))
            self.assertEqual(COMPAT["adopt_extras"](str(tools)), {"GE-Proton11-7": "GE-Proton11-7"})
            self.assertEqual(sorted(os.listdir(own)), sorted(COMPAT["OWN_FILES"]))
            self.assertFalse(spare.exists())
            self.assertEqual(sorted(os.listdir(extra)), sorted(["proton", "toolmanifest.vdf", "compatibilitytool.vdf",
                                                                 COMPAT["EXTRA_WRAPPER"], "toolmanifest.vdf" + COMPAT["ORIGINAL_SUFFIX"]]))
            self.assertIn("/%s %%verb%%" % COMPAT["EXTRA_WRAPPER"], (extra / "toolmanifest.vdf").read_text())
            self.assertIn('"/proton %verb%"', (extra / ("toolmanifest.vdf" + COMPAT["ORIGINAL_SUFFIX"])).read_text())
            self.assertIn('"$here/proton"', (extra / COMPAT["EXTRA_WRAPPER"]).read_text())
            self.assertIn("GE-Proton11-7", [entry["name"] for entry in COMPAT["tool_catalog"](str(home), None)])

    def test_droiddeck_proton_is_adopted_and_labelled_like_any_other_extra(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            (home / "label").write_text("Compatible\n")
            COMPAT["display_name"].__globals__["LABEL_FILE"] = str(home / "label")
            tools = home / "compatibilitytools.d"
            extra = tools / "DroidDeck-Proton 11-7.5"
            extra.mkdir(parents=True)
            (extra / "proton").write_text("")
            (extra / "toolmanifest.vdf").write_text('"manifest" { "version" "2" "commandline" "/proton %verb%" "require_tool_appid" "4185400" }')
            (extra / "compatibilitytool.vdf").write_text(
                '"compatibilitytools" { "compat_tools" { "DroidDeck-Proton-11-7.5" { "install_path" "." '
                '"display_name" "DroidDeck-Proton 11-7.5" "from_oslist" "windows" "to_oslist" "linux" } } }')
            COMPAT["build_tool"](str(tools / COMPAT["TOOL"]), None)
            protect = COMPAT["adopt_extras"](str(tools))
            self.assertEqual(protect, {"DroidDeck-Proton-11-7.5": "DroidDeck-Proton 11-7.5"})
            compat = (extra / "compatibilitytool.vdf").read_text()
            self.assertIn('"DroidDeck-Proton-11-7.5"', compat)
            self.assertIn('"display_name" "DroidDeck-Proton 11-7.5 (Compatible)"', compat)
            self.assertNotIn("require_tool_appid", (extra / "toolmanifest.vdf").read_text())
            self.assertIn('"$here/proton"', (extra / COMPAT["EXTRA_WRAPPER"]).read_text())
            catalog = COMPAT["tool_catalog"](str(home), None)
            self.assertIn({"name": "DroidDeck-Proton-11-7.5", "display": "DroidDeck-Proton 11-7.5 (Compatible)",
                           "dir": "DroidDeck-Proton 11-7.5", "valve": False}, catalog)
            config = home / "config.vdf"
            config.write_text(config_text([("0", COMPAT["TOOL"]), ("42", "DroidDeck-Proton-11-7.5")]))
            COMPAT["register_default"](str(config), ["42", "43"], protect=protect)
            self.assertEqual(mapping_of(config.read_text()), {"0": COMPAT["TOOL"], "42": "DroidDeck-Proton-11-7.5", "43": COMPAT["TOOL"]})
            changes, _ = COMPAT["plan_mapping"]({"0": COMPAT["TOOL"], "42": "DroidDeck-Proton-11-7.5"}, ["42"], [COMPAT["TOOL"]], COMPAT["TOOL"], {})
            self.assertEqual(changes, {"42": COMPAT["TOOL"]})

    def test_both_proton_wrappers_call_environment_launcher(self):
        self.assertIn('bl_directaudio "$depot" "$@"', COMPAT["LAUNCHER_SH"])
        self.assertIn('bl_directaudio "$here" "$@"', COMPAT["EXTRA_WRAPPER_SH"] % "proton")
        for script in (COMPAT["LAUNCHER_SH"], COMPAT["EXTRA_WRAPPER_SH"] % "proton"):
            subprocess.run(["bash", "-n"], input=script, text=True, check=True)
            self.assertIn('bl_run ${BL_TASKSET:-} /usr/local/bin/droiddeck-game-env', script)

    def test_wrappers_preload_the_session_library_before_the_input_shim(self):
        overlay = "/root/.local/share/Steam/ubuntu12_64/gameoverlayrenderer.so"
        with tempfile.TemporaryDirectory() as tmp:
            for name in ("libblsession.so", "libfakeinput.so"):
                (Path(tmp) / name).write_bytes(b"")
            for script in (COMPAT["LAUNCHER_SH"], COMPAT["EXTRA_WRAPPER_SH"] % "proton"):
                start = script.index("for fake in ")
                block = script[start:script.index("\ndone\n", start) + 6].replace("/usr/local/lib/", tmp + "/")
                for inherited, expected in (
                        (None, ["libblsession.so", "libfakeinput.so"]),
                        (overlay, ["libblsession.so", "libfakeinput.so", overlay]),
                        (tmp + "/libfakeinput.so:" + overlay, ["libblsession.so", "libfakeinput.so", overlay]),
                        (tmp + "/libblsession.so:" + tmp + "/libfakeinput.so", ["libblsession.so", "libfakeinput.so"])):
                    env = {"PATH": os.defpath}
                    if inherited is not None:
                        env["LD_PRELOAD"] = inherited
                    out = subprocess.run(["bash", "-c", block + 'printf %s "$LD_PRELOAD"'], env=env,
                                         capture_output=True, text=True, check=True).stdout
                    self.assertEqual([entry.replace(tmp + "/", "") for entry in out.split(":")], expected, inherited)


class ProtonDefaultTest(unittest.TestCase):
    TOOL = COMPAT["TOOL"]
    CATALOG = [
        {"name": COMPAT["TOOL"], "display": "Proton Experimental ARM64", "dir": "Proton Experimental (ARM64)", "valve": True},
        {"name": COMPAT["TOOL_11"], "display": "Proton 11.0 ARM64", "dir": "Proton 11.0 (ARM64)", "valve": True},
        {"name": "GE-Proton11-7", "display": "GE-Proton11-7", "dir": "GE-Proton11-7", "valve": False},
    ]
    NAMES = [t["name"] for t in CATALOG]

    def plan(self, current, installed, default, auto):
        return COMPAT["plan_mapping"](current, installed, self.NAMES, default, auto)

    def test_new_installs_and_valve_picks_follow_the_default(self):
        changes, auto = self.plan({"0": self.TOOL, "10": "proton_experimental", "11": "proton_11_arm64", "12": "GE-Proton11-7", "13": "steamlinuxruntime"},
                                  ["10", "11", "12", "13", "14"], "GE-Proton11-7", {})
        self.assertEqual(changes, {"0": "GE-Proton11-7", "10": "GE-Proton11-7", "11": "GE-Proton11-7", "14": "GE-Proton11-7"})
        self.assertEqual(auto, {"10": "GE-Proton11-7", "11": "GE-Proton11-7", "14": "GE-Proton11-7"})

    def test_windows_only_titles_still_follow_the_default(self):
        changes, auto = COMPAT["plan_mapping"](
            {"0": self.TOOL}, ["42"], self.NAMES, self.TOOL, {})
        self.assertEqual(changes, {"42": self.TOOL})
        self.assertEqual(auto, {"42": self.TOOL})

    def test_native_arm64_default_clears_only_our_auto_mapping(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = Path(tmp) / "config.vdf"
            config.write_text(config_text([("0", self.TOOL), ("42", self.TOOL)]))
            (Path(tmp) / "state.json").write_text(json.dumps({
                "default": self.TOOL,
                "auto": {"42": self.TOOL},
            }))
            globals_ = COMPAT["register_default"].__globals__
            original_native_targets = globals_["native_targets"]
            globals_["native_targets"] = lambda _root, _apps: {"42": None}
            try:
                COMPAT["register_default"](
                    str(config), ["42"], catalog=self.CATALOG, directory=tmp)
                first = config.read_bytes()
                self.assertEqual(mapping_of(first.decode()), {"0": self.TOOL})
                self.assertEqual(COMPAT["tokenize"](first.decode()),
                                 COMPAT["tokenize"](config_text([("0", self.TOOL)])))
                self.assertEqual(json.loads((Path(tmp) / "state.json").read_text())["auto"], {})

                COMPAT["register_default"](
                    str(config), ["42"], catalog=self.CATALOG, directory=tmp)
                self.assertEqual(config.read_bytes(), first)
            finally:
                globals_["native_targets"] = original_native_targets

    def test_saved_native_choice_survives_valve_pick_without_appinfo(self):
        app = "452280"
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            steam = root / "Steam"
            config = steam / "config/config.vdf"
            config.parent.mkdir(parents=True)
            (steam / "steamapps").mkdir()
            (steam / "steamapps" / ("appmanifest_%s.acf" % app)).write_text("")
            config.write_text(config_text([("0", self.TOOL)]))
            (root / "state.json").write_text(json.dumps({
                "default": self.TOOL,
                "auto": {},
                "native_choices": [app],
            }))

            globals_ = COMPAT["register_default"].__globals__
            original_native_targets = globals_["native_targets"]
            globals_["native_targets"] = lambda _root, _apps: {}
            try:
                COMPAT["register_default"](
                    str(config), [], catalog=self.CATALOG, directory=tmp, picks=[app])
                self.assertEqual(mapping_of(config.read_text()), {"0": self.TOOL})
                state = json.loads((root / "state.json").read_text())
                self.assertEqual(state["auto"], {})
                self.assertEqual(state["native_choices"], [app])
            finally:
                globals_["native_targets"] = original_native_targets

    def test_shortcuts_scanner_keeps_native_choice_for_unrelated_app_list(self):
        app, added_shortcut = "452280", "3537741571"
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            steam = root / "Steam"
            config = steam / "config/config.vdf"
            config.parent.mkdir(parents=True)
            (steam / "steamapps").mkdir()
            (steam / "steamapps" / ("appmanifest_%s.acf" % app)).write_text("")
            config.write_text(config_text([("0", self.TOOL)]))
            (root / "state.json").write_text(json.dumps({
                "default": self.TOOL,
                "auto": {},
                "native_choices": [app],
            }))

            globals_ = COMPAT["register_default"].__globals__
            original_native_targets = globals_["native_targets"]
            globals_["native_targets"] = lambda _root, wanted: {
                app: COMPAT["NATIVE_CHOICE"]
            } if app in wanted else {}
            try:
                COMPAT["register_default"](
                    str(config), [app], catalog=self.CATALOG, directory=tmp)
                self.assertEqual(json.loads((root / "state.json").read_text())["native_choices"], [app])

                COMPAT["register_default"](
                    str(config), [added_shortcut], catalog=self.CATALOG, directory=tmp)
                state = json.loads((root / "state.json").read_text())
                self.assertEqual(state["native_choices"], [app])
                self.assertEqual(state["auto"], {added_shortcut: self.TOOL})
                self.assertEqual(mapping_of(config.read_text()), {
                    "0": self.TOOL,
                    added_shortcut: self.TOOL,
                })
            finally:
                globals_["native_targets"] = original_native_targets

    def test_factorio_preserves_an_observed_native_choice_despite_ambiguous_metadata(self):
        with tempfile.TemporaryDirectory() as tmp:
            steam = Path(tmp) / "Steam"
            config = steam / "config/config.vdf"
            config.parent.mkdir(parents=True)
            config.write_text(config_text([("0", self.TOOL)]))
            state = {"default": self.TOOL, "auto": {}}
            (Path(tmp) / "state.json").write_text(json.dumps(state))

            globals_ = COMPAT["native_targets"].__globals__
            original_platforms = globals_["appinfo_platforms"]
            globals_["appinfo_platforms"] = lambda _path, wanted: {
                "427520": ({"linux", "macos", "windows"}, "", "game")
            } if "427520" in wanted else {}
            try:
                native = COMPAT["native_targets"](str(steam), ["427520"])
                self.assertIs(native["427520"], COMPAT["NATIVE_CHOICE"])

                changes, auto = COMPAT["plan_mapping"](
                    {"0": self.TOOL}, ["427520"], self.NAMES, self.TOOL, {}, native)
                self.assertEqual(changes, {"427520": self.TOOL})
                self.assertEqual(auto, {"427520": self.TOOL})

                COMPAT["register_default"](
                    str(config), ["427520"], catalog=self.CATALOG, directory=tmp)
                self.assertEqual(mapping_of(config.read_text()), {"0": self.TOOL, "427520": self.TOOL})

                state["auto"] = {"427520": self.TOOL}
                (Path(tmp) / "state.json").write_text(json.dumps(state))
                config.write_text(config_text([("0", self.TOOL)]))
                COMPAT["register_default"](
                    str(config), ["427520"], catalog=self.CATALOG, directory=tmp)
                first = config.read_bytes()
                self.assertEqual(mapping_of(first.decode()), {"0": self.TOOL})
                self.assertEqual(COMPAT["tokenize"](first.decode()),
                                 COMPAT["tokenize"](config_text([("0", self.TOOL)])))
                self.assertEqual(json.loads((Path(tmp) / "state.json").read_text())["auto"], {})
                self.assertEqual(json.loads((Path(tmp) / "state.json").read_text())["native_choices"], ["427520"])

                COMPAT["register_default"](
                    str(config), ["427520"], catalog=self.CATALOG, directory=tmp)
                self.assertEqual(config.read_bytes(), first)

                config.write_text(config_text([("0", self.TOOL), ("427520", "")]))
                COMPAT["register_default"](
                    str(config), ["427520"], catalog=self.CATALOG, directory=tmp)
                native_choice = config.read_bytes()
                self.assertEqual(mapping_of(native_choice.decode()), {"0": self.TOOL, "427520": ""})
                self.assertEqual(json.loads((Path(tmp) / "state.json").read_text())["auto"], {})
                self.assertEqual(json.loads((Path(tmp) / "state.json").read_text())["native_choices"], ["427520"])
                COMPAT["register_default"](
                    str(config), ["427520"], catalog=self.CATALOG, directory=tmp)
                self.assertEqual(config.read_bytes(), native_choice)

                for choice in (COMPAT["FEX_TOOL"], COMPAT["TOOL_11"], "GE-Proton11-7"):
                    config.write_text(config_text([("0", self.TOOL), ("427520", choice)]))
                    COMPAT["register_default"](
                        str(config), ["427520"], catalog=self.CATALOG, directory=tmp)
                    self.assertEqual(mapping_of(config.read_text()), {"0": self.TOOL, "427520": choice})
                    self.assertEqual(json.loads((Path(tmp) / "state.json").read_text())["native_choices"], [])

                state = json.loads((Path(tmp) / "state.json").read_text())
                state["native_choices"] = ["427520"]
                state["auto"] = {}
                (Path(tmp) / "state.json").write_text(json.dumps(state))
                config.write_text(config_text([("0", self.TOOL), ("427520", "proton_experimental")]))
                COMPAT["register_default"](
                    str(config), ["427520"], catalog=self.CATALOG, directory=tmp)
                self.assertEqual(mapping_of(config.read_text()), {"0": self.TOOL, "427520": self.TOOL})
                saved = json.loads((Path(tmp) / "state.json").read_text())
                self.assertEqual(saved["native_choices"], [])
                self.assertEqual(saved["auto"], {"427520": self.TOOL})
            finally:
                globals_["appinfo_platforms"] = original_platforms

    def test_native_arm64_preserves_a_proton_mapping_without_managed_state(self):
        changes, auto = COMPAT["plan_mapping"](
            {"0": self.TOOL, "42": self.TOOL}, ["42"], self.NAMES, self.TOOL,
            {"42": self.TOOL}, {"42": None}, managed=())
        self.assertEqual(changes, {})
        self.assertEqual(auto, {})

    def test_native_arm64_keeps_a_chosen_valve_proton_through_its_wrapper(self):
        changes, auto = COMPAT["plan_mapping"](
            {"0": self.TOOL, "42": "proton_experimental"}, ["42"], self.NAMES,
            self.TOOL, {}, {"42": None})
        self.assertEqual(changes, {"42": self.TOOL})
        self.assertEqual(auto, {})

        changes, auto = COMPAT["plan_mapping"](
            {"0": self.TOOL, "42": self.TOOL}, ["42"], self.NAMES,
            self.TOOL, {}, {"42": None})
        self.assertEqual(changes, {})
        self.assertEqual(auto, {})

    def test_native_arm64_keeps_explicit_runtime_fex_and_proton_profiles(self):
        fex = COMPAT["FEX_TOOL"]
        current = {
            "0": self.TOOL,
            "42": COMPAT["TOOL_11"],
            "43": "GE-Proton11-7",
            "44": "steamlinuxruntime_arm64",
            "45": fex,
            "46": "",
        }
        changes, auto = COMPAT["plan_mapping"](
            current, ["42", "43", "44", "45", "46"], self.NAMES, self.TOOL, {},
            {app: None for app in ("42", "43", "44", "45", "46")},
            overrides={"46": "GE-Proton11-7"},
        )
        self.assertEqual(changes, {"46": "GE-Proton11-7"})
        self.assertEqual(auto, {})

    def test_titles_following_the_default_move_with_it_and_picks_stay(self):
        current = {"0": self.TOOL, "10": self.TOOL, "11": "GE-Proton11-7", "12": self.TOOL}
        auto = {"10": self.TOOL, "11": self.TOOL, "12": self.TOOL}
        changes, auto = self.plan(current, ["10", "11", "12"], COMPAT["TOOL_11"], auto)
        self.assertEqual(changes, {"0": COMPAT["TOOL_11"], "10": COMPAT["TOOL_11"], "12": COMPAT["TOOL_11"]})
        self.assertEqual(auto, {"10": COMPAT["TOOL_11"], "12": COMPAT["TOOL_11"]})

    def test_a_removed_tool_falls_back_to_the_default(self):
        changes, auto = self.plan({"0": self.TOOL, "10": "GE-Proton10-1", "11": "droiddeck-proton-12-arm64"}, ["10", "11"], self.TOOL, {"10": "GE-Proton10-1"})
        self.assertEqual(changes, {"10": self.TOOL, "11": self.TOOL})
        self.assertEqual(auto, {"10": self.TOOL, "11": self.TOOL})

    def test_uninstalled_titles_are_forgotten(self):
        changes, auto = self.plan({"0": self.TOOL}, [], self.TOOL, {"10": self.TOOL})
        self.assertEqual((changes, auto), ({}, {}))

    def test_per_game_proton_override_and_inherit_follow_steam_mappings(self):
        current = {"0": self.TOOL, "10": self.TOOL, "11": "GE-Proton11-7", "12": self.TOOL}
        overrides = {"10": "GE-Proton11-7", "11": None}
        changes, auto = COMPAT["plan_mapping"](
            current, ["10", "11", "12"], self.NAMES, COMPAT["TOOL_11"],
            {"10": self.TOOL, "12": self.TOOL}, overrides=overrides)
        self.assertEqual(changes, {"0": COMPAT["TOOL_11"], "10": "GE-Proton11-7", "11": COMPAT["TOOL_11"], "12": COMPAT["TOOL_11"]})
        self.assertEqual(auto, {"11": COMPAT["TOOL_11"], "12": COMPAT["TOOL_11"]})

    def test_per_game_requests_resolve_identity_and_defer_a_new_live_tool(self):
        requests = {
            "10": {"valve": False, "dir": "GE-Proton11-7"},
            "11": None,
            "12": {"valve": True, "dir": "Proton 11.0 (ARM64)"},
            "0": None,
        }
        self.assertEqual(
            COMPAT["requested_games"](requests, self.CATALOG),
            {"10": "GE-Proton11-7", "11": None, "12": COMPAT["TOOL_11"]},
        )
        self.assertEqual(
            COMPAT["requested_games"](requests, self.CATALOG, [self.TOOL, COMPAT["TOOL_11"]]),
            {"10": COMPAT["DEFERRED_GAME_TOOL"], "11": None, "12": COMPAT["TOOL_11"]},
        )

    def test_missing_per_game_proton_is_preserved_during_default_registration(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = Path(tmp) / "config.vdf"
            config.write_text(config_text([
                ("0", self.TOOL),
                ("10", "GE-Proton9-1"),
                ("11", self.TOOL),
            ]))
            (Path(tmp) / "games.json").write_text(json.dumps({
                "version": 1,
                "games": {
                    "10": {"valve": False, "dir": "GE-Proton9-1"},
                },
            }))

            COMPAT["register_default"](
                str(config), ["10", "11"], default=COMPAT["TOOL_11"],
                catalog=self.CATALOG, directory=tmp,
            )

            self.assertEqual(mapping_of(config.read_text()), {
                "0": COMPAT["TOOL_11"],
                "10": "GE-Proton9-1",
                "11": COMPAT["TOOL_11"],
            })

    def test_the_last_side_to_change_wins(self):
        state = {"default": self.TOOL, "applied": 5}
        default, state = COMPAT["choose_default"]("GE-Proton11-7", state, {"seq": 5, "dir": "Proton 11.0 (ARM64)", "valve": True}, self.CATALOG, now=100)
        self.assertEqual((default, state["source"], state["seq"]), ("GE-Proton11-7", "steam", 100))
        default, state = COMPAT["choose_default"]("GE-Proton11-7", state, {"seq": 6, "dir": "Proton 11.0 (ARM64)", "valve": True}, self.CATALOG, now=200)
        self.assertEqual((default, state["source"], state["seq"], state["applied"]), (COMPAT["TOOL_11"], "app", 200, 6))
        default, state = COMPAT["choose_default"]("GE-Proton11-7", state, {"seq": 6}, self.CATALOG, adopt_steam=False, now=300)
        self.assertEqual((default, state["seq"]), (COMPAT["TOOL_11"], 200))
        default, state = COMPAT["choose_default"]("proton_experimental", state, {"seq": 6}, self.CATALOG, now=300)
        self.assertEqual(default, COMPAT["TOOL_11"])

    def test_a_request_for_a_missing_tool_is_dropped(self):
        default, state = COMPAT["choose_default"](self.TOOL, {"default": self.TOOL}, {"seq": 9, "dir": "GE-Proton9-1", "valve": False}, self.CATALOG, now=1)
        self.assertEqual((default, state["applied"]), (self.TOOL, 9))
        default, state = COMPAT["choose_default"]("GE-Proton11-7", state, {"seq": 9, "dir": "GE-Proton9-1", "valve": False}, self.CATALOG, now=2)
        self.assertEqual(default, "GE-Proton11-7")

    def test_settle_before_the_client_starts(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = Path(tmp) / "config.vdf"
            config.write_text(config_text([("0", self.TOOL), ("10", "proton_experimental"), ("11", "GE-Proton11-7")]))
            (Path(tmp) / "request.json").write_text(json.dumps({"seq": 3, "dir": "Proton 11.0 (ARM64)", "valve": True}))
            default = COMPAT["register_default"](str(config), ["10", "11", "12"], catalog=self.CATALOG, directory=tmp, settle=True)
            self.assertEqual(default, COMPAT["TOOL_11"])
            self.assertEqual(mapping_of(config.read_text()), {"0": COMPAT["TOOL_11"], "10": COMPAT["TOOL_11"], "11": "GE-Proton11-7", "12": COMPAT["TOOL_11"]})
            state = json.loads((Path(tmp) / "state.json").read_text())
            self.assertEqual((state["applied"], state["source"], state["tools"]), (3, "app", self.CATALOG))
            config.write_text(config_text([("0", "GE-Proton11-7"), ("10", COMPAT["TOOL_11"]), ("11", "GE-Proton11-7"), ("12", COMPAT["TOOL_11"])]))
            default = COMPAT["register_default"](str(config), ["10", "11", "12"], catalog=self.CATALOG, directory=tmp, settle=True)
            self.assertEqual(default, "GE-Proton11-7")
            self.assertEqual(mapping_of(config.read_text())["10"], "GE-Proton11-7")
            self.assertEqual(json.loads((Path(tmp) / "state.json").read_text())["source"], "steam")
            config.write_text(config_text([("0", "proton_experimental")]))
            default = COMPAT["register_default"](str(config), ["10"], catalog=self.CATALOG, directory=tmp)
            self.assertEqual(mapping_of(config.read_text()), {"0": "GE-Proton11-7", "10": "GE-Proton11-7"})

    def test_the_shortcut_writer_keeps_the_chosen_default(self):
        with tempfile.TemporaryDirectory() as tmp:
            steam = Path(tmp) / "Steam"
            for name in COMPAT["SOURCES"]:
                (steam / "steamapps/common" / name / "files/bin-arm64").mkdir(parents=True)
            (steam / "config").mkdir()
            config = steam / "config/config.vdf"
            config.write_text(config_text([("0", COMPAT["TOOL_11"]), ("10", COMPAT["TOOL_11"]), ("3044416433", COMPAT["TOOL_11"])]))
            (Path(tmp) / "state.json").write_text(json.dumps({"default": COMPAT["TOOL_11"], "auto": {"10": COMPAT["TOOL_11"], "3044416433": COMPAT["TOOL_11"]}}))
            COMPAT["register_default"](str(config), apps=["3044416433", "3212965118"], protect={}, directory=tmp)
            self.assertEqual(mapping_of(config.read_text()), {"0": COMPAT["TOOL_11"], "10": COMPAT["TOOL_11"], "3044416433": COMPAT["TOOL_11"], "3212965118": COMPAT["TOOL_11"]})

    def test_each_valve_depot_gets_its_own_tool(self):
        with tempfile.TemporaryDirectory() as tmp:
            steam = Path(tmp) / "Steam"
            for name in COMPAT["SOURCES"]:
                (steam / "steamapps/common" / name / "files/bin-arm64").mkdir(parents=True)
            self.assertEqual(COMPAT["find_source"](str(steam), COMPAT["OWN_TOOLS"][1][1]), str(steam / "steamapps/common/Proton 11.0 (ARM64)"))
            self.assertEqual(COMPAT["find_source"](str(steam)), str(steam / "steamapps/common/Proton Experimental (ARM64)"))
            self.assertEqual([(t["name"], t["dir"]) for t in COMPAT["tool_catalog"](str(steam), {})],
                             [(COMPAT["TOOL"], "Proton Experimental (ARM64)"), (COMPAT["TOOL_11"], "Proton 11.0 (ARM64)")])
            for name, sources in COMPAT["OWN_TOOLS"]:
                COMPAT["build_tool"](str(steam / "compatibilitytools.d" / name), COMPAT["find_source"](str(steam), sources), sources, name)
            self.assertEqual(COMPAT["adopt_extras"](str(steam / "compatibilitytools.d")), {})
            self.assertEqual(sorted(os.listdir(steam / "compatibilitytools.d" / COMPAT["TOOL_11"])), sorted(COMPAT["OWN_FILES"]))
            launcher = COMPAT["launcher_sh"](COMPAT["OWN_TOOLS"][1][1])
            self.assertIn('for name in "Proton 11.0 (ARM64)"; do', launcher)
            self.assertIn("${major%%.*}", launcher)



class DirectAudioPrefixTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        base = Path(self.tmp.name)
        self.tool = base / "tool"
        (self.tool / "files/bin-arm64").mkdir(parents=True)
        wine = self.tool / "files/bin-arm64/wine"
        wine.write_text("#!/bin/sh\necho wine-11.0-4c8f2e1 '(Staging)'\n")
        wine.chmod(0o755)
        (self.tool / "files/lib/wine/aarch64-unix").mkdir(parents=True)
        self.winepulse = self.tool / "files/lib/wine/aarch64-unix/winepulse.so"
        self.winepulse.write_bytes(b"\x7fELF classic wine 11 pulse driver")
        self.sets = base / "directaudio"
        for set_name in ("linux-wine11", "linux-wine11-systhread"):
            for arch in ("aarch64-windows", "i386-windows", "aarch64-unix"):
                (self.sets / set_name / "lib/wine" / arch).mkdir(parents=True)
            (self.sets / set_name / "lib/wine/aarch64-windows/winedirectaudio.drv").write_bytes(b"arm64x-" + set_name.encode())
            (self.sets / set_name / "lib/wine/i386-windows/winedirectaudio.drv").write_bytes(b"i386")
        self.audio = self.sets / "linux-wine11"
        self.compat = base / "compat"
        self.windows = self.compat / "pfx/drive_c/windows"
        for folder in ("system32", "syswow64"):
            (self.windows / folder).mkdir(parents=True)
        self.reg = self.compat / "pfx/user.reg"

    def run_setup(self, verb="waitforexitandrun", target="game.exe", cwd=None):
        script = COMPAT["BL_DIRECTAUDIO_SETUP"] + '\nverb=$2\nbl_directaudio "$1" "$3"\necho "WINEDLLPATH=$WINEDLLPATH"\necho "WINE_AUDIO_DRIVER=${WINE_AUDIO_DRIVER:-}"\n'
        steam = self.compat.parent / "Steam"
        env = dict(os.environ, BL_DIRECTAUDIO=str(self.sets), STEAM_COMPAT_DATA_PATH=str(self.compat),
                   STEAM_COMPAT_CLIENT_INSTALL_PATH=str(steam))
        env.pop("WINEDLLPATH", None)
        env.pop("WINE_AUDIO_DRIVER", None)
        return subprocess.run(["bash", "-c", script, "bash", str(self.tool), verb, target], cwd=cwd, env=env,
                              capture_output=True, text=True, check=True)

    def audio_values(self):
        found, current = [], None
        for line in self.reg.read_text().splitlines():
            if line.startswith("["):
                current = line.rsplit("] ", 1)[0] + "]"
            elif line.startswith('"Audio"='):
                found.append((current, line))
        return found

    def link(self, folder):
        path = self.windows / folder / "winedirectaudio.drv"
        return os.readlink(path) if path.is_symlink() else None

    def test_steam_installscript_evaluator_does_not_change_prefix_audio(self):
        legacycompat = self.compat.parent / "Steam/legacycompat"
        legacycompat.mkdir(parents=True)
        (legacycompat / "iscriptevaluator.exe").write_bytes(b"Steam install-script evaluator")
        self.reg.write_text("WINE REGISTRY Version 2\n\n#arch=win64\n")

        result = self.run_setup(target="iscriptevaluator.exe", cwd=legacycompat)

        self.assertIn("DirectAudio skipped for Steam's install-script evaluator", result.stderr)
        self.assertIn("WINEDLLPATH=\n", result.stdout)
        self.assertIn("WINE_AUDIO_DRIVER=\n", result.stdout)
        self.assertEqual(self.reg.read_text(), "WINE REGISTRY Version 2\n\n#arch=win64\n")
        self.assertIsNone(self.link("system32"))
        self.assertIsNone(self.link("syswow64"))

    def test_same_basename_outside_steam_legacycompat_keeps_normal_setup(self):
        legacycompat = self.compat.parent / "Steam/legacycompat"
        legacycompat.mkdir(parents=True)
        (legacycompat / "iscriptevaluator.exe").write_bytes(b"Steam install-script evaluator")
        other = self.compat.parent / "other"
        other.mkdir()
        (other / "iscriptevaluator.exe").write_bytes(b"unrelated executable")
        self.reg.write_text("WINE REGISTRY Version 2\n\n#arch=win64\n")

        result = self.run_setup(target="iscriptevaluator.exe", cwd=other)

        self.assertIn("DirectAudio selected", result.stderr)
        self.assertEqual(self.link("system32"), str(self.audio / "lib/wine/aarch64-windows/winedirectaudio.drv"))
        self.assertEqual(self.audio_values(), [("[Software\\\\Wine\\\\Drivers]", '"Audio"="directaudio,pulse"')])
    def test_non_launch_verbs_preserve_audio_configuration(self):
        for verb in ("getcompatpath", "getnativepath", "destroyprefix"):
            with self.subTest(verb=verb):
                self.reg.write_text('WINE REGISTRY Version 2\n\n[Software\\\\Wine\\\\Drivers] 1\n"Audio"="pulse"\n')
                before = self.reg.read_bytes()
                result = self.run_setup(verb)
                self.assertEqual(self.reg.read_bytes(), before)
                self.assertIsNone(self.link("system32"))
                self.assertIsNone(self.link("syswow64"))
                self.assertIn("WINEDLLPATH=\n", result.stdout)
                self.assertIn("WINE_AUDIO_DRIVER=\n", result.stdout)

    def test_other_windows_launch_verbs_still_set_up_audio(self):
        self.reg.write_text("WINE REGISTRY Version 2\n")
        for verb in ("run", "runinprefix"):
            with self.subTest(verb=verb):
                result = self.run_setup(verb)
                self.assertIn("DirectAudio ready", result.stderr)
                self.assertIn("WINE_AUDIO_DRIVER=directaudio,pulse\n", result.stdout)
                self.assertEqual(self.link("system32"), str(self.audio / "lib/wine/aarch64-windows/winedirectaudio.drv"))

    def test_the_driver_is_placed_and_selected_the_way_wine_reads_it(self):
        self.reg.write_text("WINE REGISTRY Version 2\n;; All keys relative to \\\\User\\\\S-1-5-21-0-0-0-1000\n\n#arch=win64\n")
        result = self.run_setup()
        self.assertIn("DirectAudio selected", result.stderr)
        self.assertIn("WINEDLLPATH=%s/lib/wine" % self.audio, result.stdout)
        self.assertIn("WINE_AUDIO_DRIVER=directaudio,pulse\n", result.stdout)
        self.assertEqual(self.link("system32"), str(self.audio / "lib/wine/aarch64-windows/winedirectaudio.drv"))
        self.assertEqual(self.link("syswow64"), str(self.audio / "lib/wine/i386-windows/winedirectaudio.drv"))
        self.assertEqual((self.windows / "system32/winedirectaudio.drv").read_bytes(), b"arm64x-linux-wine11")
        self.assertEqual(self.audio_values(), [("[Software\\\\Wine\\\\Drivers]", '"Audio"="directaudio,pulse"')])
        before = self.reg.read_text()
        self.assertNotIn("DirectAudio selected", self.run_setup().stderr)
        self.assertEqual(self.reg.read_text(), before)
        self.assertEqual(sorted(os.listdir(self.windows / "system32")), ["winedirectaudio.drv"])

    def test_without_a_prefix_it_waits_for_the_next_launch(self):
        result = self.run_setup()
        self.assertIn("takes effect the next time this game starts", result.stderr)
        self.assertEqual(self.link("system32"), None)

    def test_the_system_thread_set_is_picked_from_winepulse_not_the_version(self):
        self.reg.write_text("WINE REGISTRY Version 2\n\n#arch=win64\n")
        self.winepulse.write_bytes(b"\x7fELF cachyos pulse driver PsCreateSystemThread NtSetInformationThread")
        result = self.run_setup()
        systhread = self.sets / "linux-wine11-systhread"
        self.assertIn("DirectAudio ready (Wine 11, linux-wine11-systhread)", result.stderr)
        self.assertIn("WINEDLLPATH=%s/lib/wine" % systhread, result.stdout)
        self.assertEqual(self.link("system32"), str(systhread / "lib/wine/aarch64-windows/winedirectaudio.drv"))
        self.assertEqual((self.windows / "system32/winedirectaudio.drv").read_bytes(), b"arm64x-linux-wine11-systhread")

    def test_no_set_for_the_interface_leaves_protons_own_audio(self):
        self.reg.write_text("WINE REGISTRY Version 2\n\n#arch=win64\n")
        self.winepulse.write_bytes(b"\x7fELF PsCreateSystemThread")
        shutil.rmtree(self.sets / "linux-wine11-systhread")
        result = self.run_setup()
        self.assertIn("no driver set for the linux-wine11-systhread interface", result.stderr)
        self.assertIn("WINEDLLPATH=\n", result.stdout)
        self.assertIn("WINE_AUDIO_DRIVER=\n", result.stdout)
        self.assertFalse((self.windows / "system32/winedirectaudio.drv").exists())

    def test_older_selections_are_upgraded(self):
        self.reg.write_text('WINE REGISTRY Version 2\n\n[SoftwareWineDrivers] 1790995967\n#time=1dd52e24550e980\n"Audio"="directaudio"\n'
                            '\n[Software\\\\Wine\\\\Drivers] 1790995986\n#time=1dd52e250b31ec4\n"Audio"="directaudio"\n')
        self.assertIn("DirectAudio selected", self.run_setup().stderr)
        self.assertEqual(self.audio_values(), [("[SoftwareWineDrivers]", '"Audio"="directaudio"'),
                                               ("[Software\\\\Wine\\\\Drivers]", '"Audio"="directaudio"'),
                                               ("[Software\\\\Wine\\\\Drivers]", '"Audio"="directaudio,pulse"')])
        self.assertNotIn("DirectAudio selected", self.run_setup().stderr)

    def test_a_copied_driver_is_replaced_by_the_staged_one(self):
        self.reg.write_text("WINE REGISTRY Version 2\n")
        (self.windows / "system32/winedirectaudio.drv").write_bytes(b"stale copy")
        self.run_setup()
        self.assertEqual(self.link("system32"), str(self.audio / "lib/wine/aarch64-windows/winedirectaudio.drv"))

    def test_nothing_is_selected_without_the_driver_in_the_prefix(self):
        self.reg.write_text("WINE REGISTRY Version 2\n")
        shutil.rmtree(self.windows)
        self.assertIn("could not be placed", self.run_setup().stderr)
        self.assertEqual(self.audio_values(), [])
        (self.windows / "system32").mkdir(parents=True)
        (self.audio / "lib/wine/aarch64-windows/winedirectaudio.drv").unlink()
        self.assertIn("could not be placed", self.run_setup().stderr)
        self.assertEqual(self.audio_values(), [])

    def test_a_new_prefix_waits_for_the_next_launch(self):
        shutil.rmtree(self.compat / "pfx")
        self.assertIn("no prefix yet", self.run_setup().stderr)
        self.assertFalse(self.reg.exists())

    def test_another_wine_major_is_left_alone(self):
        (self.tool / "files/bin-arm64/wine").write_text("#!/bin/sh\necho wine-10.0\n")
        self.reg.write_text("WINE REGISTRY Version 2\n")
        result = self.run_setup()
        self.assertIn("left off", result.stderr)
        self.assertIn("WINEDLLPATH=\n", result.stdout)
        self.assertEqual(self.audio_values(), [])
        self.assertIsNone(self.link("system32"))


class UbisoftOverlayTest(unittest.TestCase):
    SETTINGS = (
        "language:\n  code: en-US\n"
        "overlay:\n  enabled: true\n  forceunhookgame: false\n  fps_enabled: false\n  warning_enabled: true\n"
        "patchTrigger:\n  source: USER_RESTART\n"
    )

    def test_the_overlay_and_its_prompt_are_turned_off_and_the_rest_is_kept(self):
        got = MODULE["overlay_off"](self.SETTINGS)
        self.assertEqual(got, self.SETTINGS.replace("enabled: true", "enabled: false"))
        self.assertEqual(MODULE["overlay_off"](got), got)

    def test_missing_keys_or_section_are_added(self):
        self.assertEqual(MODULE["overlay_off"]("overlay:\n  fps_enabled: true\nmisc:\n  a: 1\n"),
                         "overlay:\n  fps_enabled: true\n  enabled: false\n  warning_enabled: false\nmisc:\n  a: 1\n")
        self.assertEqual(MODULE["overlay_off"](""), "overlay:\n  enabled: false\n  warning_enabled: false\n")

    def test_blank_lines_inside_the_overlay_section_do_not_end_it(self):
        text = "overlay:\n  enabled: true\n\n  warning_enabled: true\n\nmisc:\n  a: 1\n"
        self.assertEqual(MODULE["overlay_off"](text), text.replace("true", "false"))
        self.assertEqual(MODULE["overlay_off"]("overlay:\n  fps_enabled: true\n\nmisc:\n  a: 1\n"),
                         "overlay:\n  fps_enabled: true\n  enabled: false\n  warning_enabled: false\n\nmisc:\n  a: 1\n")

    def test_an_annotated_overlay_header_is_the_same_section(self):
        self.assertEqual(MODULE["overlay_off"]("overlay:  # in-game\n  enabled: true\n"),
                         "overlay:  # in-game\n  enabled: false\n  warning_enabled: false\n")

    def test_only_prefixes_with_ubisoft_connect_are_touched(self):
        with tempfile.TemporaryDirectory() as tmp:
            prefix = Path(tmp) / "447040/pfx"
            env = {"STEAM_COMPAT_DATA_PATH": str(prefix.parent)}
            settings = prefix / MODULE["UBISOFT_SETTINGS"]
            prefix.mkdir(parents=True)
            MODULE["ubisoft_overlay_off"](env)
            self.assertFalse(settings.exists())
            (prefix / MODULE["UBISOFT_LAUNCHER"]).mkdir(parents=True)
            MODULE["ubisoft_overlay_off"](env)
            self.assertEqual(settings.read_text(), "overlay:\n  enabled: false\n  warning_enabled: false\n")
            settings.write_text(self.SETTINGS)
            settings.chmod(0o644)
            MODULE["ubisoft_overlay_off"](env)
            self.assertEqual(settings.read_text(), self.SETTINGS.replace("enabled: true", "enabled: false"))
            self.assertEqual(settings.stat().st_mode & 0o777, 0o644)
            self.assertEqual(sorted(p.name for p in settings.parent.iterdir()), ["settings.yaml"])


if __name__ == "__main__":
    unittest.main()


class ForceSsbsTest(unittest.TestCase):
    def test_libssbs_is_preloaded_only_when_asked_and_only_into_arm64_proton(self):
        with tempfile.TemporaryDirectory() as tmp:
            arm = Path(tmp) / "arm"
            (arm / "files/lib/wine/aarch64-unix").mkdir(parents=True)
            x86 = Path(tmp) / "x86"
            (x86 / "files/lib/wine/x86_64-unix").mkdir(parents=True)
            lib = Path(tmp) / "libssbs.so"
            lib.write_bytes(b"\x7fELF")
            g = MODULE["force_ssbs"].__globals__
            old = g["SSBS_LIB"]
            g["SSBS_LIB"] = str(lib)
            try:
                env = {"LD_PRELOAD": "/usr/local/lib/libblsession.so"}
                self.assertEqual(MODULE["force_ssbs"]([str(arm / "proton"), "waitforexitandrun"], env), env)
                on = dict(env, DROIDDECK_FORCE_SSBS="1")
                got = MODULE["force_ssbs"]([str(arm / "proton"), "waitforexitandrun"], on)
                self.assertEqual(got["LD_PRELOAD"], "/usr/local/lib/libblsession.so:" + str(lib))
                self.assertEqual(MODULE["force_ssbs"]([str(arm / "proton")], got)["LD_PRELOAD"], got["LD_PRELOAD"])
                self.assertEqual(MODULE["force_ssbs"]([str(x86 / "proton")], on), on)
            finally:
                g["SSBS_LIB"] = old
