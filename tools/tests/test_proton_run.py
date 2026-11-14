import json
import os
from pathlib import Path
import runpy
import shlex
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
RUN = runpy.run_path(str(BIN / "droiddeck-proton-run"))["main"].__globals__
GAME_ENV = runpy.run_path(str(BIN / "droiddeck-game-env"))


class Temp(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.root = self.tmp / "Steam"
        (self.root / "steamapps").mkdir(parents=True)
        patcher = mock.patch.dict(RUN, {"EXTRA_LIBRARIES": (), "SYSTEM_TOOLS": (), "STATE": str(self.tmp / "state.json")})
        patcher.start()
        self.addCleanup(patcher.stop)

    def write(self, relative, data, mode=0o644):
        path = self.tmp / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data if isinstance(data, bytes) else data.encode())
        path.chmod(mode)
        return path

    def dll(self, relative, markers=(b"ISteamClient", b"ISteamApps"), size=None):
        data = b"MZ" + b"".join(markers)
        if size:
            data = data.ljust(size, b"\0")
        return self.write(relative, data)

    def pe_dll(self, relative, managed=False):
        data = bytearray(0x200)
        data[0:2] = b"MZ"
        struct.pack_into("<I", data, 0x3C, 0x40)
        data[0x40:0x44] = b"PE\0\0"
        struct.pack_into("<H", data, 0x58, 0x10B)
        struct.pack_into("<I", data, 0x58 + 92, 16)
        if managed:
            struct.pack_into("<II", data, 0x58 + 96 + 14 * 8, 1, 1)
        return self.write(relative, bytes(data) + b"ISteamClient\0ISteamApps")

    def managed_dll(self, relative):
        return self.pe_dll(relative, managed=True)

    def target(self, install, start=None, appid="2150000000", kind="shortcut"):
        return RUN["Target"](appid, str(self.tmp / "compat"), str(install), str(self.root), kind,
                             str(start) if start else None)


