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

    def test_known_fixes_sit_between_shared_and_game_entries(self):
        config = {"version": 1,
                  "shared": {"FEX_HALFBARRIERTSOENABLED": "0", "FEX_TSOENABLED": "1", "WINEDLLOVERRIDES": "dxgi=n"},
                  "games": {"2868840": {"DOTNET_EnableWriteXorExecute": "1"}}}
        env = MODULE["apply_config"]({"WINEDLLOVERRIDES": "d3d9=n"}, config, "2868840")
        # Slay the Spire 2: the .NET entries, and tabtip off ahead of the overrides the shared entries
        # chose; the user's own entry for the game beats the fix; nothing else is added.
        self.assertEqual(env, {"FEX_HALFBARRIERTSOENABLED": "0", "FEX_TSOENABLED": "1",
                               "WINEDLLOVERRIDES": "tabtip.exe=d;dxgi=n", "DOTNET_GCHeapHardLimit": "0x400000000",
                               "DOTNET_EnableWriteXorExecute": "1", "DOTNET_TieredCompilation": "0"})
        # Another title gets the shared entries only.
        self.assertEqual(MODULE["apply_config"]({}, config, "42"),
                         {"FEX_HALFBARRIERTSOENABLED": "0", "FEX_TSOENABLED": "1", "WINEDLLOVERRIDES": "dxgi=n"})
        # The three layers, with a fix that sets entries: the fix beats the shared FEX preset, the
        # user's own entry for the game beats the fix, and a user entry can take a fix's entry away.
        fixes = MODULE["KNOWN_FIXES"]
        fixes["42"] = {"env": {"FEX_HALFBARRIERTSOENABLED": "1", "DOTNET_TieredCompilation": "0"}}
        try:
            config["games"]["42"] = {"DOTNET_TieredCompilation": None, "FEX_TSOENABLED": "0"}
            self.assertEqual(MODULE["apply_config"]({}, config, "42"),
                             {"FEX_HALFBARRIERTSOENABLED": "1", "FEX_TSOENABLED": "0", "WINEDLLOVERRIDES": "dxgi=n"})
        finally:
            del fixes["42"]

    def test_known_fix_arguments_and_launches_without_configuration(self):
        self.assertEqual(MODULE["known_args"](["proton", "waitforexitandrun", "SlayTheSpire2.exe"], "2868840"),
                         ["proton", "waitforexitandrun", "SlayTheSpire2.exe", "--rendering-driver", "vulkan"])
        fixes = MODULE["KNOWN_FIXES"]
        fixes["42"] = {"args": ["--rendering-driver", "vulkan"]}
        try:
            self.assertEqual(MODULE["known_args"](["proton", "waitforexitandrun", "game.exe"], "42"),
                             ["proton", "waitforexitandrun", "game.exe", "--rendering-driver", "vulkan"])
            chosen = ["proton", "waitforexitandrun", "game.exe", "--rendering-driver", "d3d12"]
            self.assertEqual(MODULE["known_args"](chosen, "42"), chosen)
        finally:
            del fixes["42"]
        self.assertEqual(MODULE["known_args"](["proton", "waitforexitandrun", "game.exe"], "43"),
                         ["proton", "waitforexitandrun", "game.exe"])
        with tempfile.TemporaryDirectory() as tmp:
            probe = Path(tmp) / "fake-proton"
            probe.write_text("#!/usr/bin/python3\nimport json, os, sys\nprint(json.dumps([os.environ.get('WINEDLLOVERRIDES'), sys.argv[1:]]))\n")
            probe.chmod(0o755)
            env = {**os.environ, "HOME": tmp, "STEAM_COMPAT_DATA_PATH": "/compatdata/2868840"}
            env.pop("WINEDLLOVERRIDES", None)
            run = lambda verb: json.loads(subprocess.check_output(
                [sys.executable, str(BIN / "bannerlator-game-env"), str(probe), verb, "SlayTheSpire2.exe"],
                env=env, text=True, stderr=subprocess.DEVNULL))
            # No configuration file at all: the fix still applies to the game's launch, and only to it.
            self.assertEqual(run("waitforexitandrun"),
                             ["tabtip.exe=d", ["waitforexitandrun", "SlayTheSpire2.exe", "--rendering-driver", "vulkan"]])
            self.assertEqual(run("run"), [None, ["run", "SlayTheSpire2.exe"]])

    def test_both_proton_wrappers_call_environment_launcher(self):
        for script in (COMPAT["LAUNCHER_SH"], COMPAT["EXTRA_WRAPPER_SH"] % "proton"):
            subprocess.run(["bash", "-n"], input=script, text=True, check=True)
            self.assertIn('exec ${BL_TASKSET:-} /usr/local/bin/bannerlator-game-env', script)


if __name__ == "__main__":
    unittest.main()
