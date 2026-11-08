import json
import runpy
import tempfile
import unittest
from pathlib import Path
from unittest import mock

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
COMPAT = runpy.run_path(str(BIN / "steam-compatibility"))
TOOL = COMPAT["TOOL"]
OLD = {"name": "DroidDeck-Proton-11-11.2", "display": "DroidDeck-Proton 11-11.2", "dir": "DroidDeck-Proton 11-11.2", "valve": False}
NEW = {"name": "DroidDeck-Proton-11-11.4", "display": "DroidDeck-Proton 11-11.4", "dir": "DroidDeck-Proton 11-11.4", "valve": False}
VALVE = {"name": TOOL, "display": "Proton Experimental ARM64", "dir": "Proton - Experimental (ARM64)", "valve": True}
GE = {"name": "GE-Proton11-7", "display": "GE-Proton11-7", "dir": "GE-Proton11-7", "valve": False}
GE10 = {"name": "GE-Proton10-3", "display": "GE-Proton10-3", "dir": "GE-Proton10-3", "valve": False}
NEWER = {"name": "DroidDeck-Proton-11-12.1", "display": "DroidDeck-Proton 11-12.1", "dir": "DroidDeck-Proton 11-12.1", "valve": False}


def config_text(entries):
    mapping = "".join('"%s" { "name" "%s" "config" "" "priority" "%s" }' % (app, name, "75" if app == "0" else "250") for app, name in entries)
    return '"InstallConfigStore" { "Software" { "Valve" { "Steam" { "CompatToolMapping" { %s } } } } }' % mapping