class SteamLibraryTest(Temp):
    def game(self):
        folder = self.tmp / "Game"
        folder.mkdir()
        (folder / "Game.exe").write_bytes(b"MZ")
        return folder

    def test_a_library_beside_the_exe_is_found(self):
        folder = self.game()
        self.dll("Game/steam_api64.dll")
        self.assertEqual(RUN["steam_libraries"](self.target(folder)), {"steam_api64"})

    def test_a_library_in_a_game_subfolder_is_found(self):
        folder = self.game()
        self.dll("Game/bin/steam_api64.dll")
        self.assertEqual(RUN["steam_libraries"](self.target(folder)), {"steam_api64"})

    def test_a_library_under_bin_x64_is_found(self):
        folder = self.game()
        self.dll("Game/bin/x64/steam_api64.dll")
        self.assertEqual(RUN["steam_libraries"](self.target(folder)), {"steam_api64"})

    def test_a_library_in_a_known_folder_above_the_exe_is_found(self):
        folder = self.tmp / "Game"
        (folder / "bin").mkdir(parents=True)
        (folder / "bin/Game.exe").write_bytes(b"MZ")
        self.dll("Game/x64/steam_api64.dll")
        self.assertEqual(RUN["steam_libraries"](self.target(folder / "bin", folder)), {"steam_api64"})

    def test_a_library_beside_a_sibling_game_is_not_counted(self):
        start = self.tmp / "Shelf"
        folder = start / "A"
        folder.mkdir(parents=True)
        (folder / "A.exe").write_bytes(b"MZ")
        self.dll("Shelf/B/steam_api64.dll")
        self.assertEqual(RUN["steam_libraries"](self.target(folder, start)), set())

    def test_a_five_mebibyte_library_is_found(self):
        folder = self.game()
        self.dll("Game/steamclient64.dll", size=5 * 1024 * 1024)
        self.assertEqual(RUN["steam_libraries"](self.target(folder)), {"steamclient64"})

    def test_a_library_without_steam_in_its_name_is_found(self):
        folder = self.game()
        self.pe_dll("Game/Client.dll")
        self.assertEqual(RUN["steam_libraries"](self.target(folder)), {"Client"})

    def test_a_managed_assembly_is_not_a_library(self):
        folder = self.game()
        self.managed_dll("Game/Managed.dll")
        self.assertEqual(RUN["steam_libraries"](self.target(folder)), set())

    def test_a_non_regular_library_is_not_opened(self):
        folder = self.game()
        os.mkfifo(str(folder / "steam_api64.dll"))
        with mock.patch("builtins.open", side_effect=AssertionError("opened a non-regular file")):
            self.assertEqual(RUN["steam_libraries"](self.target(folder)), set())

    def test_a_symlink_loop_in_a_scanned_folder_is_skipped(self):
        folder = self.game()
        self.dll("Game/bin/steam_api64.dll")
        os.symlink("loop", folder / "loop")
        os.symlink("loop", folder / "bin/loop")
        self.assertEqual(RUN["steam_libraries"](self.target(folder)), {"steam_api64"})

    def test_a_non_pe_file_is_not_a_library(self):
        folder = self.game()
        self.write("Game/steam_notes.dll", b"ISteamClient ISteamApps")
        self.assertEqual(RUN["steam_libraries"](self.target(folder)), set())

    def test_both_interfaces_are_required(self):
        folder = self.game()
        self.write("Game/steam_api64.dll", b"MZISteamClient")
        self.assertEqual(RUN["steam_libraries"](self.target(folder)), set())

    def test_a_plain_game_has_no_library(self):
        self.assertEqual(RUN["steam_libraries"](self.target(self.game())), set())

    def test_a_symlinked_start_does_not_adopt_a_sibling_library(self):
        start = self.tmp / "Shelf"
        folder = start / "A"
        folder.mkdir(parents=True)
        (folder / "A.exe").write_bytes(b"MZ")
        self.dll("Shelf/B/steam_api64.dll")
        link = self.tmp / "start"
        os.symlink(start, link)
        self.assertEqual(RUN["steam_libraries"](self.target(folder, link)), set())

    def test_an_unrelated_start_does_not_adopt_a_sibling_library(self):
        install = self.tmp / "Games/A"
        install.mkdir(parents=True)
        (install / "A.exe").write_bytes(b"MZ")
        self.dll("Shelf/B/steam_api64.dll")
        self.assertEqual(RUN["steam_libraries"](self.target(install, self.tmp / "Shelf")), set())

    def test_a_sibling_library_in_a_known_folder_under_the_start_is_not_counted(self):
        start = self.tmp / "Shelf"
        folder = start / "A"
        folder.mkdir(parents=True)
        (folder / "A.exe").write_bytes(b"MZ")
        self.dll("Shelf/bin/steam_api64.dll")
        self.assertEqual(RUN["steam_libraries"](self.target(folder, start)), set())

    def test_the_scan_stops_at_the_stated_number_of_folders(self):
        folder = self.game()
        (folder / "a").mkdir()
        (folder / "b").mkdir()
        with mock.patch.dict(RUN, {"STEAM_SCAN_FOLDERS": 1}):
            self.assertEqual(len(RUN["steam_folders"](self.target(folder))), 1)

    def test_the_scan_stops_at_the_stated_number_of_entries(self):
        folder = self.game()
        for i in range(3):
            (folder / ("%d.dat" % i)).write_bytes(b"")
        with mock.patch.dict(RUN, {"STEAM_SCAN_ENTRIES": 2}):
            self.assertEqual(len(RUN["scan_entries"](str(folder))), 2)

    def test_the_scan_stops_at_the_stated_number_of_libraries(self):
        folder = self.game()
        for i in range(3):
            self.dll("Game/%d.dll" % i)
        with mock.patch.dict(RUN, {"STEAM_SCAN_FILES": 2}):
            self.assertEqual(len(RUN["steam_libraries"](self.target(folder))), 2)

    def test_the_scan_stops_at_the_stated_byte_budget(self):
        folder = self.game()
        self.dll("Game/one.dll", size=1024)
        with mock.patch.dict(RUN, {"STEAM_SCAN_BYTES": 1}):
            self.assertEqual(RUN["steam_libraries"](self.target(folder)), set())


