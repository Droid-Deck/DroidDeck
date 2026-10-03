import json
import os
import shutil
from pathlib import Path
import runpy
import subprocess
import sys
import tempfile
import unittest

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
MODULE = runpy.run_path(str(BIN / "bannerlator-game-env"))
COMPAT = runpy.run_path(str(BIN / "steam-compatibility"))
STATE = tempfile.TemporaryDirectory()
COMPAT["main"].__globals__["COMPAT_DIR"] = STATE.name


def mapping_of(text):
    tokens = COMPAT["tokenize"](text)
    return COMPAT["read_mapping"](tokens, COMPAT["mapping_block"](tokens))


def config_text(entries):
    mapping = "".join('"%s" { "name" "%s" "config" "" "priority" "%s" }' % (app, name, "75" if app == "0" else "250") for app, name in entries)
    return '"InstallConfigStore" { "Software" { "Valve" { "Steam" { "CompatToolMapping" { %s } } } } }' % mapping


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
        self.audio = base / "directaudio"
        for arch in ("aarch64-windows", "i386-windows", "aarch64-unix"):
            (self.audio / "lib/wine" / arch).mkdir(parents=True)
        (self.audio / "lib/wine/aarch64-windows/winedirectaudio.drv").write_bytes(b"arm64x")
        (self.audio / "lib/wine/i386-windows/winedirectaudio.drv").write_bytes(b"i386")
        self.compat = base / "compat"
        self.windows = self.compat / "pfx/drive_c/windows"
        for folder in ("system32", "syswow64"):
            (self.windows / folder).mkdir(parents=True)
        self.reg = self.compat / "pfx/user.reg"

    def run_setup(self):
        script = COMPAT["BL_DIRECTAUDIO_SETUP"] + '\nbl_directaudio "$1"\necho "WINEDLLPATH=$WINEDLLPATH"\n'
        env = dict(os.environ, BL_DIRECTAUDIO=str(self.audio), STEAM_COMPAT_DATA_PATH=str(self.compat))
        env.pop("WINEDLLPATH", None)
        return subprocess.run(["bash", "-c", script, "bash", str(self.tool)], env=env, capture_output=True, text=True, check=True)

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

    def test_the_driver_is_placed_and_selected_the_way_wine_reads_it(self):
        self.reg.write_text("WINE REGISTRY Version 2\n;; All keys relative to \\\\User\\\\S-1-5-21-0-0-0-1000\n\n#arch=win64\n")
        result = self.run_setup()
        self.assertIn("DirectAudio selected", result.stderr)
        self.assertIn("WINEDLLPATH=%s/lib/wine" % self.audio, result.stdout)
        self.assertEqual(self.link("system32"), str(self.audio / "lib/wine/aarch64-windows/winedirectaudio.drv"))
        self.assertEqual(self.link("syswow64"), str(self.audio / "lib/wine/i386-windows/winedirectaudio.drv"))
        self.assertEqual((self.windows / "system32/winedirectaudio.drv").read_bytes(), b"arm64x")
        self.assertEqual(self.audio_values(), [("[Software\\\\Wine\\\\Drivers]", '"Audio"="directaudio,pulse"')])
        before = self.reg.read_text()
        self.assertNotIn("DirectAudio selected", self.run_setup().stderr)
        self.assertEqual(self.reg.read_text(), before)
        self.assertEqual(sorted(os.listdir(self.windows / "system32")), ["winedirectaudio.drv"])

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


if __name__ == "__main__":
    unittest.main()
