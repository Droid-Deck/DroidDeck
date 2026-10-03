import json
import os
from pathlib import Path
import runpy
import subprocess
import sys
import tempfile
import unittest

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
MODULE = runpy.run_path(str(BIN / "bannerlator-game-env"))
COMPAT = runpy.run_path(str(BIN / "bannerlator-steam-compat"))


class GameEnvironmentTest(unittest.TestCase):
    def test_profile_precedence_and_unset(self):
        env = {"KEEP": "inherited", "REMOVE": "inherited", "CUSTOM": "launch option"}
        config = {"version": 1, "shared": {"CUSTOM": "shared"}, "games": {"42": {"REMOVE": None, "CUSTOM": "game", "EMPTY": ""}}}
        self.assertEqual(MODULE["apply_config"](env, config, "42"), {"KEEP": "inherited", "CUSTOM": "game", "EMPTY": ""})
        self.assertEqual(env["REMOVE"], "inherited")
        self.assertEqual(MODULE["apply_config"](env, config, "43")["CUSTOM"], "shared")

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
                result = subprocess.check_output([sys.executable, str(BIN / "bannerlator-game-env"), str(probe), "waitforexitandrun", "path with spaces", "a=b"], env=env, text=True)
                self.assertEqual(json.loads(result), [value, ["waitforexitandrun", "path with spaces", "a=b"]])
            self.assertFalse((home / "injected").exists())
            for verb, prefix in (("run", "/compatdata/42"), ("waitforexitandrun", "/compatdata/0")):
                output = subprocess.check_output([sys.executable, str(BIN / "bannerlator-game-env"), str(probe), verb], env={**env, "STEAM_COMPAT_DATA_PATH": prefix, "CUSTOM": "original"}, text=True)
                self.assertEqual(json.loads(output)[0], "original")
            config.write_text("{broken")
            result = subprocess.run([sys.executable, str(BIN / "bannerlator-game-env"), str(probe), "waitforexitandrun"], env={**env, "CUSTOM": "original"}, text=True, capture_output=True, check=True)
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
            COMPAT["build_tool"](str(tools / COMPAT["TOOL"]))
            COMPAT["adopt_extras"](str(tools))
            config = home / ".config/droiddeck/game-environment.json"
            config.parent.mkdir(parents=True)
            config.write_text(json.dumps({"version": 1, "shared": {"CUSTOM": "shared"}, "games": {"42": {"CUSTOM": "specific"}}}))
            wrappers = (tools / COMPAT["TOOL"] / COMPAT["LAUNCHER"], extra / COMPAT["EXTRA_WRAPPER"])
            for wrapper in wrappers:
                wrapper.write_text(wrapper.read_text().replace("/usr/local/bin/bannerlator-game-env", str(BIN / "bannerlator-game-env")))
                result = subprocess.run([str(wrapper), "waitforexitandrun", "game with spaces.exe"],
                    env={"PATH": os.defpath, "HOME": tmp, "STEAM_COMPAT_DATA_PATH": "/compatdata/42", "STEAM_COMPAT_CLIENT_INSTALL_PATH": str(steam)},
                    text=True, capture_output=True)
                self.assertEqual(result.returncode, 7, result.stderr)
                self.assertEqual(json.loads(result.stdout), ["specific", ["waitforexitandrun", "game with spaces.exe"]])

    def test_both_proton_wrappers_call_environment_launcher(self):
        for script in (COMPAT["LAUNCHER_SH"], COMPAT["EXTRA_WRAPPER_SH"] % "proton"):
            subprocess.run(["bash", "-n"], input=script, text=True, check=True)
            self.assertIn('exec ${BL_TASKSET:-} /usr/local/bin/bannerlator-game-env', script)


if __name__ == "__main__":
    unittest.main()