class OwnSteamIdTest(Temp):
    def game(self, app_id="123"):
        folder = self.tmp / "Game"
        folder.mkdir()
        (folder / "Game.exe").write_bytes(b"MZ")
        if app_id is not None:
            self.write("Game/steam_appid.txt", app_id)
        return folder

    def test_an_app_id_beside_the_exe_counts(self):
        self.assertTrue(RUN["own_steam_id"](self.target(self.game())))

    def test_a_steam_settings_app_id_counts(self):
        folder = self.game(app_id=None)
        self.write("Game/steam_settings/steam_appid.txt", "123")
        self.assertTrue(RUN["own_steam_id"](self.target(folder)))

    def test_an_app_id_between_the_exe_and_the_start_folder_counts(self):
        folder = self.tmp / "Game"
        (folder / "bin").mkdir(parents=True)
        (folder / "bin/Game.exe").write_bytes(b"MZ")
        self.write("Game/bin/steam_appid.txt", "123")
        self.assertTrue(RUN["own_steam_id"](self.target(folder / "bin", folder)))

    def test_a_sibling_games_app_id_does_not_count(self):
        start = self.tmp / "Shelf"
        folder = start / "A"
        folder.mkdir(parents=True)
        (folder / "A.exe").write_bytes(b"MZ")
        self.write("Shelf/B/steam_appid.txt", "456")
        self.assertFalse(RUN["own_steam_id"](self.target(folder, start)))

    def test_a_zero_app_id_does_not_count(self):
        self.assertFalse(RUN["own_steam_id"](self.target(self.game("0"))))

    def test_spacewar_does_not_count(self):
        self.assertFalse(RUN["own_steam_id"](self.target(self.game("480"))))

    def test_a_non_numeric_app_id_does_not_count(self):
        self.assertFalse(RUN["own_steam_id"](self.target(self.game("123abc"))))

    def test_a_non_ascii_digit_app_id_does_not_count(self):
        self.assertFalse(RUN["own_steam_id"](self.target(self.game("\u00b2"))))

    def test_a_plain_game_has_no_app_id(self):
        self.assertFalse(RUN["own_steam_id"](self.target(self.game(None))))

    def test_a_non_regular_app_id_is_not_opened(self):
        folder = self.game(app_id=None)
        os.mkfifo(str(folder / "steam_appid.txt"))
        with mock.patch("builtins.open", side_effect=AssertionError("opened a non-regular file")):
            self.assertFalse(RUN["own_steam_id"](self.target(folder)))


class EnvironmentTest(Temp):
    def game(self):
        folder = self.tmp / "Game"
        folder.mkdir()
        (folder / "Game.exe").write_bytes(b"MZ")
        return folder

    def inherited(self):
        return {"SteamAppId": "999", "SteamGameId": "999", "SteamOverlayGameId": "999", "STEAM_COMPAT_APP_ID": "999"}

    def test_own_libraries_remove_inherited_steam_ids(self):
        folder = self.game()
        self.dll("Game/steam_api64.dll")
        source = self.inherited()
        env = RUN["environment"](source, self.target(folder), "/tool", str(self.root))
        for name in ("SteamAppId", "SteamGameId", "SteamOverlayGameId"):
            self.assertNotIn(name, env)
        self.assertEqual(env["STEAM_COMPAT_APP_ID"], "0")
        self.assertEqual(source["SteamAppId"], "999")

    def test_own_app_id_removes_inherited_steam_ids(self):
        folder = self.game()
        self.write("Game/steam_appid.txt", "123")
        env = RUN["environment"](self.inherited(), self.target(folder), "/tool", str(self.root))
        for name in ("SteamAppId", "SteamGameId", "SteamOverlayGameId"):
            self.assertNotIn(name, env)
        self.assertEqual(env["STEAM_COMPAT_APP_ID"], "0")

    def test_a_plain_shortcut_keeps_the_plain_environment(self):
        appid = "2150000000"
        env = RUN["environment"](self.inherited(), self.target(self.game(), appid=appid), "/tool", str(self.root))
        game = str(int(appid) << 32 | 0x02000000)
        self.assertEqual((env["SteamAppId"], env["STEAM_COMPAT_APP_ID"]), ("0", "0"))
        self.assertEqual((env["SteamGameId"], env["SteamOverlayGameId"]), (game, game))

    def test_a_plain_shortcut_beside_another_games_app_id_keeps_the_plain_environment(self):
        start = self.tmp / "Shelf"
        folder = start / "A"
        folder.mkdir(parents=True)
        (folder / "A.exe").write_bytes(b"MZ")
        self.write("Shelf/B/steam_appid.txt", "456")
        env = RUN["environment"](self.inherited(), self.target(folder, start), "/tool", str(self.root))
        self.assertEqual(env["SteamAppId"], "0")
        self.assertIn("SteamGameId", env)

    def test_a_steam_title_keeps_its_own_ids(self):
        env = RUN["environment"](self.inherited(), self.target(self.game(), appid="480", kind="steam"),
                                 "/tool", str(self.root))
        self.assertEqual((env["SteamAppId"], env["SteamGameId"], env["SteamOverlayGameId"]), ("480", "480", "480"))

    def test_a_non_shortcut_program_keeps_the_old_environment(self):
        folder = self.game()
        self.write("Game/steam_appid.txt", "123")
        env = RUN["environment"](self.inherited(), self.target(folder, kind="own"),
                                 "/tool", str(self.root))
        self.assertEqual(env["SteamAppId"], "0")
        self.assertIn("SteamGameId", env)

    def test_an_explicit_game_id_still_wins(self):
        folder = self.game()
        self.dll("Game/steam_api64.dll")
        env = RUN["environment"](self.inherited(), self.target(folder), "/tool", str(self.root),
                                 game_id="777")
        self.assertNotIn("SteamAppId", env)
        self.assertEqual((env["SteamGameId"], env["SteamOverlayGameId"]), ("777", "777"))


