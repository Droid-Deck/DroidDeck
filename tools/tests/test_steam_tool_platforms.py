import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from tools.tests import test_droiddeck_fex as fixtures
from tools.tests import test_steam_compat_live as live
from tools.tests.test_steam_compat_mapping import config_text


COMPAT = live.COMPAT
TOOL = COMPAT["TOOL"]


class ToolPlatformsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.steam = self.root / "Steam"
        self.state = self.root / "state"
        self.config = self.steam / "config/config.vdf"
        self.config.parent.mkdir(parents=True)
        self.state.mkdir()
        (self.steam / "appcache").mkdir()
        (self.steam / "steamapps").mkdir()
        self.entries = {
            976730: ("windows", "64", "game"),
            1826330: ("linux", "", "tool"),
            1161040: ("linux", "", "tool"),
            900001: ("windows", "64", "tool"),
            900002: ("linux", "arm64", "tool"),
            900003: ("osx", "64", "tool"),
            900004: ("", "", "tool"),
            900005: ("linux,windows", "64", "tool"),
        }
        self.write_appinfo()
        self.catalog = [{"name": TOOL, "display": "Proton", "dir": "Proton", "valve": True}]
        self.config.write_text(config_text('"0" { "name" "%s" }' % TOOL))
        patcher = mock.patch.dict(COMPAT["register_default"].__globals__, COMPAT_DIR=str(self.state))
        patcher.start()
        self.addCleanup(patcher.stop)

    def write_appinfo(self, magic=0x07564429):
        (self.steam / "appcache/appinfo.vdf").write_bytes(fixtures.AppInfoTest.appinfo(self.entries, magic))

    def platforms(self):
        return COMPAT["tool_platforms"](str(self.steam))

    def plan(self, current, auto=None, **kwargs):
        return COMPAT["plan_mapping"](current, ["976730"], [TOOL], TOOL, auto or {},
                                      tools=self.platforms(), **kwargs)

    def test_metadata_selects_tools_across_cache_formats(self):
        for magic in (0x07564427, 0x07564428, 0x07564429):
            with self.subTest(magic=magic):
                self.write_appinfo(magic)
                self.assertEqual(set(self.platforms()), {"1826330", "1161040", "900001", "900002", "900005"})

    def test_tools_are_not_installed_games(self):
        for app in self.entries:
            (self.steam / ("steamapps/appmanifest_%s.acf" % app)).write_text("")
        self.assertEqual(COMPAT["installed_apps"](str(self.steam)), ["976730"])

    def test_linux_tools_clear_proton_and_windows_tools_get_default_before_install(self):
        current = {"0": TOOL, "976730": TOOL, "1826330": TOOL, "1161040": "proton_experimental"}
        changes, auto = self.plan(current, {"1826330": TOOL, "976730": TOOL})
        self.assertEqual(changes, {"1826330": None, "1161040": None, "900001": TOOL})
        self.assertNotIn("1826330", auto)
        self.assertEqual(auto["900001"], TOOL)
        self.assertEqual(auto["976730"], TOOL)

    def test_linux_tool_requests_cannot_restore_windows_platform(self):
        changes, auto = self.plan({"0": TOOL, "1826330": TOOL}, overrides={"1826330": TOOL})
        self.assertIsNone(changes["1826330"])
        self.assertNotIn("1826330", auto)

    def test_linux_execution_tools_and_custom_selections_survive(self):
        current = {"0": TOOL, "1826330": COMPAT["FEX_TOOL"], "900002": "custom-native",
                   "900001": "custom-windows", "900005": TOOL}
        changes, auto = self.plan(current)
        self.assertEqual(changes, {"976730": TOOL})
        self.assertNotIn("1826330", auto)

    def test_missing_or_malformed_cache_does_not_invent_platforms(self):
        cache = self.steam / "appcache/appinfo.vdf"
        cache.unlink()
        self.assertEqual(self.platforms(), {})
        cache.write_bytes(b"invalid")
        self.assertEqual(self.platforms(), {})

    def test_offline_repair_is_idempotent_and_preserves_game_and_neighbor_fields(self):
        self.config.write_text(config_text(
            '"0" { "name" "%s" } "976730" { "name" "custom-game" } '
            '"1826330" { "name" "%s" }' % (TOOL, TOOL)))
        for _ in range(2):
            COMPAT["register_default"](str(self.config), ["976730"], catalog=self.catalog,
                                       directory=str(self.state), default=TOOL)
            self.assertEqual(COMPAT["read_config_mapping"](str(self.config)),
                             {"0": TOOL, "976730": "custom-game", "900001": TOOL})
            self.assertIn('"outside"', self.config.read_text())
            state = json.loads((self.state / "state.json").read_text())
            self.assertNotIn("1826330", state["auto"])

    def reconcile(self, page):
        helper = live.LIVE["Helper"](str(self.steam), 1)
        helper.page = page
        with mock.patch.dict(COMPAT, tool_catalog=lambda root: self.catalog):
            helper.reconcile()
        return json.loads((self.state / "state.json").read_text())

    def test_live_repair_covers_uninstalled_dependencies_and_stays_idempotent(self):
        page = live.FakePage({"0": TOOL, "1826330": TOOL, "976730": "custom-game"}, [TOOL])
        state = self.reconcile(page)
        self.assertEqual(page.sets, [("900001", TOOL), ("1826330", "")])
        self.assertNotIn("1826330", state["auto"])
        page.sets.clear()
        self.reconcile(page)
        self.assertEqual(page.sets, [])
        self.assertEqual(page.mapping["976730"], "custom-game")

    def test_live_refused_tools_are_not_written(self):
        page = live.FakePage({"0": TOOL, "1826330": TOOL}, [TOOL], refused={"1826330", "900001"})
        self.reconcile(page)
        self.assertEqual(page.sets, [])
