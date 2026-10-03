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
COMPAT = runpy.run_path(str(BIN / "steam-compatibility"))


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
            COMPAT["build_tool"](str(tools / COMPAT["TOOL"]), str(depot))
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

    def test_both_proton_wrappers_call_environment_launcher(self):
        for script in (COMPAT["LAUNCHER_SH"], COMPAT["EXTRA_WRAPPER_SH"] % "proton"):
            subprocess.run(["bash", "-n"], input=script, text=True, check=True)
            self.assertIn('exec ${BL_TASKSET:-} /usr/local/bin/bannerlator-game-env', script)

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


if __name__ == "__main__":
    unittest.main()