class OverrideTest(Temp):
    def game(self):
        folder = self.tmp / "Game"
        folder.mkdir()
        (folder / "Game.exe").write_bytes(b"MZ")
        return folder

    def state(self):
        folder = self.game()
        self.dll("Game/steam_api64.dll")
        return RUN["steam_state"](self.target(folder))

    def test_one_entry_per_dll_with_the_last_value_winning(self):
        self.assertEqual(RUN["merge_overrides"]("foo=n;foo=b"), "foo=b")

    def test_comma_separated_names_are_split(self):
        self.assertEqual(RUN["merge_overrides"]("steam_api64,lsteamclient=b"), "steam_api64=b;lsteamclient=b")

    def test_space_and_tab_separated_names_are_split(self):
        for merge in (RUN["merge_overrides"], GAME_ENV["merge_overrides"]):
            self.assertEqual(merge("steam_api64 lsteamclient=b"), "steam_api64=b;lsteamclient=b")
            self.assertEqual(merge("steam_api64\tlsteamclient=b"), "steam_api64=b;lsteamclient=b")

    def test_a_dll_suffix_is_the_same_name(self):
        for merge in (RUN["merge_overrides"], GAME_ENV["merge_overrides"]):
            self.assertEqual(merge("steam_api64.dll=b;steam_api64=n,b"), "steam_api64=n,b")

    def test_a_caller_dll_suffix_is_not_overridden(self):
        env = {"WINEDLLOVERRIDES": "steam_api64.dll=b"}
        RUN["prefer_game_steam_client"](env, self.state())
        self.assertEqual(env["WINEDLLOVERRIDES"], "steam_api64=b;lsteamclient=d")

    def test_quoted_entries_are_ignored(self):
        self.assertEqual(RUN["merge_overrides"]('foo="bad";bar=d'), "bar=d")

    def test_game_libraries_and_the_bridge_are_added_once(self):
        env = {}
        RUN["prefer_game_steam_client"](env, self.state())
        self.assertEqual(env["WINEDLLOVERRIDES"], "steam_api64=n,b;lsteamclient=d")

    def test_caller_entries_are_kept(self):
        env = {"WINEDLLOVERRIDES": "foo=n"}
        RUN["prefer_game_steam_client"](env, self.state())
        self.assertEqual(env["WINEDLLOVERRIDES"], "foo=n;steam_api64=n,b;lsteamclient=d")

    def test_a_caller_comma_list_is_not_overridden(self):
        env = {"WINEDLLOVERRIDES": "steam_api64,lsteamclient=b"}
        RUN["prefer_game_steam_client"](env, self.state())
        self.assertEqual(env["WINEDLLOVERRIDES"], "steam_api64,lsteamclient=b")

    def test_a_caller_lsteamclient_is_not_duplicated(self):
        env = {"WINEDLLOVERRIDES": "lsteamclient=n,b"}
        RUN["prefer_game_steam_client"](env, self.state())
        self.assertEqual(env["WINEDLLOVERRIDES"], "lsteamclient=n,b;steam_api64=n,b")

    def test_a_stored_override_is_appended_once(self):
        result = GAME_ENV["apply_config"](
            {"WINEDLLOVERRIDES": "steam_api64=n,b;lsteamclient=d"},
            {"version": 1, "shared": {"WINEDLLOVERRIDES": "libsentry=d"}},
            "2150000000",
        )
        self.assertEqual(result["WINEDLLOVERRIDES"], "steam_api64=n,b;lsteamclient=d;libsentry=d")

    def test_a_game_override_wins_and_is_not_duplicated(self):
        result = GAME_ENV["apply_config"](
            {"WINEDLLOVERRIDES": "steam_api64=n,b;lsteamclient=d"},
            {"version": 1, "shared": {"WINEDLLOVERRIDES": "foo=d"},
             "games": {"2150000000": {"WINEDLLOVERRIDES": "foo=b"}}},
            "2150000000",
        )
        self.assertEqual(result["WINEDLLOVERRIDES"], "steam_api64=n,b;lsteamclient=d;foo=b")
        self.assertEqual(result["WINEDLLOVERRIDES"].count("foo"), 1)

    def test_a_game_override_keeps_shared_and_fixes(self):
        result = GAME_ENV["apply_config"](
            {"WINEDLLOVERRIDES": "steam_api64=n,b;lsteamclient=d"},
            {"version": 1, "shared": {"WINEDLLOVERRIDES": "foo=d"},
             "games": {"2150000000": {"WINEDLLOVERRIDES": "foo=b;bar=b"}}},
            "2150000000",
            {"WINEDLLOVERRIDES": "ws2_32=b"},
        )
        self.assertEqual(result["WINEDLLOVERRIDES"],
                         "steam_api64=n,b;lsteamclient=d;foo=b;ws2_32=b;bar=b")

    def test_fixes_are_merged_without_duplicates(self):
        result = GAME_ENV["apply_config"](
            {"WINEDLLOVERRIDES": "steam_api64=n,b;lsteamclient=d"},
            {"version": 1, "shared": {"WINEDLLOVERRIDES": "foo=d"}},
            "2150000000",
            {"WINEDLLOVERRIDES": "foo=b;bar=d"},
        )
        self.assertEqual(result["WINEDLLOVERRIDES"], "steam_api64=n,b;lsteamclient=d;foo=b;bar=d")