class DroidDeckProtonDefaultTest(unittest.TestCase):
    def run_default(self, state, mapping, catalog, apps=(), request=None, settle=True, owned=None, games=None):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            (directory / "state.json").write_text(json.dumps(state))
            if request is not None:
                (directory / "request.json").write_text(json.dumps(request))
            if games is not None:
                (directory / "games.json").write_text(json.dumps({"version": 1, "games": games}))
            config = directory / "config.vdf"
            config.write_text(config_text(mapping))
            with mock.patch.dict(COMPAT["register_default"].__globals__, steam_running=lambda: False):
                default = COMPAT["register_default"](str(config), list(apps), catalog=catalog, directory=str(directory),
                                                     settle=settle, owned=owned)
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
        self.assertEqual((state["default"], state["source"], state["written"]), (NEW["name"], "update", NEW["name"]))

    def test_a_default_picked_after_the_switch_is_kept(self):
        default, mapping, state = self.run_default(
            {"default": NEW["name"], "own_proton": True, "applied": 1, "tools": [VALVE, NEW]},
            [("0", NEW["name"])], [VALVE, NEW], request={"seq": 2, "valve": True, "dir": VALVE["dir"]})
        self.assertEqual(default, TOOL)
        self.assertEqual(mapping, {"0": TOOL})
        default, mapping, state = self.run_default(state, [("0", TOOL)], [VALVE, NEW])
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
            {"default": TOOL, "source": "app", "written": TOOL, "tools": [VALVE, OLD]}, [("0", TOOL), ("43", OLD["name"])], [VALVE, NEW], ["43"])
        self.assertEqual(default, TOOL)
        self.assertEqual(mapping, {"0": TOOL, "43": NEW["name"]})

    def test_nothing_changes_without_droiddeck_proton(self):
        default, mapping, state = self.run_default({"default": TOOL}, [("0", TOOL)], [VALVE, GE])
        self.assertEqual(default, TOOL)
        self.assertEqual(mapping, {"0": TOOL})
        self.assertNotIn("own_proton", state)

    def test_a_fresh_state_with_steam_on_proton_experimental_moves_to_the_newest_build(self):
        default, mapping, state = self.run_default({}, [("0", TOOL)], [VALVE, OLD, NEW])
        self.assertEqual((default, mapping), (NEW["name"], {"0": NEW["name"]}))
        default, mapping, state = self.run_default(state, [("0", NEW["name"])], [VALVE, OLD, NEW])
        self.assertEqual((default, state["source"]), (NEW["name"], "update"))

    def test_a_default_adopted_from_steam_by_an_earlier_build_recovers(self):
        default, mapping, state = self.run_default(
            {"default": TOOL, "source": "steam", "seq": 5, "own_proton": True, "tools": [VALVE, NEW]}, [("0", TOOL)], [VALVE, NEW])
        self.assertEqual((default, mapping, state["source"]), (NEW["name"], {"0": NEW["name"]}, "update"))
        self.assertNotIn("own_proton", state)

    def test_a_request_applied_without_a_change_still_moves_to_the_newest_build(self):
        for request in ({"seq": 5, "valve": True, "dir": VALVE["dir"]}, {"seq": 5, "valve": False, "dir": GE["dir"]}):
            default, mapping, state = self.run_default({"default": TOOL, "applied": 1}, [("0", TOOL)], [VALVE, NEW], request=request)
            self.assertEqual((default, mapping, state["applied"]), (NEW["name"], {"0": NEW["name"]}, 5))

    def test_a_default_that_is_not_installed_falls_back_without_counting_as_a_pick(self):
        default, mapping, state = self.run_default(
            {"default": GE["name"], "source": "app", "seq": 9, "written": GE["name"]}, [("0", GE["name"])], [VALVE])
        self.assertEqual((default, mapping), (TOOL, {"0": TOOL}))
        self.assertEqual((state["source"], state["seq"]), ("fallback", 9))
        default, _, state = self.run_default(state, [("0", TOOL)], [VALVE, NEW])
        self.assertEqual((default, state["source"]), (NEW["name"], "update"))

    def test_proton_experimental_asked_for_in_the_app_stays(self):
        default, mapping, state = self.run_default(
            {"default": NEW["name"], "written": NEW["name"], "applied": 1}, [("0", NEW["name"])], [VALVE, NEW],
            request={"seq": 2, "valve": True, "dir": VALVE["dir"]})
        self.assertEqual((default, state["source"]), (TOOL, "app"))
        for catalog in ([VALVE, NEW], [VALVE, NEW, NEWER], [VALVE, NEWER]):
            default, mapping, state = self.run_default(state, [("0", TOOL)], catalog)
            self.assertEqual((default, mapping, state["source"]), (TOOL, {"0": TOOL}, "app"))

    def test_proton_experimental_picked_in_steam_stays(self):
        default, mapping, state = self.run_default(
            {"default": NEW["name"], "written": NEW["name"], "source": "update"}, [("0", TOOL)], [VALVE, NEW])
        self.assertEqual((default, state["source"], state["written"]), (TOOL, "steam", TOOL))
        for catalog in ([VALVE, NEW], [VALVE, NEW, NEWER]):
            default, mapping, state = self.run_default(state, [("0", TOOL)], catalog)
            self.assertEqual((default, mapping, state["source"]), (TOOL, {"0": TOOL}, "steam"))

    def test_steam_echoing_the_written_default_is_not_a_pick(self):
        default, _, state = self.run_default(
            {"default": NEW["name"], "written": TOOL, "source": "update"}, [("0", TOOL)], [VALVE, NEW])
        self.assertEqual((default, state["source"]), (NEW["name"], "update"))

    def test_a_default_the_running_client_does_not_list_is_kept(self):
        state = {"default": NEW["name"], "written": NEW["name"], "seq": 1, "source": "update"}
        default, state = COMPAT["choose_default"](TOOL, state, {}, [VALVE, NEW], listed=[TOOL])
        self.assertEqual((default, state["default"], state["source"], state["seq"]), (TOOL, NEW["name"], "update", 1))
        default, state = COMPAT["choose_default"](GE["name"], dict(state, written=TOOL), {}, [VALVE, NEW, GE])
        self.assertEqual((default, state["source"]), (GE["name"], "steam"))

    def test_owned_shortcuts_follow_the_default_and_explicit_picks_stay(self):
        owned = ["3000000001", "3000000002", "3000000003", "3000000004"]
        catalog = [VALVE, NEW, GE, GE10]
        default, mapping, state = self.run_default(
            {"default": NEW["name"], "written": NEW["name"], "source": "update", "auto": {}},
            [("0", NEW["name"]), ("3000000001", NEW["name"]), ("3000000002", TOOL), ("3000000003", GE["name"]),
             ("3000000004", NEW["name"]), ("42", GE["name"]), ("44", NEW["name"])],
            catalog, ["42", "44"] + owned, settle=False, owned=owned, games={"44": {"valve": False, "dir": GE["dir"]}})
        self.assertEqual(mapping, {"0": NEW["name"], "3000000001": NEW["name"], "3000000002": NEW["name"],
                                   "3000000003": GE["name"], "3000000004": NEW["name"], "42": GE["name"], "44": GE["name"]})
        mapping["3000000001"] = TOOL
        default, mapping, state = self.run_default(state, sorted(mapping.items()), catalog, ["42", "44"] + owned,
                                                   settle=False, owned=owned)
        self.assertEqual(mapping["3000000001"], TOOL)
        default, mapping, state = self.run_default(state, sorted(mapping.items()), catalog, ["42", "44"] + owned,
                                                   request={"seq": 1, "valve": False, "dir": GE10["dir"]})
        self.assertEqual(default, GE10["name"])
        self.assertEqual(mapping, {"0": GE10["name"], "3000000001": TOOL, "3000000002": GE10["name"],
                                   "3000000003": GE["name"], "3000000004": GE10["name"], "42": GE["name"], "44": GE["name"]})

    def test_the_newest_build_is_found_by_version(self):
        newest, gone = COMPAT["own_proton_moves"]({"tools": [OLD]}, [dict(NEW, name="a", dir="DroidDeck-Proton 11-9.10"), dict(NEW, name="b", dir="DroidDeck-Proton 11-11.1")])
        self.assertEqual(newest, "b")
        self.assertEqual(gone, {OLD["name"]})


if __name__ == "__main__":
    unittest.main()
