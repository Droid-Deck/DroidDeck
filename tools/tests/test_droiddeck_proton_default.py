import json
import runpy
import tempfile
import unittest
from pathlib import Path

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
COMPAT = runpy.run_path(str(BIN / "steam-compatibility"))
TOOL = COMPAT["TOOL"]
OLD = {"name": "DroidDeck-Proton-11-11.2", "display": "DroidDeck-Proton 11-11.2", "dir": "DroidDeck-Proton 11-11.2", "valve": False}
NEW = {"name": "DroidDeck-Proton-11-11.4", "display": "DroidDeck-Proton 11-11.4", "dir": "DroidDeck-Proton 11-11.4", "valve": False}
VALVE = {"name": TOOL, "display": "Proton Experimental ARM64", "dir": "Proton - Experimental (ARM64)", "valve": True}
GE = {"name": "GE-Proton11-7", "display": "GE-Proton11-7", "dir": "GE-Proton11-7", "valve": False}


def config_text(entries):
    mapping = "".join('"%s" { "name" "%s" "config" "" "priority" "%s" }' % (app, name, "75" if app == "0" else "250") for app, name in entries)
    return '"InstallConfigStore" { "Software" { "Valve" { "Steam" { "CompatToolMapping" { %s } } } } }' % mapping


class DroidDeckProtonDefaultTest(unittest.TestCase):
    def run_default(self, state, mapping, catalog, apps=(), request=None, settle=True):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            (directory / "state.json").write_text(json.dumps(state))
            if request is not None:
                (directory / "request.json").write_text(json.dumps(request))
            config = directory / "config.vdf"
            config.write_text(config_text(mapping))
            default = COMPAT["register_default"](str(config), list(apps), catalog=catalog, directory=str(directory), settle=settle)
            return default, COMPAT["read_config_mapping"](str(config)), json.loads((directory / "state.json").read_text())

    def test_a_replacement_seen_while_steam_runs_still_moves_the_default_later(self):
        _, mapping, state = self.run_default(
            {"default": OLD["name"], "own_proton": True, "tools": [VALVE, OLD]}, [("0", OLD["name"])], [VALVE, NEW], settle=False)
        self.assertEqual(state["tools"], [VALVE, NEW])
        self.assertIn(OLD["name"], state["own_builds"])
        default, mapping, state = self.run_default(state, [("0", OLD["name"])], [VALVE, NEW])
        self.assertEqual(default, NEW["name"])
        self.assertEqual(mapping, {"0": NEW["name"]})

    def test_a_valve_default_picked_in_steam_after_a_replacement_is_kept(self):
        default, mapping, _ = self.run_default(
            {"default": OLD["name"], "own_proton": True, "tools": [VALVE, OLD]}, [("0", TOOL)], [VALVE, NEW])
        self.assertEqual(default, TOOL)
        self.assertEqual(mapping, {"0": TOOL})

    def test_the_first_start_with_droiddeck_proton_makes_it_the_default_for_following_titles(self):
        default, mapping, state = self.run_default(
            {"default": TOOL, "auto": {"42": TOOL}}, [("0", TOOL), ("42", TOOL), ("43", TOOL)], [VALVE, NEW], ["42", "43"])
        self.assertEqual(default, NEW["name"])
        self.assertEqual(mapping, {"0": NEW["name"], "42": NEW["name"], "43": TOOL})
        self.assertEqual(state["default"], NEW["name"])
        self.assertTrue(state["own_proton"])

    def test_a_default_picked_after_the_switch_is_kept(self):
        default, mapping, state = self.run_default(
            {"default": NEW["name"], "own_proton": True, "applied": 1, "tools": [VALVE, NEW]},
            [("0", NEW["name"])], [VALVE, NEW], request={"seq": 2, "valve": True, "dir": VALVE["dir"]})
        self.assertEqual(default, TOOL)
        self.assertEqual(mapping, {"0": TOOL})
        default, mapping, state = self.run_default(
            {"default": TOOL, "own_proton": True, "tools": [VALVE, NEW]}, [("0", TOOL)], [VALVE, NEW])
        self.assertEqual(default, TOOL)
        self.assertEqual(mapping, {"0": TOOL})

    def test_a_replaced_build_moves_its_default_and_titles_to_the_newest(self):
        default, mapping, state = self.run_default(
            {"default": OLD["name"], "own_proton": True, "auto": {"42": OLD["name"]}, "tools": [VALVE, OLD, GE]},
            [("0", OLD["name"]), ("42", OLD["name"]), ("43", OLD["name"]), ("44", GE["name"]), ("45", TOOL)],
            [VALVE, NEW, GE], ["42", "43", "44", "45"])
        self.assertEqual(default, NEW["name"])
        self.assertEqual(mapping, {"0": NEW["name"], "42": NEW["name"], "43": NEW["name"], "44": GE["name"], "45": TOOL})
        self.assertEqual(state["auto"], {"42": NEW["name"]})

    def test_a_replaced_build_does_not_override_a_valve_default(self):
        default, mapping, _ = self.run_default(
            {"default": TOOL, "own_proton": True, "tools": [VALVE, OLD]}, [("0", TOOL), ("43", OLD["name"])], [VALVE, NEW], ["43"])
        self.assertEqual(default, TOOL)
        self.assertEqual(mapping, {"0": TOOL, "43": NEW["name"]})

    def test_nothing_changes_without_droiddeck_proton(self):
        default, mapping, state = self.run_default({"default": TOOL}, [("0", TOOL)], [VALVE, GE])
        self.assertEqual(default, TOOL)
        self.assertEqual(mapping, {"0": TOOL})
        self.assertNotIn("own_proton", state)

    def test_the_newest_build_is_found_by_version(self):
        newest, gone = COMPAT["own_proton_moves"]({"tools": [OLD]}, [dict(NEW, name="a", dir="DroidDeck-Proton 11-9.10"), dict(NEW, name="b", dir="DroidDeck-Proton 11-11.1")])
        self.assertEqual(newest, "b")
        self.assertEqual(gone, {OLD["name"]})


if __name__ == "__main__":
    unittest.main()