TOOL_VDF = '''"compatibilitytools"
{
  "compat_tools"
  {
    "test"
    {
      "install_path" "."
      "from_oslist" "windows"
      "to_oslist" "linux"
    }
  }
}
'''

CONFIG_VDF = '''"InstallConfigStore"
{
  "Software"
  {
    "Valve"
    {
      "Steam"
      {
        "CompatToolMapping"
        {
          "2150000000"
          {
            "name" "test"
            "config" ""
            "priority" "250"
          }
        }
      }
    }
  }
}
'''


class ChainTest(Temp):
    def chain(self, appid, install, start, config=None, extra_env=None, verb="waitforexitandrun"):
        tool = self.root / "compatibilitytools.d/test"
        tool.mkdir(parents=True)
        (tool / "compatibilitytool.vdf").write_text(TOOL_VDF)
        (tool / "toolmanifest.vdf").write_text('"manifest"\n{\n "commandline" "/run %verb%"\n}\n')
        (self.root / "config").mkdir()
        (self.root / "config/config.vdf").write_text(CONFIG_VDF)
        probe = self.tmp / "final-env.json"
        proton = self.tmp / "fake-proton"
        proton.write_text("#!" + sys.executable + "\nimport json, os\njson.dump(dict(os.environ), open("
                          + repr(str(probe)) + ', "w"))\n')
        proton.chmod(0o755)
        runner = tool / "run"
        runner.write_text("#!/bin/sh\nexec " + shlex.quote(sys.executable) + " "
                          + shlex.quote(str(BIN / "droiddeck-game-env")) + " "
                          + shlex.quote(str(proton)) + ' "$@"\n')
        runner.chmod(0o755)
        settings = self.tmp / ".config/droiddeck/game-environment.json"
        if config is not None:
            settings.parent.mkdir(parents=True)
            settings.write_text(json.dumps(config))
        env = {**os.environ, "HOME": str(self.tmp), "STEAM_COMPAT_CLIENT_INSTALL_PATH": str(self.root),
               "SteamAppId": "999", "SteamGameId": "999", "SteamOverlayGameId": "999"}
        env.update(extra_env or {})
        result = subprocess.run(
            [sys.executable, str(BIN / "droiddeck-proton-run"), "--verb", verb, "--app", appid,
             "--dir", str(start), "--", str(install)],
            env=env, text=True, capture_output=True, timeout=30,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        return json.loads(probe.read_text())

    def test_the_chain_keeps_a_bundled_library_once_and_drops_the_ids(self):
        game = self.tmp / "Game"
        (game / "bin").mkdir(parents=True)
        (game / "bin/Game.exe").write_bytes(b"MZ")
        self.dll("Game/bin/steam_api64.dll")
        env = self.chain("2150000000", game / "bin/Game.exe", game,
                         {"version": 1, "games": {"2150000000": {"WINEDLLOVERRIDES": "lsteamclient=d"}}},
                         {"WINEDLLOVERRIDES": "caller=n"})
        for name in ("SteamAppId", "SteamGameId", "SteamOverlayGameId"):
            self.assertNotIn(name, env)
        self.assertEqual(env["STEAM_COMPAT_APP_ID"], "0")
        self.assertEqual((env["WINEDLLOVERRIDES"].count("steam_api64"), env["WINEDLLOVERRIDES"].count("lsteamclient")), (1, 1))

    def test_the_chain_keeps_a_plain_shortcuts_old_environment(self):
        start = self.tmp / "Shelf"
        game = start / "A"
        game.mkdir(parents=True)
        (game / "A.exe").write_bytes(b"MZ")
        self.write("Shelf/B/steam_appid.txt", "456")
        self.dll("Shelf/B/steam_api64.dll")
        env = self.chain("2150000000", game / "A.exe", start)
        game_id = str(2150000000 << 32 | 0x02000000)
        self.assertEqual((env["SteamAppId"], env["STEAM_COMPAT_APP_ID"]), ("0", "0"))
        self.assertEqual((env["SteamGameId"], env["SteamOverlayGameId"]), (game_id, game_id))

    def test_the_chain_keeps_a_plain_shortcut_plain_through_a_symlinked_start(self):
        start = self.tmp / "Shelf"
        game = start / "A"
        game.mkdir(parents=True)
        (game / "A.exe").write_bytes(b"MZ")
        self.write("Shelf/B/steam_appid.txt", "456")
        self.dll("Shelf/B/steam_api64.dll")
        link = self.tmp / "start"
        os.symlink(start, link)
        env = self.chain("2150000000", game / "A.exe", link)
        game_id = str(2150000000 << 32 | 0x02000000)
        self.assertEqual((env["SteamAppId"], env["STEAM_COMPAT_APP_ID"]), ("0", "0"))
        self.assertEqual((env["SteamGameId"], env["SteamOverlayGameId"]), (game_id, game_id))

    def test_the_run_verb_keeps_the_launcher_overrides(self):
        game = self.tmp / "Game"
        game.mkdir()
        (game / "Game.exe").write_bytes(b"MZ")
        self.dll("Game/steam_api64.dll")
        env = self.chain("2150000000", game / "Game.exe", game,
                         {"version": 1, "games": {"2150000000": {"WINEDLLOVERRIDES": "lsteamclient=d"}}},
                         {"WINEDLLOVERRIDES": "caller=n"}, verb="run")
        overrides = env["WINEDLLOVERRIDES"]
        self.assertEqual((overrides.count("steam_api64"), overrides.count("lsteamclient")), (1, 1))
        self.assertIn("caller=n", overrides)


if __name__ == "__main__":
    unittest.main()
