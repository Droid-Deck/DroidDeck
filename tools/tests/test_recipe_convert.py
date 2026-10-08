import json
import os
from pathlib import Path
import runpy
import shutil
import tempfile
import unittest

CONVERT = runpy.run_path(str(Path(__file__).resolve().parents[1] / "recipes/convert.py"))
CATALOG = {
    "Dxvk-Linux": ["dxvk-1.10.3-linux.wcp", "dxvk-2.6.2-linux.wcp", "dxvk-low-latency-3.1.1-2-linux.wcp"],
    "Dxvk-gplasync-Linux": ["dxvk-gplasync-2.3.1-1-linux.wcp", "dxvk-gplasync-2.6.2-1-linux.wcp", "dxvk-gplasync-3.1-1-binsem-linux.wcp"],
    "Vkd3d-proton-Linux": ["vkd3d-proton-2.14.1-linux.wcp", "vkd3d-low-latency-3.0.1-2-linux.wcp"],
}
EXTREME = {"id": "local_Extreme", "name": "Extreme", "TSOEnabled": False, "HalfBarrierTSOEnabled": False,
           "X87ReducedPrecision": True, "Multiblock": True, "SMCChecks": "mtrack", "userChangeSettingMap": None}
RECOMMENDED = {"id": "game_recommend_id", "name": "Game Presets", "TSOEnabled": True, "HalfBarrierTSOEnabled": True,
               "X87ReducedPrecision": False, "Multiblock": True, "SMCChecks": "mtrack", "userChangeSettingMap": None}


def named(name):
    return json.dumps({"name": name})


class ConvertTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.bh = self.tmp / "bh"
        self.bl = self.tmp / "bl"
        (self.bh / "configs").mkdir(parents=True)
        (self.bh / "configs/.gitkeep").write_text("")
        self.bl.mkdir()
        self.folders = {}
        self.count = 0

    def upload(self, folder, appid, token, soc="Adreno (TM) 740", fex=EXTREME, dxvk="dxvk-2.3.1-async",
               vkd3d="vkd3d-2.12", container="proton10.0-arm64x-2", env="", device=None):
        self.count += 1
        self.folders[folder] = appid
        settings = {"pc_ls_CONTAINER_LIST": named(container), "pc_ls_DXVK": named(dxvk), "pc_ls_VK3k": named(vkd3d),
                    "pc_ls_environment_variable": env}
        if fex is not None:
            settings["pc_ls_TRANSLATOR_CONFIG_APPLYING_FEX129505"] = json.dumps(fex)
        meta = {"device": device or "Device %s" % token, "soc": soc}
        if token:
            meta["upload_token"] = token
        path = self.bh / "configs" / folder
        path.mkdir(exist_ok=True)
        (path / ("%s-%s-%d.json" % (folder, meta["device"].replace(" ", "_"), 1780000000 + self.count))).write_text(
            json.dumps({"meta": meta, "settings": settings}))

    def build(self, overrides=None, catalog=CATALOG):
        steam = {folder: {"appid": int(appid) if appid else None, "method": "naive", "steam_name": folder}
                 for folder, appid in self.folders.items()}
        (self.bl / "games_steam.json").write_text(json.dumps(steam))
        (self.bl / "steam_aliases.json").write_text("{}")
        return CONVERT["build"](str(self.bh), str(self.bl), catalog, overrides or {}, now=0)

    def test_agreeing_fex_choices_become_variables_and_defaults_do_not(self):
        for token in "abc":
            self.upload("Game", "42", token)
        library, report, stats = self.build()
        self.assertEqual(library["version"], 2)
        game = library["games"]["42"]
        self.assertEqual(game["name"], "Game")
        self.assertEqual(game["variants"][0]["env"], {"FEX_TSOENABLED": "0", "FEX_HALFBARRIERTSOENABLED": "0",
                                                     "FEX_X87REDUCEDPRECISION": "1"})
        self.assertNotIn("gpu", game["variants"][0])
        self.assertIn("3 sources", game["variants"][0]["note"])
        self.assertIn("FEX_TSOENABLED=0 (3 of 3 sources)", report[0][3])

    def test_too_few_sources_a_split_or_the_recommended_profile_give_nothing(self):
        for token in "ab":
            self.upload("Two", "1", token)
        for token in "ab":
            self.upload("Split", "2", token)
        for token in "cd":
            self.upload("Split", "2", token, fex=dict(EXTREME, TSOEnabled=True, HalfBarrierTSOEnabled=True, X87ReducedPrecision=False))
        for token in "abcd":
            self.upload("Recommended", "3", token, fex=RECOMMENDED)
        self.upload("Same uploader", "4", "a")
        self.upload("Same uploader", "4", "a")
        self.upload("Same uploader", "4", "a")
        self.assertEqual(self.build()[0]["games"], {})

    def test_fex_needs_half_of_the_games_sources(self):
        for token in "abc":
            self.upload("Game", "42", token)
        for token in "defgh":
            self.upload("Game", "42", token, fex=RECOMMENDED)
        self.assertEqual(self.build()[0]["games"], {})

    def test_a_changed_field_on_the_recommended_profile_counts(self):
        changed = dict(RECOMMENDED, SMCChecks="none", userChangeSettingMap={"com.xj.winemu.bean.SMCChecksSetting": True})
        for token in "abc":
            self.upload("Game", "42", token, fex=changed)
        self.assertEqual(self.build()[0]["games"]["42"]["variants"][0]["env"], {"FEX_SMCCHECKS": "none"})

    def test_x64_containers_do_not_count_for_fex(self):
        for token in "abc":
            self.upload("Game", "42", token, container="proton9.0-x64-3")
        self.assertEqual(self.build()[0]["games"], {})

    def test_dxvk_per_family_maps_to_a_linux_package_and_skips_defaults(self):
        # A build most uploads on a family carry is that app's default, not a choice.
        for n in range(40):
            self.upload("Filler", "9", "filler%d" % n, fex=RECOMMENDED)
            self.upload("Filler", "9", "filler6-%d" % n, fex=RECOMMENDED, soc="Adreno (TM) 650")
        for token in "abc":
            self.upload("Async", "42", token, fex=RECOMMENDED, dxvk="dxvk-v2.6.2-1-async")
        for token in "def":
            self.upload("Plain", "43", token, fex=RECOMMENDED, dxvk="dxvk-1.10.3", soc="Adreno (TM) 650")
        for token in "ghi":
            self.upload("Default", "44", token, fex=RECOMMENDED)
        for token in "jkl":
            self.upload("Nothing on Linux", "45", token, fex=RECOMMENDED, dxvk="dxvk-1.5.5")
        for token in "mno":
            self.upload("Mali", "46", token, fex=RECOMMENDED, dxvk="dxvk-1.10.3", soc="Mali-G57")
        games = self.build()[0]["games"]
        self.assertEqual(games["42"]["variants"], [{
            "gpu": ["A7XX"], "dxvk": {"file": "dxvk-gplasync-2.6.2-1-linux.wcp", "release": "Dxvk-gplasync-Linux"},
            "env": {"DXVK_ASYNC": "1"}, "note": games["42"]["variants"][0]["note"]}])
        self.assertEqual(games["43"]["variants"][0]["gpu"], ["A6XX"])
        self.assertEqual(games["43"]["variants"][0]["dxvk"], {"file": "dxvk-1.10.3-linux.wcp", "release": "Dxvk-Linux"})
        self.assertNotIn("env", games["43"]["variants"][0])
        for appid in ("44", "45", "46"):
            self.assertNotIn(appid, games)

    def test_package_mapping(self):
        self.assertEqual(CONVERT["package_for"]("dxvk", "dxvk-v2.6-1-async", CATALOG)[0]["file"], "dxvk-gplasync-2.6.2-1-linux.wcp")
        self.assertEqual(CONVERT["package_for"]("vkd3d", "vkd3d-proton-2.14.1", CATALOG)[0]["file"], "vkd3d-proton-2.14.1-linux.wcp")
        for comp, build in (("dxvk", "dxvk-v1.11.1-mali-fix"), ("dxvk", "dxvk-1.12.0-sarek-dyasync"), ("dxvk", "dxvk-3.1.1"),
                            ("vkd3d", "vkd3d-proton-3.0.1"), ("dxvk", None)):
            self.assertIsNone(CONVERT["package_for"](comp, build, CATALOG), build)
        self.assertEqual(CONVERT["family"]("Adreno (TM) 720"), "A7XX_LOW")
        self.assertEqual(CONVERT["family"]("Adreno (TM) 830"), "A8XX")
        self.assertIsNone(CONVERT["family"]("Mali-G610"))

    def test_only_allowed_variables_are_kept(self):
        for token in "abc":
            self.upload("Game", "42", token, fex=RECOMMENDED,
                        env='TU_DEBUG="noconform" WINEDLLOVERRIDES=xinput1_3=n,b DXVK_CONFIG_FILE=/sdcard/x.conf')
        self.assertEqual(self.build()[0]["games"]["42"]["variants"][0]["env"], {"WINEDLLOVERRIDES": "xinput1_3=n,b"})

    def test_uploads_without_an_appid_are_left_out(self):
        for token in "abc":
            self.upload("Repack", None, token)
        library, _, stats = self.build()
        self.assertEqual((library["games"], stats["steam_apps"]), ({}, 0))

    def test_overrides_replace_and_block(self):
        for token in "abc":
            self.upload("Game", "42", token)
            self.upload("Other", "43", token)
        tested = {"name": "Game", "variants": [{"env": {"FEX_TSOENABLED": "1"}, "note": "tested"}]}
        games = self.build({"games": {"42": tested, "43": {"blocked": True}, "44": tested}})[0]["games"]
        self.assertEqual(games, {"42": tested, "44": tested})

    def test_every_variable_and_package_passes_the_app_rules(self):
        for token in "abc":
            self.upload("Game", "42", token, dxvk="dxvk-v2.6.2-1-async")
        for n in range(40):
            self.upload("Filler", "9", "filler%d" % n, fex=RECOMMENDED)
        for variant in self.build()[0]["games"]["42"]["variants"]:
            for name, value in variant.get("env", {}).items():
                self.assertTrue(CONVERT["RECIPE"]["env_allowed"](name, value), name)

    def test_the_cli_writes_the_library_and_report(self):
        for token in "abc":
            self.upload("Game", "42", token)
        self.build()
        catalog = self.tmp / "catalog.json"
        catalog.write_text(json.dumps(CATALOG))
        out, report = self.tmp / "out.json", self.tmp / "report.md"
        CONVERT["main"](["--bannerhub", str(self.bh), "--bannerlator", str(self.bl), "--catalog", str(catalog),
                         "--overrides", str(self.tmp / "missing.json"), "--out", str(out), "--report", str(report)])
        self.assertIn("42", json.loads(out.read_text())["games"])
        self.assertIn("| 42 | Game | 3 |", report.read_text())


if __name__ == "__main__":
    unittest.main()
