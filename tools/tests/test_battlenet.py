import contextlib
import json
import os
from pathlib import Path
import re
import runpy
import shutil
import tempfile
import threading
import time
import unittest
from unittest import mock
import zlib

REPO = Path(__file__).resolve().parents[2]
BIN = REPO / "tools/linuxfs/overlay/usr/local/bin"
BNET = runpy.run_path(str(BIN / "droiddeck-battlenet"))["main"].__globals__
BNET["CACHE"] = tempfile.mkdtemp(prefix="bnet-test-cache-")
BNET["LOG"] = os.path.join(BNET["CACHE"], "battlenet.log")
RUN = runpy.run_path(str(BIN / "droiddeck-proton-run"))["main"].__globals__
STORE = runpy.run_path(str(BIN / "droiddeck-store-launch"))["main"].__globals__
KOTLIN = REPO / "app/src/main/java/com/droiddeck/launcher/runtime/BattleNet.kt"


class Temp(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def write(self, relative, data, mode=0o644):
        path = self.tmp / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data if isinstance(data, bytes) else data.encode())
        path.chmod(mode)
        return path


class LogTest(Temp):
    def test_each_line_is_kept_and_a_long_log_rotates(self):
        log = self.tmp / "battlenet.log"
        with open(os.devnull, "w") as null, \
                mock.patch.dict(BNET, {"CACHE": str(self.tmp), "LOG": str(log), "LOG_LIMIT": 64}), \
                mock.patch.object(BNET["sys"], "stderr", null):
            BNET["say"]("first line, long enough to pass the limit of sixty-four bytes")
            BNET["say"]("second")
        self.assertIn("first line", (self.tmp / "battlenet.log.1").read_text())
        self.assertRegex(log.read_text(), r"^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d \[\d+\] second\n$")


class IdTest(unittest.TestCase):
    def test_the_prefix_id_is_the_shortcut_id_the_app_names(self):
        self.assertEqual(int(BNET["APP_ID"]), zlib.crc32(b'"droiddeck-battlenet"Battle.net') | 0x80000000)
        found = re.search(r"const val APP_ID = (\d+)L", KOTLIN.read_text())
        self.assertEqual(found.group(1), BNET["APP_ID"])
        for name in ("FEX_REQUEST", "FEX_RESULT"):
            self.assertIn('const val %s = "%s"' % (name, BNET[name]), KOTLIN.read_text())


class ProtonRunOptionsTest(Temp):
    def setUp(self):
        super().setUp()
        self.root = self.tmp / "Steam"
        folder = self.root / "compatibilitytools.d/droiddeck-proton-arm64"
        self.write("Steam/compatibilitytools.d/droiddeck-proton-arm64/compatibilitytool.vdf",
                   '"compatibilitytools"\n{\n  "compat_tools"\n  {\n    "droiddeck-proton-arm64"\n    {\n'
                   '      "install_path" "."\n      "display_name" "x"\n      "from_oslist" "windows"\n'
                   '      "to_oslist" "linux"\n    }\n  }\n}\n')
        self.write("Steam/compatibilitytools.d/droiddeck-proton-arm64/toolmanifest.vdf",
                   '"manifest"\n{\n  "commandline" "/droiddeck-proton %verb%"\n}\n')
        self.write("Steam/compatibilitytools.d/droiddeck-proton-arm64/droiddeck-proton", b"#!/bin/sh\n", 0o755)
        self.entry = str(folder / "droiddeck-proton")
        patcher = mock.patch.dict(RUN, {"EXTRA_LIBRARIES": (), "SYSTEM_TOOLS": (), "STATE": str(self.tmp / "state.json"),
                                        "say": lambda message: None})
        patcher.start()
        self.addCleanup(patcher.stop)

    def run_main(self, argv):
        calls = []
        with mock.patch.dict(os.environ, {"STEAM_COMPAT_CLIENT_INSTALL_PATH": str(self.root)}), \
                mock.patch.object(RUN["os"], "execve", lambda *a: calls.append(a)), mock.patch.object(RUN["os"], "chdir"):
            status = RUN["main"](argv)
        return status, calls

    def test_a_named_prefix_wins_over_where_the_program_lies(self):
        setup = str(self.write("Download/Battle.net-Setup.exe", b"MZ"))
        status, calls = self.run_main(["--window", "--prefix", "2596113170", setup])
        self.assertIsNone(status)
        entry, argv, env = calls[0]
        self.assertEqual(argv, [self.entry, "waitforexitandrun", setup])
        self.assertEqual(env["STEAM_COMPAT_DATA_PATH"], str(self.root / "steamapps/compatdata/2596113170"))
        self.assertEqual((env["SteamAppId"], env["SteamGameId"], env["PROTON_USE_WINED3D"]),
                         ("0", str(2596113170 << 32 | 0x02000000), "1"))
        self.assertTrue((self.root / "steamapps/compatdata/2596113170").is_dir())

    def test_run_starts_beside_what_runs_in_the_prefix(self):
        client = str(self.write("Steam/steamapps/compatdata/2596113170/pfx/drive_c/Battle.net.exe", b"MZ"))
        _, calls = self.run_main(["--verb", "run", client])
        self.assertEqual(calls[0][1], [self.entry, "run", client])

    def test_a_launch_from_steam_keeps_its_game_id_for_gamescope(self):
        client = str(self.write("Steam/steamapps/compatdata/2596113170/pfx/drive_c/Battle.net.exe", b"MZ"))
        _, calls = self.run_main(["--prefix", "2596113170", "--game-id", "11151176786366644224", client])
        env = calls[0][2]
        self.assertEqual((env["SteamGameId"], env["SteamOverlayGameId"]), ("11151176786366644224", "11151176786366644224"))
        self.assertEqual(env["STEAM_COMPAT_DATA_PATH"], str(self.root / "steamapps/compatdata/2596113170"))
        self.assertNotIn("PROTON_USE_WINED3D", env)
        self.assertEqual(self.run_main(["--game-id", "0", client]), (2, []))

    def test_start_without_steam_has_droiddeck_battlenet_launch_a_games_shortcut(self):
        client = str(self.write("Steam/steamapps/compatdata/2596113170/pfx/drive_c/Program Files (x86)/Battle.net/Battle.net.exe", b"MZ"))
        prefix = str(self.root / "steamapps/compatdata/2596113170")
        options = 'STEAM_COMPAT_DATA_PATH=%s %%command%% --exec="launch Fen"' % prefix
        status, calls = self.run_main(["--app", "3000000000", "--dir", os.path.dirname(client), "--options", options, "--", client])
        self.assertIsNone(status)
        _, argv, env = calls[0]
        self.assertEqual(argv, [self.entry, "waitforexitandrun", client, "--exec=launch Fen"])
        self.assertEqual(env["STEAM_COMPAT_DATA_PATH"], prefix)
        self.assertEqual(STORE["battlenet"](argv[2:], env={}), [STORE["BATTLENET"], "launch", "Fen"])

    def test_a_bad_prefix_or_verb_is_refused(self):
        setup = str(self.write("Download/setup.exe", b"MZ"))
        for argv in (["--prefix", "0", setup], ["--prefix", "07", setup], ["--prefix", "4294967296", setup],
                     ["--prefix", "abc", setup], ["--prefix"], ["--verb", "getcompatpath", setup]):
            with self.subTest(argv):
                self.assertEqual(self.run_main(list(argv)), (2, []))


class ConfigAndMenuTest(Temp):
    def config_path(self):
        return self.tmp / ("steamapps/compatdata/%s/pfx/drive_c/users/steamuser/AppData/Roaming/Battle.net/Battle.net.config"
                           % BNET["APP_ID"])

    def test_the_config_keeps_browser_hardware_acceleration_off(self):
        root = str(self.tmp)
        self.assertEqual(BNET["write_config"](root), "first")
        path = self.config_path()
        config = json.loads(path.read_text())
        self.assertEqual(config["Client"]["HardwareAcceleration"], "false")
        self.assertEqual(config["Client"]["Streaming"]["StreamingEnabled"], "false")
        self.assertEqual(config["Client"]["GameLaunchWindowBehavior"], "2")
        self.assertIsNone(BNET["write_config"](root))
        path.write_text('{"Client": {"HardwareAcceleration": "false", "GameLaunchWindowBehavior": "0"}, "Games": {"osi": {}}}')
        self.assertEqual(BNET["write_config"](root), "launch 32-bit")
        config = json.loads(path.read_text())
        self.assertEqual(config, {"Client": {"HardwareAcceleration": "false", "GameLaunchWindowBehavior": "2"},
                                  "Games": {"osi": {}, "s1": {"Run64Bit": "false", "Run64BitSet": "true"}}})
        path.write_text('{"Client": {"HardwareAcceleration": "true", "GameLaunchWindowBehavior": "2"}}')
        self.assertEqual(BNET["write_config"](root), "acceleration 32-bit")
        self.assertEqual(json.loads(path.read_text())["Client"]["HardwareAcceleration"], "false")

    def test_starcraft_starts_its_32_bit_client_until_a_player_picks_one(self):
        root = str(self.tmp)
        BNET["write_config"](root)
        self.assertEqual(json.loads(self.config_path().read_text())["Games"], {"s1": {"Run64Bit": "false", "Run64BitSet": "true"}})
        self.config_path().write_text('{"Client": {"HardwareAcceleration": "false", "GameLaunchWindowBehavior": "2"}, '
                                      '"Games": {"s1": {"ServerUid": "s1", "Run64Bit": "true"}}}')
        self.assertEqual(BNET["write_config"](root), "32-bit")
        self.assertEqual(json.loads(self.config_path().read_text())["Games"]["s1"],
                         {"ServerUid": "s1", "Run64Bit": "false", "Run64BitSet": "true"})
        self.config_path().write_text('{"Client": {"HardwareAcceleration": "false", "GameLaunchWindowBehavior": "2"}, '
                                      '"Games": {"s1": {"Run64Bit": "true", "Run64BitSet": "true"}}}')
        self.assertIsNone(BNET["write_config"](root))
        self.config_path().write_text('{"Client": {"HardwareAcceleration": "false", "GameLaunchWindowBehavior": "2"}, "Games": []}')
        self.assertIsNone(BNET["write_config"](root))

    def test_games_install_to_the_chosen_library(self):
        root = str(self.tmp)
        BNET["write_config"](root)
        self.assertEqual(BNET["write_config"](root, "/root/Games/Stores/Battle.net"), "library")
        config = json.loads(self.config_path().read_text())
        self.assertEqual(config["Client"]["Install"]["DefaultInstallPath"], "Z:/root/Games/Stores/Battle.net")
        self.assertIsNone(BNET["write_config"](root, "/root/Games/Stores/Battle.net/"))
        config["Client"]["Install"]["DefaultInstallPath"] = "Z:/root/Games/Stores/Battle.net/"
        self.config_path().write_text(json.dumps(config))
        self.assertIsNone(BNET["write_config"](root, "/root/Games/Stores/Battle.net"))
        self.assertEqual(BNET["write_config"](root, "/mnt/droiddeck-sd/Games/Battle.net"), "library")

    def test_a_config_that_is_not_json_is_left_alone(self):
        path = self.config_path()
        path.parent.mkdir(parents=True)
        for text in ("{not json", "[1, 2]", '{"Client": "x"}'):
            with self.subTest(text):
                path.write_text(text)
                self.assertIsNone(BNET["write_config"](str(self.tmp)))
                self.assertEqual(path.read_text(), text)

    def test_the_menu_entry_is_a_window_not_a_game(self):
        path = self.tmp / "applications/droiddeck-battlenet.desktop"
        BNET["write_menu"](str(path))
        text = path.read_text()
        self.assertIn("Exec=/usr/local/bin/droiddeck-battlenet\n", text)
        self.assertIn("Name=Battle.net\n", text)
        self.assertNotIn("Game", re.search(r"^Categories=(.*)$", text, re.M).group(1))
        self.assertNotIn("StartupWMClass", text)
        mtime = path.stat().st_mtime_ns
        time.sleep(0.01)
        BNET["write_menu"](str(path))
        self.assertEqual(path.stat().st_mtime_ns, mtime)


class GamepadTest(Temp):
    def registry(self, *entries):
        rows = ["WINE REGISTRY Version 2"]
        for name, uid, location in entries:
            rows += ["", "[Software\\\\Wow6432Node\\\\Microsoft\\\\Windows\\\\CurrentVersion\\\\Uninstall\\\\%s] 1" % name,
                     '"InstallLocation"="%s"' % location.replace("\\", "\\\\"),
                     '"UninstallString"="\\"C:\\\\ProgramData\\\\Battle.net\\\\Agent\\\\Blizzard Uninstaller.exe\\" --uid=%s"' % uid]
        self.write("Steam/steamapps/compatdata/%s/pfx/system.reg" % BNET["APP_ID"], "\n".join(rows) + "\n")

    def z(self, path):
        return "Z:" + str(path).replace("/", "\\")

    def test_every_world_of_warcraft_flavor_gets_controllers_on(self):
        wow = self.tmp / "Games/World of Warcraft"
        retail = self.write("Games/World of Warcraft/_retail_/WTF/Config.wtf", 'SET gxApi "D3D11"')
        (wow / "_classic_era_").mkdir(parents=True)
        (wow / "Data").mkdir()
        self.registry(("World of Warcraft", "wow", self.z(wow)))
        with mock.patch.dict(BNET, {"say": lambda message: None}):
            self.assertEqual(len(BNET["wow_settings"](str(self.tmp / "Steam"))), 2)
            self.assertEqual(BNET["wow_settings"](str(self.tmp / "Steam")), [])
        self.assertEqual(retail.read_text(), 'SET gxApi "D3D11"\nSET GamePadEnable "1"\n')
        self.assertEqual((wow / "_classic_era_/WTF/Config.wtf").read_text(), 'SET GamePadEnable "1"\nSET gxApi "D3D11"\n')
        self.assertFalse((wow / "Data/WTF").exists())

    def test_directx_11_is_set_once_and_a_later_pick_is_kept(self):
        wow = self.tmp / "Games/World of Warcraft"
        retail = self.write("Games/World of Warcraft/_retail_/WTF/Config.wtf", 'SET GxApi "D3D12"\nSET GamePadEnable "1"\nSET vsync "1"\n')
        self.registry(("World of Warcraft", "wow", self.z(wow)))
        with mock.patch.dict(BNET, {"say": lambda message: None}):
            self.assertEqual(len(BNET["wow_settings"](str(self.tmp / "Steam"))), 1)
            self.assertEqual(retail.read_text(), 'SET gxApi "D3D11"\nSET GamePadEnable "1"\nSET vsync "1"\n')
            self.assertTrue((wow / "_retail_/WTF/.droiddeck-d3d11").exists())
            retail.write_text('SET gxApi "d3d12"\nSET GamePadEnable "1"\nSET vsync "1"\n')
            self.assertEqual(BNET["wow_settings"](str(self.tmp / "Steam")), [])
        self.assertEqual(retail.read_text(), 'SET gxApi "d3d12"\nSET GamePadEnable "1"\nSET vsync "1"\n')

    def test_a_players_own_choice_and_other_games_are_left_alone(self):
        wow = self.tmp / "Games/World of Warcraft Classic"
        config = self.write("Games/World of Warcraft Classic/_anniversary_/WTF/Config.wtf", 'SET GamePadEnable "0"\n')
        other = self.tmp / "Games/Diablo IV"
        (other / "_retail_").mkdir(parents=True)
        self.registry(("World of Warcraft Classic", "wow_classic_anniversary", self.z(wow)), ("Diablo IV", "fenris", self.z(other)))
        with mock.patch.dict(BNET, {"say": lambda message: None}):
            self.assertEqual(len(BNET["wow_settings"](str(self.tmp / "Steam"))), 1)
        self.assertEqual(config.read_text(), 'SET GamePadEnable "0"\nSET gxApi "D3D11"\n')
        self.assertFalse((other / "_retail_/WTF").exists())

    def test_pads_look_like_an_xbox_360_pad_only_without_the_steam_client(self):
        proc = self.tmp / "proc"
        self.write("proc/10/cmdline", b"/usr/bin/python3\0/usr/local/bin/droiddeck-battlenet\0")
        self.write("proc/11/cmdline", b"c:\\windows\\system32\\steam.exe\0")
        self.assertFalse(BNET["steam_running"](str(proc)))
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop("PROTON_SPOOF_STEAMINPUT_VIDPID", None)
            with mock.patch.dict(BNET, {"steam_running": lambda: False}):
                self.assertEqual(BNET["proton_env"]()["PROTON_SPOOF_STEAMINPUT_VIDPID"], "1")
            with mock.patch.dict(BNET, {"steam_running": lambda: True}):
                self.assertNotIn("PROTON_SPOOF_STEAMINPUT_VIDPID", BNET["proton_env"]())
            os.environ["PROTON_SPOOF_STEAMINPUT_VIDPID"] = "0"
            with mock.patch.dict(BNET, {"steam_running": lambda: False}):
                self.assertEqual(BNET["proton_env"]()["PROTON_SPOOF_STEAMINPUT_VIDPID"], "0")
        self.write("proc/12/cmdline", b"/usr/bin/FEX\0/root/.local/share/Steam/ubuntu12_32/steam\0-silent\0")
        self.assertTrue(BNET["steam_running"](str(proc)))


class ProcessTest(Temp):
    def proc(self, pid, argv, prefix=None):
        env = b"HOME=/root\0" + (("WINEPREFIX=%s\0" % prefix).encode() if prefix else b"")
        self.write("proc/%d/environ" % pid, env)
        self.write("proc/%d/cmdline" % pid, b"\0".join(a.encode() for a in argv) + b"\0")

    def test_the_prefixs_processes_are_found_by_their_wineprefix(self):
        pfx = self.tmp / "compatdata/2596113170/pfx"
        pfx.mkdir(parents=True)
        self.proc(10, ["C:\\Program Files (x86)\\Battle.net\\Battle.net.exe", "--from-launcher"], str(pfx) + "/")
        self.proc(11, ["C:\\ProgramData\\Battle.net\\Agent\\Agent.9142\\Agent.exe"], str(pfx))
        self.proc(12, ["C:\\Games\\Other.exe"], str(self.tmp / "compatdata/42/pfx"))
        self.proc(13, ["/usr/bin/bash"])
        self.write("proc/self/environ", b"")
        found = BNET["wine_processes"](str(pfx), str(self.tmp / "proc"))
        self.assertEqual(sorted(pid for pid, _ in found), [10, 11])
        self.assertEqual([pid for pid, argv in found if BNET["is_client"](argv)], [10])

    def test_only_battle_net_itself_counts_as_open(self):
        self.assertTrue(BNET["is_client"](["C:\\Program Files (x86)\\Battle.net\\Battle.net.exe"]))
        self.assertTrue(BNET["is_client"](["/root/.local/share/Steam/x/Battle.net.exe", "--x"]))
        self.assertTrue(BNET["is_client"](["battle.net.exe"]))
        self.assertFalse(BNET["is_client"](["C:\\Program Files (x86)\\Battle.net\\Battle.net Launcher.exe"]))
        self.assertFalse(BNET["is_client"](["C:\\users\\steamuser\\Temp\\Battle.net-Setup.exe"]))
        self.assertFalse(BNET["is_client"]([]))

    def test_battle_nets_own_processes_are_those_under_its_folders(self):
        for argv in (["C:\\Program Files (x86)\\Battle.net\\Battle.net.exe", "--type=renderer"],
                     ["C:/ProgramData/Battle.net/Agent/Agent.9824/Agent.exe"],
                     ["C:\\Program Files (x86)\\Battle.net\\Battle.net Launcher.exe"]):
            with self.subTest(argv):
                self.assertTrue(BNET["is_battlenet"](argv))
        for argv in (["Z:\\root\\.cache\\droiddeck\\battlenet\\Battle.net-Setup.exe"],
                     ["Z:\\root\\Games\\Stores\\Battle.net\\Hearthstone\\Hearthstone.exe"],
                     ["C:\\Program Files (x86)\\Diablo IV\\Diablo IV.exe"], ["C:\\windows\\system32\\services.exe"], []):
            with self.subTest(argv):
                self.assertFalse(BNET["is_battlenet"](argv))

    def test_a_game_battle_net_launched_carries_its_uid(self):
        self.assertTrue(BNET["launched"](["C:\\Program Files (x86)\\Diablo II Resurrected\\D2R.exe", "-uid", "osi"]))
        self.assertTrue(BNET["launched"](["C:\\Program Files (x86)\\Diablo IV\\Diablo IV.exe", "-launch", "-UID", "fenris"]))
        for argv in (["C:\\Program Files (x86)\\Diablo IV\\Diablo IV.exe"],
                     ["C:\\Program Files (x86)\\Diablo IV\\vc_redist.x64.exe", "/quiet"],
                     ["C:\\Program Files (x86)\\Battle.net\\Battle.net.exe", "-uid", "battle.net"]):
            with self.subTest(argv):
                self.assertFalse(BNET["launched"](argv))

    def test_a_game_that_signs_in_with_battle_nets_token_says_so(self):
        self.assertTrue(BNET["shares_sign_in"](["Diablo IV.exe", "-nostreamline", "-sso", "-launch", "-uid", "fenris"]))
        self.assertFalse(BNET["shares_sign_in"](["D2R.exe", "-uid", "osi"]))
        self.assertFalse(BNET["shares_sign_in"](["-sso.exe"]))

    def test_battle_net_takes_work_once_its_agent_is_up(self):
        client = (1, ["C:\\Program Files (x86)\\Battle.net\\Battle.net.exe"])
        agent = (2, ["C:\\ProgramData\\Battle.net\\Agent\\Agent.9824\\Agent.exe"])
        self.assertTrue(BNET["agent_up"]([client, agent]))
        self.assertFalse(BNET["agent_up"]([client]))
        self.assertFalse(BNET["agent_up"]([agent]))

    def test_open_is_waited_for_then_closed(self):
        states = iter([False, False, True, True, False])
        with mock.patch.dict(BNET, {"running": lambda pfx: next(states)}), mock.patch.object(BNET["time"], "sleep"):
            self.assertTrue(BNET["wait_while_open"]("/pfx", grace=60, poll=0))
        with mock.patch.dict(BNET, {"running": lambda pfx: False}):
            self.assertFalse(BNET["wait_while_open"]("/pfx", grace=0, poll=0))


class ProtonWaitTest(Temp):
    class Process:
        def __init__(self, test, waits):
            self.test, self.waits = test, waits

        def wait(self, timeout=None):
            self.waits -= 1
            if self.test.stopped or self.waits < 0:
                return 0
            raise BNET["subprocess"].TimeoutExpired("proton", timeout)

    def setUp(self):
        super().setUp()
        self.stopped = []

    def run_proton(self, states, close_grace=0, waits=100):
        states = iter(states)
        last = []

        def processes(pfx, proc="/proc"):
            last[:] = next(states, last)
            return list(last)

        with mock.patch.dict(BNET, {"wine_processes": processes, "say": lambda message: None,
                                    "stop": lambda found, wait=0: self.stopped.extend(pid for pid, _ in found)}), \
                mock.patch.object(BNET["subprocess"], "Popen", lambda cmd, **kw: self.Process(self, waits)):
            return BNET["proton"]("launcher.exe", watch="/pfx", poll=0, close_grace=close_grace)

    def test_agent_and_wine_left_behind_are_stopped_once_battle_net_closed(self):
        client = (1, ["C:\\Program Files (x86)\\Battle.net\\Battle.net.exe"])
        agent = (2, ["C:/ProgramData/Battle.net/Agent/Agent.9824/Agent.exe", "--locale=enUS"])
        wine = (3, ["C:\\windows\\system32\\services.exe"])
        self.assertEqual(self.run_proton([[client, agent, wine], [agent, wine], [agent, wine]]), (0, True))
        self.assertEqual(sorted(self.stopped), [2, 3])

    def test_nothing_is_stopped_while_a_game_still_runs(self):
        client = (1, ["Battle.net.exe"])
        game = (4, ["C:\\Games\\Diablo IV\\Diablo IV.exe"])
        self.assertEqual(self.run_proton([[client, game]] + [[game]] * 5, waits=6), (0, True))
        self.assertEqual(self.stopped, [])

    def test_a_restart_to_update_is_not_a_close(self):
        client = (1, ["Battle.net.exe"])
        agent = (2, ["Agent.exe"])
        states = [[client, agent], [agent], [client, agent], [agent]]
        with mock.patch.object(BNET["time"], "monotonic", side_effect=[0, 0, 5, 5, 10, 10, 30, 30, 30, 30]):
            self.run_proton(states, close_grace=15)
        self.assertEqual(self.stopped, [2])

    def test_battle_net_is_closed_once_a_game_is_up_and_what_is_left_once_it_exits(self):
        client = (1, ["C:\\Program Files (x86)\\Battle.net\\Battle.net.exe"])
        agent = (2, ["C:\\ProgramData\\Battle.net\\Agent\\Agent.9824\\Agent.exe"])
        wine = (3, ["C:\\windows\\system32\\services.exe"])
        game = (4, ["C:\\Program Files (x86)\\Diablo IV\\Diablo IV.exe", "-launch", "-uid", "fenris"])
        tidy = BNET["Tidy"](settle=0, gone=60)
        states = [[client, agent, wine], [client, agent, wine, game], [wine, game], [wine, game], [wine], [wine]]
        with mock.patch.dict(BNET, {"notify": lambda *a, **k: self.fail("notified")}):
            self.assertEqual(self.run_proton_tidy(states, tidy), (0, True))
        self.assertEqual(self.stopped, [1, 2, 3])

    def run_proton_tidy(self, states, tidy):
        states = iter(states)
        last = []
        test = self

        def processes(pfx, proc="/proc"):
            last[:] = next(states, last)
            return list(last)

        class Process:
            def wait(self, timeout=None):
                if 3 in test.stopped:
                    return 0
                raise BNET["subprocess"].TimeoutExpired("proton", timeout)

        with mock.patch.dict(BNET, {"wine_processes": processes, "say": lambda message: None,
                                    "stop": lambda found, wait=0: self.stopped.extend(pid for pid, _ in found)}), \
                mock.patch.object(BNET["subprocess"], "Popen", lambda cmd, **kw: Process()):
            return BNET["proton"]("launcher.exe", watch="/pfx", poll=0, close_grace=0, tidy=tidy)

    def test_only_wine_and_agent_are_idle(self):
        self.assertTrue(BNET["idle"]([(1, ["C:\\windows\\system32\\explorer.exe", "/desktop"]),
                                      (2, ["/x/files/bin/wineserver"]), (3, ["c:\\windows\\system32\\steam.exe", "a"])]))
        self.assertFalse(BNET["idle"]([(1, ["Battle.net.exe"])]))
        self.assertFalse(BNET["idle"]([(1, ["Z:\\root\\.cache\\droiddeck\\battlenet\\Battle.net-Setup.exe"])]))


class RequestTest(Temp):
    UP = [(1, ["C:\\Program Files (x86)\\Battle.net\\Battle.net.exe"]),
          (2, ["C:\\ProgramData\\Battle.net\\Agent\\Agent.9437\\Agent.exe"])]

    def test_the_apps_request_waits_for_battle_net_then_goes_to_it(self):
        (self.tmp / "battlenet-request.00000000000000000001").write_text("install-game\nhs_beta\n")
        calls = []
        with mock.patch.dict(BNET, {"spawn": lambda exe, verb, args=(): calls.append((exe, verb, list(args))) or 0}):
            self.assertFalse(BNET["serve_requests"]("/p/pfx", self.UP[:1], str(self.tmp)))
            self.assertTrue((self.tmp / "battlenet-request.00000000000000000001").exists())
            self.assertTrue(BNET["serve_requests"]("/p/pfx", self.UP, str(self.tmp)))
            (self.tmp / "battlenet-request.00000000000000000002").write_text("launch\nWTCG\n")
            self.assertTrue(BNET["serve_requests"]("/p/pfx", self.UP, str(self.tmp)))
            (self.tmp / "battlenet-request.00000000000000000003").write_text("launch\nWTCG;reboot\n")
            self.assertFalse(BNET["serve_requests"]("/p/pfx", self.UP, str(self.tmp)))
        client = "/p/pfx/drive_c/Program Files (x86)/Battle.net/Battle.net.exe"
        self.assertEqual(calls, [(client, "run", ["--install", "--game=hs_beta"]), (client, "run", ["--exec=launch WTCG"])])
        self.assertEqual(list(self.tmp.iterdir()), [])

    def test_requests_left_before_the_poll_are_all_served_in_order(self):
        (self.tmp / "battlenet-request.00000000000000000020").write_text("launch\nOSI\n")
        (self.tmp / "battlenet-request.00000000000000000010").write_text("install-game\nwow\n")
        (self.tmp / "battlenet-request.00000000000000000030.tmp").write_text("install-game\nfenris\n")
        calls = []
        with mock.patch.dict(BNET, {"spawn": lambda exe, verb, args=(): calls.append(list(args)) or 0}):
            self.assertTrue(BNET["serve_requests"]("/p/pfx", self.UP, str(self.tmp)))
        self.assertEqual(calls, [["--install", "--game=wow"], ["--exec=launch OSI"]])
        self.assertEqual([p.name for p in self.tmp.iterdir()], ["battlenet-request.00000000000000000030.tmp"])


class TidyTest(unittest.TestCase):
    CLIENT = (1, ["C:\\Program Files (x86)\\Battle.net\\Battle.net.exe"])
    PAGE = (2, ["C:\\Program Files (x86)\\Battle.net\\Battle.net.exe", "--type=renderer"])
    AGENT = (3, ["C:\\ProgramData\\Battle.net\\Agent\\Agent.9824\\Agent.exe"])
    WINE = (4, ["C:\\windows\\system32\\services.exe"])
    GAME = (5, ["C:\\Program Files (x86)\\Diablo II Resurrected\\D2R.exe", "-uid", "osi"])

    def setUp(self):
        self.stopped = []
        self.notified = []
        patcher = mock.patch.dict(BNET, {
            "say": lambda message: None,
            "stop": lambda found, wait=0: self.stopped.append(sorted(pid for pid, _ in found)),
            "notify": lambda title, body, icon=None: self.notified.append(title),
        })
        patcher.start()
        self.addCleanup(patcher.stop)

    def run_tidy(self, tidy, timeline):
        return [tidy(processes, now) for now, processes in timeline]

    def test_battle_net_and_its_agent_close_once_the_game_has_run_a_while(self):
        up = [self.CLIENT, self.PAGE, self.AGENT, self.WINE]
        tidy = BNET["Tidy"](settle=20, gone=30)
        closed = self.run_tidy(tidy, [(0, up), (5, up + [self.GAME]), (24, up + [self.GAME]), (25, up + [self.GAME]),
                                      (40, up + [self.GAME])])
        self.assertEqual(closed, [False, False, False, True, False])
        self.assertEqual(self.stopped, [[1, 2, 3]])
        self.assertEqual(self.notified, [])

    def test_battle_net_beside_a_game_signing_in_with_its_token_is_left_open(self):
        d4 = (6, ["C:\\Program Files (x86)\\Diablo IV\\Diablo IV.exe", "-nostreamline", "-sso", "-launch", "-uid", "fenris"])
        up = [self.CLIENT, self.PAGE, self.AGENT]
        tidy = BNET["Tidy"](settle=20, gone=30)
        closed = self.run_tidy(tidy, [(0, up + [d4]), (25, up + [d4]), (30, [self.CLIENT, self.PAGE, d4]),
                                      (100, [self.CLIENT, self.PAGE, d4])])
        self.assertEqual(closed, [False, False, False, False])
        self.assertEqual(self.stopped, [])
        self.assertEqual(self.notified, [])

    def test_what_battle_net_left_beside_such_a_game_once_closed_itself_is_stopped(self):
        d4 = (6, ["C:\\Program Files (x86)\\Diablo IV\\Diablo IV.exe", "-sso", "-launch", "-uid", "fenris"])
        tidy = BNET["Tidy"](settle=20, gone=30)
        self.run_tidy(tidy, [(0, [self.CLIENT, self.AGENT, d4]), (5, [self.AGENT, d4]), (20, [self.AGENT, d4])])
        self.assertEqual(self.stopped, [[3]])

    def test_what_battle_net_did_not_launch_closes_nothing(self):
        up = [self.CLIENT, self.AGENT]
        redist = (6, ["C:\\Program Files (x86)\\Diablo IV\\vc_redist.x64.exe", "/install"])
        tidy = BNET["Tidy"](settle=0, gone=30)
        self.assertEqual(self.run_tidy(tidy, [(0, up + [redist]), (100, up + [redist])]), [False, False])
        self.assertEqual(self.stopped, [])

    def test_a_game_that_exits_and_starts_again_counts_afresh(self):
        tidy = BNET["Tidy"](settle=20, gone=30)
        up = [self.CLIENT, self.AGENT]
        self.run_tidy(tidy, [(0, up + [self.GAME]), (20, up + [self.GAME]), (30, up), (40, up + [self.GAME]),
                             (55, up + [self.GAME]), (60, up + [self.GAME])])
        self.assertEqual(self.stopped, [[1, 3], [1, 3]])

    def test_battle_net_outliving_its_agent_is_closed(self):
        tidy = BNET["Tidy"](settle=20, gone=30)
        with_agent = [self.CLIENT, self.PAGE, self.AGENT, self.WINE]
        without = [self.CLIENT, self.PAGE, self.WINE]
        closed = self.run_tidy(tidy, [(0, with_agent), (10, without), (39, without), (40, without), (41, without)])
        self.assertEqual(closed, [False, False, False, True, False])
        self.assertEqual(self.stopped, [[1, 2]])
        self.assertEqual(self.notified, ["Battle.net was closed"])

    def test_an_agent_back_within_the_grace_keeps_battle_net(self):
        tidy = BNET["Tidy"](settle=20, gone=30)
        with_agent = [self.CLIENT, self.AGENT]
        without = [self.CLIENT]
        self.run_tidy(tidy, [(0, with_agent), (10, without), (35, with_agent), (50, without), (79, without)])
        self.assertEqual(self.stopped, [])

    def test_a_battle_net_still_starting_its_agent_is_left_alone(self):
        tidy = BNET["Tidy"](settle=20, gone=30)
        self.run_tidy(tidy, [(0, [self.CLIENT]), (100, [self.CLIENT]), (1000, [self.CLIENT, self.WINE])])
        self.assertEqual(self.stopped, [])

    def test_a_closed_battle_net_starts_its_count_over(self):
        tidy = BNET["Tidy"](settle=20, gone=30)
        self.run_tidy(tidy, [(0, [self.CLIENT, self.AGENT]), (10, [self.AGENT]), (20, [self.CLIENT]), (100, [self.CLIENT])])
        self.assertEqual(self.stopped, [])


class FexRequestTest(Temp):
    def answer(self, text, delay=0.2):
        def respond():
            request = self.tmp / BNET["FEX_REQUEST"]
            deadline = time.monotonic() + 5
            while not request.exists() and time.monotonic() < deadline:
                time.sleep(0.02)
            self.requested = request.read_text()
            request.unlink()
            time.sleep(delay)
            (self.tmp / (BNET["FEX_RESULT"] + ".tmp")).write_text(text + "\n")
            os.replace(self.tmp / (BNET["FEX_RESULT"] + ".tmp"), self.tmp / BNET["FEX_RESULT"])
        thread = threading.Thread(target=respond)
        thread.start()
        self.addCleanup(thread.join)

    def test_the_apps_answer_comes_back(self):
        (self.tmp / BNET["FEX_RESULT"]).write_text("ok an-old-answer\n")
        self.answer("ok FEXCore-2610-d2rfix28-linux.wcp")
        self.assertEqual(BNET["ask_fex"](str(self.tmp), wait=5), "ok FEXCore-2610-d2rfix28-linux.wcp")
        self.assertEqual(self.requested, "appid=%s\n" % BNET["APP_ID"])
        self.assertFalse((self.tmp / BNET["FEX_RESULT"]).exists())

    def test_no_answer_or_no_session_starts_without_one(self):
        with mock.patch.dict(BNET, {"say": lambda message: None}):
            self.assertIsNone(BNET["ask_fex"](str(self.tmp), wait=0.3))
            self.assertIsNone(BNET["ask_fex"](None))
            self.assertIsNone(BNET["ask_fex"](str(self.tmp / "gone")))


class OpenTest(Temp):
    def setUp(self):
        super().setUp()
        self.root = str(self.tmp / "Steam")
        self.calls = []
        self.alive = []
        self.menu = self.tmp / "droiddeck-battlenet.desktop"
        patcher = mock.patch.dict(BNET, {
            "ask_fex": lambda launch_dir, wait=0: "ok fex.wcp",
            "say": lambda message: None, "notify": lambda *a, **k: self.calls.append(("notify", a[0])),
            "wine_processes": lambda pfx, proc="/proc": list(self.alive),
            "stop": lambda processes, wait=0: self.calls.append(("stop", [pid for pid, _ in processes])) or self.alive.clear(),
            "write_menu": lambda path=None: self.calls.append(("menu",)),
            "START_GRACE": 0, "POLL": 0,
        })
        patcher.start()
        self.addCleanup(patcher.stop)

    def client(self):
        return self.write("Steam/steamapps/compatdata/%s/pfx/drive_c/Program Files (x86)/Battle.net/Battle.net Launcher.exe"
                          % BNET["APP_ID"], b"MZ")

    def proton(self, effects):
        effects = iter(effects)

        def fake(program, args=(), verb=None, watch=None, poll=0):
            status, seen, side = next(effects)
            self.calls.append(("proton", os.path.basename(program), verb))
            if side:
                side()
            return status, seen
        return fake

    def test_an_installed_client_opens_and_the_start_waits_for_it(self):
        self.client()
        with mock.patch.dict(BNET, {"proton": self.proton([(0, True, None)])}):
            self.assertEqual(BNET["open_client"](self.root), 0)
        self.assertEqual(self.calls, [("menu",), ("proton", "Battle.net Launcher.exe", None)])

    def test_what_a_closed_battle_net_left_is_stopped_first(self):
        self.client()
        self.alive.append((40, ["C:\\ProgramData\\Battle.net\\Agent\\Agent.exe"]))
        with mock.patch.dict(BNET, {"proton": self.proton([(0, True, None)])}):
            self.assertEqual(BNET["open_client"](self.root), 0)
        self.assertEqual(self.calls[:2], [("menu",), ("stop", [40])])

    def test_without_a_client_the_installer_runs_and_hands_over(self):
        setup = self.write("cache/Battle.net-Setup.exe", b"MZ")
        with mock.patch.dict(BNET, {"download": lambda: str(setup), "proton": self.proton([(0, True, self.client)])}):
            self.assertEqual(BNET["open_client"](self.root), 0)
        self.assertEqual([c for c in self.calls if c[0] != "notify"], [("menu",), ("proton", "Battle.net-Setup.exe", None)])
        config = Path(self.root) / ("steamapps/compatdata/%s/pfx/drive_c/users/steamuser/AppData/Roaming/Battle.net/Battle.net.config"
                                    % BNET["APP_ID"])
        self.assertTrue(config.is_file())

    def test_an_installer_that_did_not_start_it_is_followed_by_the_launcher(self):
        setup = self.write("cache/Battle.net-Setup.exe", b"MZ")
        effects = [(0, False, self.client), (0, True, None)]
        with mock.patch.dict(BNET, {"download": lambda: str(setup), "proton": self.proton(effects)}):
            self.assertEqual(BNET["open_client"](self.root), 0)
        self.assertEqual([c[1] for c in self.calls if c[0] == "proton"], ["Battle.net-Setup.exe", "Battle.net Launcher.exe"])

    def test_a_failed_install_says_so(self):
        setup = self.write("cache/Battle.net-Setup.exe", b"MZ")
        with mock.patch.dict(BNET, {"download": lambda: str(setup), "proton": self.proton([(1, False, None)])}):
            self.assertEqual(BNET["open_client"](self.root), 1)
        self.assertIn(("notify", "Battle.net did not install"), self.calls)

    def test_no_download_is_no_install(self):
        with mock.patch.dict(BNET, {"download": lambda: None, "proton": self.proton([])}):
            self.assertEqual(BNET["open_client"](self.root), 1)
        self.assertIn(("notify", "Battle.net could not be downloaded"), self.calls)
        self.assertEqual(self.calls[0], ("menu",))

    def test_a_cut_off_installer_download_is_not_kept(self):
        import http.client
        import io

        class Response(io.BytesIO):
            def __init__(self, body, length):
                super().__init__(body)
                self.headers = {"Content-Length": str(length)}

        folder = str(self.tmp / "dl")
        body = b"MZ" + b"\0" * (BNET["MIN_INSTALLER"] + 10)
        with mock.patch.object(BNET["urllib"].request, "urlopen", lambda *a, **k: Response(body, len(body) + 100)), \
                mock.patch.object(BNET["shutil"], "which", lambda name: None):
            self.assertIsNone(BNET["download"]("https://example.invalid/setup.exe", folder))
        self.assertEqual(os.listdir(folder), [])

        def broken(*a, **k):
            raise http.client.IncompleteRead(b"MZ")
        with mock.patch.object(BNET["urllib"].request, "urlopen", broken), \
                mock.patch.object(BNET["shutil"], "which", lambda name: None):
            self.assertIsNone(BNET["download"]("https://example.invalid/setup.exe", folder))
        self.assertEqual(os.listdir(folder), [])

        with mock.patch.object(BNET["urllib"].request, "urlopen", lambda *a, **k: Response(body, len(body))):
            self.assertEqual(BNET["download"]("https://example.invalid/setup.exe", folder), os.path.join(folder, BNET["SETUP"]))

    def test_a_client_that_never_starts_is_reported(self):
        self.client()
        with mock.patch.dict(BNET, {"proton": self.proton([(3, False, None)])}):
            self.assertEqual(BNET["open_client"](self.root), 3)
        self.assertIn(("notify", "Battle.net did not start"), self.calls)

    def test_a_second_start_shows_the_open_window_and_returns(self):
        client = self.write("Steam/steamapps/compatdata/%s/pfx/drive_c/Program Files (x86)/Battle.net/Battle.net.exe"
                            % BNET["APP_ID"], b"MZ")
        self.alive.extend([(50, [str(client)]), (51, ["C:\\ProgramData\\Battle.net\\Agent\\Agent.9824\\Agent.exe"])])
        started = []
        with mock.patch.object(BNET["subprocess"], "Popen", lambda cmd, **kw: started.append((cmd, kw)) or mock.Mock()), \
                mock.patch.dict(os.environ, {"BL_DESKTOP": "1"}), \
                mock.patch.dict(BNET, {"CACHE": str(self.tmp / "cache"), "acquire": lambda: self.fail("took the lock")}):
            self.assertIsNone(BNET["show"](self.root, wait=60, poll=0))
        cmd, kw = started[0]
        self.assertEqual(cmd, [BNET["PROTON_RUN"], "--window", "--prefix", BNET["APP_ID"], "--verb", "run", str(client)])
        self.assertTrue(kw["start_new_session"])
        self.assertEqual(kw["stdin"], BNET["subprocess"].DEVNULL)
        self.assertEqual(kw["stdout"].name, str(self.tmp / "cache/spawned.log"))
        self.assertIs(kw["stderr"], kw["stdout"])

    def test_a_battle_net_without_its_agent_is_not_handed_the_start(self):
        self.write("Steam/steamapps/compatdata/%s/pfx/drive_c/Program Files (x86)/Battle.net/Battle.net.exe"
                   % BNET["APP_ID"], b"MZ")
        self.alive.append((50, ["C:\\Program Files (x86)\\Battle.net\\Battle.net.exe"]))
        tries = iter([None, None, "the lock"])
        with mock.patch.dict(BNET, {"acquire": lambda: next(tries), "spawn": lambda *a, **k: self.fail("handed over")}):
            self.assertEqual(BNET["show"](self.root, wait=60, poll=0), "the lock")

    def test_a_second_start_while_the_first_ends_opens_battle_net_itself(self):
        tries = iter([None, None, "the lock"])
        with mock.patch.dict(BNET, {"acquire": lambda: next(tries)}):
            self.assertEqual(BNET["show"](self.root, wait=60, poll=0), "the lock")

    def test_a_second_start_gives_up_when_the_first_neither_opens_nor_ends(self):
        with mock.patch.dict(BNET, {"acquire": lambda: None}):
            self.assertIsNone(BNET["show"](self.root, wait=0, poll=0))
        self.assertEqual(self.calls[-1], ("notify", "Battle.net is busy"))

    def test_a_game_in_the_prefix_is_left_running_and_battle_net_starts_beside_it(self):
        self.client()
        self.alive.append((41, ["C:\\Program Files (x86)\\Overwatch\\_retail_\\Overwatch.exe"]))
        with mock.patch.dict(BNET, {"proton": self.proton([(0, True, None)])}):
            self.assertEqual(BNET["open_client"](self.root), 0)
        self.assertNotIn("stop", [c[0] for c in self.calls])
        self.assertIn(("proton", "Battle.net Launcher.exe", "run"), self.calls)

    def test_one_start_at_a_time(self):
        first = BNET["acquire"](str(self.tmp / "lock"))
        self.assertIsNotNone(first)
        self.assertIsNone(BNET["acquire"](str(self.tmp / "lock")))
        first.close()
        again = BNET["acquire"](str(self.tmp / "lock"))
        self.assertIsNotNone(again)
        again.close()

    def test_status_says_where_it_is(self):
        self.client()
        status = BNET["status"](self.root)
        self.assertEqual((status["appid"], status["installed"], status["running"]), (int(BNET["APP_ID"]), True, False))
        self.assertTrue(status["launcher"].endswith("Battle.net/Battle.net Launcher.exe"))


class Harness(Temp):
    CLIENT = ["C:\\Program Files (x86)\\Battle.net\\Battle.net.exe"]
    GAME = ["C:\\Program Files (x86)\\Diablo IV\\Diablo IV.exe"]
    AGENT = ["C:\\ProgramData\\Battle.net\\Agent\\Agent.9824\\Agent.exe"]

    class Lock:
        def __init__(self):
            self.closed = False

        def close(self):
            self.closed = True

        def __enter__(self):
            return self

        def __exit__(self, *exc):
            self.close()

    class Proton:
        def wait(self, timeout=None):
            return 0

    def setUp(self):
        super().setUp()
        env = mock.patch.dict(os.environ)
        env.start()
        self.addCleanup(env.stop)
        self.root = str(self.tmp / "Steam")
        self.calls = []
        self.lock = self.Lock()
        self.states = []
        self.last = []
        patcher = mock.patch.dict(BNET, {
            "ask_fex": lambda launch_dir, wait=0: self.calls.append(("fex",)) or "ok fex.wcp",
            "say": lambda message: None, "notify": lambda *a, **k: self.calls.append(("notify", a[0])),
            "wine_processes": self.processes,
            "stop": lambda processes, wait=0: self.calls.append(("stop", sorted(pid for pid, _ in processes))),
            "start": lambda exe, verb=None: self.calls.append(("start", os.path.basename(exe), verb)) or self.Proton(),
            "spawn": lambda program, verb, args=(): self.calls.append(("spawn", os.path.basename(program), verb, list(args))),
            "acquire": lambda folder=None: self.lock,
            "POLL": 0, "READY_SETTLE": 0, "GAME_GRACE": 0, "CLIENT_WAIT": 0,
        })
        patcher.start()
        self.addCleanup(patcher.stop)

    def processes(self, pfx, proc="/proc"):
        if self.states:
            self.last = self.states.pop(0)
        return list(self.last)

    def install(self):
        for name in ("Battle.net Launcher.exe", "Battle.net.exe"):
            self.write("Steam/steamapps/compatdata/%s/pfx/drive_c/Program Files (x86)/Battle.net/%s" % (BNET["APP_ID"], name), b"MZ")

    def clients(self, n=5, first=10):
        return [(first + i, self.CLIENT + (["--type=renderer"] if i else [])) for i in range(n)]

class LaunchTest(Harness):
    def test_battle_net_is_opened_for_the_game_and_closed_after_it(self):
        self.install()
        up = self.clients() + [(30, self.AGENT)]
        self.states = [[], [], up, up + [(40, self.GAME)], up, up]
        self.assertEqual(BNET["launch"](self.root, "Fen"), 0)
        self.assertEqual(self.calls, [
            ("fex",), ("start", "Battle.net Launcher.exe", None),
            ("spawn", "Battle.net.exe", "run", ["--exec=launch Fen"]),
            ("stop", [10, 11, 12, 13, 14, 30]),
        ])
        self.assertTrue(self.lock.closed)

    def test_battle_net_is_closed_once_the_game_is_up(self):
        self.install()
        up = self.clients() + [(30, self.AGENT)]
        game = (40, self.GAME + ["-launch", "-uid", "fenris"])
        wine = (50, ["C:\\windows\\system32\\services.exe"])
        self.states = [[], [], up, up + [game, wine], [game, wine], [game, wine], [wine], [wine]]
        with mock.patch.dict(BNET, {"GAME_SETTLE": 0}):
            self.assertEqual(BNET["launch"](self.root, "Fen"), 0)
        self.assertEqual(self.calls, [
            ("fex",), ("start", "Battle.net Launcher.exe", None),
            ("spawn", "Battle.net.exe", "run", ["--exec=launch Fen"]),
            ("stop", [10, 11, 12, 13, 14, 30]),
            ("stop", [50]),
        ])

    def test_a_battle_net_opened_for_a_game_closes_itself_as_it_launches_it(self):
        self.install()
        self.states = [[], [], [], []]
        BNET["launch"](self.root, "Fen")
        config = Path(self.root) / ("steamapps/compatdata/%s/pfx/drive_c/users/steamuser/AppData/Roaming/Battle.net/Battle.net.config"
                                    % BNET["APP_ID"])
        self.assertEqual(json.loads(config.read_text())["Client"]["GameLaunchWindowBehavior"], "2")

    def test_battle_net_beside_a_game_signing_in_with_its_token_closes_only_after_it(self):
        self.install()
        up = self.clients() + [(30, self.AGENT)]
        d4 = (40, self.GAME + ["-sso", "-launch", "-uid", "fenris"])
        self.states = [[], [], up, up + [d4], up + [d4], up + [d4], up, up]
        with mock.patch.dict(BNET, {"GAME_SETTLE": 0}):
            self.assertEqual(BNET["launch"](self.root, "Fen"), 0)
        self.assertEqual([c for c in self.calls if c[0] == "stop"], [("stop", [10, 11, 12, 13, 14, 30])])

    def test_battle_net_closing_itself_as_it_launches_the_game_is_not_an_end(self):
        game = (40, self.GAME + ["-uid", "fenris"])
        self.states = [[], [game], [game], [], []]
        times = iter([0, 5, 6, 10, 30])
        with mock.patch.object(BNET["time"], "monotonic", lambda: next(times)):
            self.assertTrue(BNET["follow"]("/pfx", poll=0, grace=15))

    def test_battle_net_that_only_selected_the_game_is_asked_again(self):
        up = self.clients()
        game = (40, self.GAME + ["-uid", "s1"])
        self.states = [up, up, up, up + [game], [game], [], []]
        asks = []
        times = iter([0, 16, 32, 33, 34, 50, 70])
        with mock.patch.object(BNET["time"], "monotonic", lambda: next(times)):
            self.assertTrue(BNET["follow"]("/pfx", poll=0, grace=15, ask=lambda: asks.append(1)))
        self.assertEqual(len(asks), 2)

    def test_battle_net_is_asked_at_most_three_times(self):
        up = self.clients()
        self.states = [up] * 6 + [[], []]
        asks = []
        times = iter([0, 20, 40, 60, 80, 100, 101, 130])
        with mock.patch.object(BNET["time"], "monotonic", lambda: next(times)):
            self.assertFalse(BNET["follow"]("/pfx", poll=0, grace=15, ask=lambda: asks.append(1)))
        self.assertEqual(len(asks), 2)

    def test_the_wallpaper_is_droiddecks_logo_on_its_dark_gradient(self):
        bmp = BNET["wallpaper_bmp"](192, 108)
        self.assertEqual(bmp[:2], b"BM")
        self.assertEqual(int.from_bytes(bmp[2:6], "little"), len(bmp))
        self.assertEqual((int.from_bytes(bmp[18:22], "little"), int.from_bytes(bmp[22:26], "little")), (192, 108))

        def pixel(x, y):
            at = 54 + (107 - y) * 192 * 3 + x * 3
            return tuple(bmp[at + 2 - i] for i in range(3))
        self.assertEqual(pixel(95, 54), (26, 159, 255))
        self.assertTrue(all(c < 16 for c in pixel(0, 0)))

    def test_battle_nets_desktop_shows_the_wallpaper(self):
        self.install()
        pfx = Path(self.root) / ("steamapps/compatdata/%s/pfx" % BNET["APP_ID"])
        (pfx / "user.reg").write_text("WINE REGISTRY Version 2\n")
        with mock.patch.dict(os.environ, {"BL_WIDTH": "160", "BL_HEIGHT": "90"}), \
                mock.patch.dict(BNET, {"wallpaper_bmp": lambda w, h: b"BM%dx%d" % (w, h)}):
            self.assertTrue(BNET["wallpaper"](self.root))
            self.assertTrue(BNET["wallpaper"](self.root))
        image = pfx / "drive_c/users/steamuser/AppData/Local/DroidDeck/wallpaper-160x90.bmp"
        self.assertEqual(image.read_bytes(), b"BM160x90")
        reg = (pfx / "user.reg").read_text()
        self.assertEqual(reg.count('"Wallpaper"="C:\\\\users\\\\steamuser\\\\AppData\\\\Local\\\\DroidDeck\\\\wallpaper-160x90.bmp"'), 1)
        self.assertIn('[Control Panel\\\\Desktop]', reg)
        self.assertIn('"Background"="4 6 11"', reg)
        self.assertIn('"WallpaperStyle"="0"', reg)

    def test_a_large_desktop_gets_a_stretched_smaller_wallpaper(self):
        self.install()
        pfx = Path(self.root) / ("steamapps/compatdata/%s/pfx" % BNET["APP_ID"])
        (pfx / "user.reg").write_text("WINE REGISTRY Version 2\n")
        with mock.patch.dict(os.environ, {"BL_WIDTH": "2560", "BL_HEIGHT": "1600"}), \
                mock.patch.dict(BNET, {"wallpaper_bmp": lambda w, h: b"BM%dx%d" % (w, h)}):
            self.assertTrue(BNET["wallpaper"](self.root))
        image = pfx / "drive_c/users/steamuser/AppData/Local/DroidDeck/wallpaper-1152x720.bmp"
        self.assertEqual(image.read_bytes(), b"BM1152x720")
        self.assertIn('"WallpaperStyle"="2"', (pfx / "user.reg").read_text())

    def test_an_open_battle_net_is_handed_the_game(self):
        self.install()
        up = self.clients()
        self.states = [up, up, up + [(40, self.GAME)], up]
        self.assertEqual(BNET["launch"](self.root, "OSI"), 0)
        self.assertEqual(self.calls, [("spawn", "Battle.net.exe", "run", ["--exec=launch OSI"])])

    def test_the_desktops_battle_net_holding_the_lock_is_handed_the_game(self):
        self.install()
        up = self.clients()
        self.states = [up, up + [(40, self.GAME)], up]
        with mock.patch.dict(BNET, {"acquire": lambda folder=None: None}):
            self.assertEqual(BNET["launch"](self.root, "Pro"), 0)
        self.assertEqual(self.calls, [("spawn", "Battle.net.exe", "run", ["--exec=launch Pro"])])

    def test_battle_net_closed_before_the_game_started(self):
        self.install()
        up = self.clients()
        self.states = [[], [], up, up, [(30, self.AGENT)], [(30, self.AGENT)]]
        self.assertEqual(BNET["launch"](self.root, "Fen"), 1)
        self.assertEqual(self.calls[-1], ("stop", [30]))

    def test_no_client_no_launch(self):
        self.assertEqual(BNET["launch"](self.root, "Fen"), 1)
        self.assertEqual(self.calls, [("notify", "Battle.net is not installed")])

    def test_a_battle_net_that_never_opens_launches_nothing(self):
        self.install()
        self.states = [[], [], []]
        self.assertEqual(BNET["launch"](self.root, "Fen"), 1)
        self.assertNotIn("spawn", [c[0] for c in self.calls])
        self.assertIn(("notify", "Battle.net did not start"), self.calls)

    def test_a_game_is_a_windows_program_neither_wines_nor_battle_nets(self):
        self.assertTrue(BNET["is_game"](self.GAME))
        self.assertTrue(BNET["is_game"](["c:/Program Files (x86)/Overwatch/_retail_/Overwatch.exe", "-uid", "prometheus"]))
        self.assertTrue(BNET["is_game"](["C:\\Program Files (x86)\\Diablo IV\\BlizzardError\\BlizzardError.exe"]))
        for argv in (self.CLIENT, self.AGENT, ["C:\\Program Files (x86)\\Battle.net\\Battle.net Launcher.exe"],
                     ["C:\\windows\\system32\\services.exe"], ["C:\\windows\\system32\\lsass.exe"], ["C:\\windows\\system32\\rundll32.exe", "setupapi"],
                     ["c:\\windows\\system32\\steam.exe", "x"], ["/x/files/bin/wineserver"], ["/usr/bin/python3", "proton"], []):
            with self.subTest(argv):
                self.assertFalse(BNET["is_game"](argv))

    def test_battle_net_shows_in_the_desktops_window_or_as_the_steam_launch(self):
        with mock.patch.dict(os.environ, {"BL_DESKTOP": "1", "SteamGameId": "123"}):
            self.assertEqual(BNET["proton_options"](), ["--window"])
        with mock.patch.dict(os.environ, {"SteamGameId": "11151176786366644224"}):
            os.environ.pop("BL_DESKTOP", None)
            self.assertEqual(BNET["proton_options"](), ["--game-id", "11151176786366644224"])
            os.environ["SteamGameId"] = "0"
            self.assertEqual(BNET["proton_options"](), [])

    def test_on_the_desktop_battle_net_restarts_on_the_gpu_unless_turned_off(self):
        with tempfile.TemporaryDirectory() as tmp:
            gpu = os.path.join(tmp, "droiddeck-gpu")
            with open(gpu, "w") as out:
                out.write("#!/bin/sh\n")
            os.chmod(gpu, 0o755)
            with mock.patch.dict(BNET, {"BIN": tmp}), mock.patch.dict(os.environ, {"BL_DESKTOP": "1"}):
                os.environ.pop("BL_DESKTOP_GPU", None)
                os.environ.pop("BL_DESKTOP_GPU_CHILD", None)
                self.assertEqual(BNET["desktop_gpu"](), gpu)
                for choice in ("off", "0", "No", "false"):
                    os.environ["BL_DESKTOP_GPU"] = choice
                    self.assertIsNone(BNET["desktop_gpu"]())
                os.environ["BL_DESKTOP_GPU"] = "on"
                os.environ["BL_DESKTOP_GPU_CHILD"] = "1"
                self.assertIsNone(BNET["desktop_gpu"]())
                os.environ.pop("BL_DESKTOP_GPU_CHILD")
                os.environ["BL_DESKTOP"] = "0"
                self.assertIsNone(BNET["desktop_gpu"]())
                os.environ["BL_DESKTOP"] = "1"
                os.chmod(gpu, 0o644)
                self.assertIsNone(BNET["desktop_gpu"]())

    def test_on_the_gpu_battle_net_fills_the_screen_instead_of_a_window(self):
        with mock.patch.dict(os.environ, {"BL_DESKTOP": "1", "BL_DESKTOP_GPU_CHILD": "1", "BL_WIDTH": "1280", "BL_HEIGHT": "720", "SteamGameId": "0"}):
            self.assertEqual(BNET["proton_options"](), ["--desktop", "1280x720"])

    def test_under_gamescope_battle_net_gets_a_desktop_of_the_screens_size(self):
        with mock.patch.dict(os.environ, {"BL_WIDTH": "1280", "BL_HEIGHT": "720", "SteamGameId": "0"}):
            os.environ.pop("BL_DESKTOP", None)
            self.assertEqual(BNET["proton_options"](), ["--desktop", "1280x720"])
            os.environ["BL_DESKTOP"] = "1"
            self.assertEqual(BNET["proton_options"](), ["--window"])

    def test_the_library_comes_before_the_action(self):
        seen = []
        self.addCleanup(BNET.__setitem__, "LIBRARY", None)
        with mock.patch.dict(BNET, {"launch": lambda root, code: seen.append((BNET["LIBRARY"], code)) or 0}):
            self.assertEqual(BNET["main"](["--library", "/root/Games/Stores/Battle.net", "launch", "WTCG"]), 0)
            self.assertEqual(BNET["main"](["--library", "Games", "launch", "WTCG"]), 2)
        self.assertEqual(seen, [("/root/Games/Stores/Battle.net", "WTCG")])

    def test_only_a_code_is_launched(self):
        with mock.patch.dict(BNET, {"launch": lambda root, code: self.calls.append(("launch", code)) or 0}):
            for argv in (["launch"], ["launch", "Fen;rm -rf /"], ["launch", "Fen", "x"], ["open", "x"], ["bogus"]):
                with self.subTest(argv):
                    self.assertEqual(BNET["main"](argv), 2)
            self.assertEqual(BNET["main"](["launch", "Fen"]), 0)
        self.assertEqual(self.calls, [("launch", "Fen")])
        self.assertEqual(os.environ.get("BL_BATTLENET"), "1")


class InstallGameTest(Harness):
    def setUp(self):
        super().setUp()
        BNET_PATCH = mock.patch.dict(BNET, {
            "open_client": lambda root, reinstall=False: self.calls.append(("open",)) or 0,
            "hand_over": lambda root, args, wait=None: self.calls.append(("hand_over", list(args))),
            "show": lambda root, wait=None, poll=None, args=(): self.calls.append(("show", list(args))),
        })
        BNET_PATCH.start()
        self.addCleanup(BNET_PATCH.stop)
        thread = mock.patch.object(BNET["threading"], "Thread", lambda target, args, daemon: mock.Mock(start=lambda: target(*args)))
        thread.start()
        self.addCleanup(thread.stop)

    def test_the_open_battle_net_is_asked(self):
        self.install()
        self.states = [self.clients() + [(30, self.AGENT)]]
        self.assertEqual(BNET["install_game"](self.root, "fenris"), 0)
        self.assertEqual(self.calls, [("spawn", "Battle.net.exe", "run", ["--install", "--game=fenris"])])
        self.assertFalse(self.lock.closed)

    def test_an_open_battle_net_without_its_agent_is_asked_once_it_has_one(self):
        self.install()
        self.states = [self.clients()]
        with mock.patch.dict(BNET, {"acquire": lambda folder=None: None}):
            self.assertEqual(BNET["install_game"](self.root, "fenris"), 0)
        self.assertEqual(self.calls, [("show", ["--install", "--game=fenris"])])

    def test_battle_net_is_opened_and_asked_once_it_is_up(self):
        self.install()
        self.assertEqual(BNET["install_game"](self.root, "osi"), 0)
        self.assertEqual(self.calls, [("hand_over", ["--install", "--game=osi"]), ("open",)])

    def test_without_battle_net_it_is_installed_first_then_asked(self):
        self.assertEqual(BNET["install_game"](self.root, "hs_beta"), 0)
        self.assertEqual(self.calls, [("hand_over", ["--install", "--game=hs_beta"]), ("open",)])

    def test_another_start_holding_the_lock_hands_it_on(self):
        with mock.patch.dict(BNET, {"acquire": lambda folder=None: None}):
            self.assertEqual(BNET["install_game"](self.root, "prometheus"), 0)
        self.assertEqual(self.calls, [("show", ["--install", "--game=prometheus"])])

    def test_only_an_id_is_asked_for(self):
        with mock.patch.dict(BNET, {"install_game": lambda root, uid: self.calls.append(("get", uid)) or 0}):
            for argv in (["install-game"], ["install-game", "Fenris"], ["install-game", "osi;rm"], ["install-game", "osi", "x"]):
                with self.subTest(argv):
                    self.assertEqual(BNET["main"](argv), 2)
            self.assertEqual(BNET["main"](["install-game", "hs_beta"]), 0)
        self.assertEqual(self.calls, [("get", "hs_beta")])


class HandOverTest(Harness):
    def test_once_battle_net_is_up_it_is_asked(self):
        self.install()
        self.states = [[], self.clients()]
        self.assertTrue(BNET["hand_over"](self.root, ["--install", "--game=osi"], wait=60))
        self.assertEqual(self.calls, [("spawn", "Battle.net.exe", "run", ["--install", "--game=osi"])])

    def test_a_battle_net_that_never_comes_up_is_not_asked(self):
        self.install()
        self.states = [[]]
        self.assertFalse(BNET["hand_over"](self.root, ["--install", "--game=osi"], wait=0))
        self.assertEqual(self.calls, [])


class AgentTest(Temp):
    def test_the_newest_agent_build_and_its_port_are_found(self):
        root = str(self.tmp / "Steam")
        agent = "Steam/steamapps/compatdata/%s/pfx/drive_c/ProgramData/Battle.net/Agent" % BNET["APP_ID"]
        self.write(agent + "/Agent.9100/Agent.exe", b"MZ")
        self.write(agent + "/Agent.9824/Agent.exe", b"MZ")
        os.makedirs(self.tmp / agent / "Agent.9999")
        self.write(agent + "/Agent.dat", "39211\n")
        self.assertTrue(BNET["agent_exe"](root).endswith("Agent.9824/Agent.exe"))
        self.assertEqual(BNET["agent_port"](root), 39211)
        self.write(agent + "/Agent.dat", "not a port")
        self.assertIsNone(BNET["agent_port"](root))

    def test_progress_keeps_the_fields_the_app_reads(self):
        status = {"progress": 0.35, "download_current": [2397, 0, 0], "download_total": [6746, 0, 0], "download_rate": 34076920,
                  "state": 1003, "build_config_kv_pairs": {"x": "y"}, "regional_version_info": {}, "estimated_time_in_sec": 120,
                  "extended_status": {"current": 1, "state": 500, "total": 2, "unit_type": "bytes"}}
        self.assertEqual(BNET["summary"](status), {"progress": 0.35, "download_current": 2397, "download_total": 6746,
                                                   "download_rate": 34076920, "state": 1003, "estimated_time_in_sec": 120, "phase": 500})

    def test_an_unknown_time_left_is_not_reported(self):
        for eta in (18446744073709551615, -1, 1.5):
            with self.subTest(eta):
                self.assertNotIn("estimated_time_in_sec", BNET["summary"]({"estimated_time_in_sec": eta}))

    def test_the_driver_script_reads_its_arguments_in_the_order_they_are_passed(self):
        script = BNET["AGENT_SCRIPT"]
        self.assertIn("port = a(0), action = a(1), uid = a(2), product = a(3), dir = a(4)", script)
        self.assertIn("lang = a(5), country = a(6), region = a(7), out = a(8)", script)
        self.assertIn('"--internalclienttools"', (BIN / "droiddeck-battlenet").read_text())


class AgentJobTest(Harness):
    def setUp(self):
        super().setUp()
        self.lines = []
        self.files = {}
        self.started = []
        self.envs = []
        test = self

        class Child:
            def __init__(self, cmd, **kwargs):
                test.started.append(cmd)
                test.envs.append(kwargs.get("env") or {})
                base = cmd[-1][2:].replace("\\", "/")
                for suffix, value in test.files.items():
                    with open(base + suffix, "wb") as f:
                        f.write(value.encode("utf-16"))
                self.returncode = 0

            def poll(self):
                return 0

        patcher = mock.patch.dict(BNET, {"emit": lambda kind, value="": self.lines.append((kind, value)),
                                         "stop_leftovers": lambda pfx: True, "CACHE": str(self.tmp / "cache")})
        patcher.start()
        self.addCleanup(patcher.stop)
        popen = mock.patch.object(BNET["subprocess"], "Popen", Child)
        popen.start()
        self.addCleanup(popen.stop)

    def install(self):
        super().install()
        self.write("Steam/steamapps/compatdata/%s/pfx/drive_c/ProgramData/Battle.net/Agent/Agent.9824/Agent.exe" % BNET["APP_ID"], b"MZ")

    def test_a_game_downloads_headless_and_reports_where_it_landed(self):
        self.install()
        folder = str(self.tmp / "Games/Battle.net/Warcraft Rumble")
        self.files = {
            ".json": json.dumps({"progress": 1.0, "download_complete": True, "state": 1004, "download_current": [6746, 0, 0],
                                 "download_total": [6746, 0, 0]}),
            ".game": json.dumps({"installed": True, "install_dir": "Z:" + folder, "local_version": "16.1.2.119725",
                                 "binaries": {"game": {"relative_path": "WarcraftRumble.exe"}}}),
        }
        code = BNET["agent_job"](self.root, "install", "gryphon", "gryphon", folder, "enUS", "USA", "US", poll=0)
        self.assertEqual(code, 0)
        self.assertTrue(os.path.isdir(folder))
        cmd = self.started[0]
        self.assertEqual(cmd[:6], ["gamescope", "--backend", "headless", "-w", "640", "-h"])
        self.assertEqual(cmd[cmd.index("--") + 2:-1], ["agent-serve", "install", "gryphon", "gryphon", "Z:" + folder, "enUS", "USA", "US"])
        self.assertEqual([kind for kind, _ in self.lines], ["STATE", "PROGRESS", "DONE"])
        self.assertEqual(json.loads(self.lines[-1][1]), {"install_dir": "Z:" + folder, "exe": "WarcraftRumble.exe", "version": "16.1.2.119725"})

    def test_an_agent_error_is_reported_with_its_code(self):
        self.install()
        self.files = {".json": json.dumps({"error": 2421, "progress": 0.0})}
        self.assertEqual(BNET["agent_job"](self.root, "install", "fenris", "fenris", str(self.tmp / "D4"), "enUS", "USA", "US", poll=0), 5)
        self.assertEqual(self.lines[-1], ("ERROR", "agent 2421"))

    def test_a_driver_failure_is_reported_with_its_reason(self):
        self.install()
        self.files = {".error": "the agent stopped answering"}
        self.assertEqual(BNET["agent_job"](self.root, "update", "wow", poll=0), 1)
        self.assertEqual(self.lines[-1], ("ERROR", "stopped the agent stopped answering"))

    def test_an_update_reports_the_new_version(self):
        self.install()
        self.files = {".json": json.dumps({"progress": 1.0}),
                      ".game": json.dumps({"installed": True, "playable": True, "install_dir": "Z:/g", "local_version": "2"})}
        self.assertEqual(BNET["agent_job"](self.root, "update", "gryphon", poll=0), 0)
        self.assertIn("agent-serve", self.started[0])
        self.assertEqual(json.loads(self.lines[-1][1])["version"], "2")

    def test_a_game_left_unplayable_is_not_reported_as_done(self):
        self.install()
        self.files = {".json": json.dumps({"state": 1008, "download_complete": True, "progress": 0.0}),
                      ".game": json.dumps({"installed": True, "playable": False, "install_dir": "Z:/g", "local_version": "2"})}
        self.assertEqual(BNET["agent_job"](self.root, "update", "gryphon", poll=0), 1)
        self.assertEqual(self.lines[-1][0], "ERROR")

    def test_the_driver_only_stops_once_the_agent_has_really_run(self):
        script = BNET["AGENT_SCRIPT"]
        self.assertIn("if (started && (state == 1004 || complete && /\"playable\"\\s*:\\s*true/.test(s))) break;", script)
        self.assertNotIn('"progress"', script)

    def test_an_uninstall_is_done_once_the_agent_no_longer_lists_it(self):
        self.install()
        self.files = {".json": json.dumps({}), ".game": json.dumps({"installed": False})}
        self.assertEqual(BNET["agent_job"](self.root, "uninstall", "gryphon", poll=0), 0)
        self.assertEqual(self.lines[-1], ("DONE", json.dumps({"uninstalled": "gryphon"})))

    def test_a_gamescope_that_cannot_start_is_reported(self):
        self.install()

        def missing(cmd, **kwargs):
            raise FileNotFoundError("gamescope")

        with mock.patch.object(BNET["subprocess"], "Popen", missing):
            self.assertEqual(BNET["agent_job"](self.root, "update", "gryphon", poll=0), 1)
        self.assertEqual(self.lines[-1], ("ERROR", "stopped gamescope: gamescope"))

    def test_an_agent_answer_that_is_not_an_object_is_ignored(self):
        path = self.tmp / "answer.json"
        for value in ([1, 2], "text", 3, None):
            with self.subTest(value):
                path.write_bytes(json.dumps(value).encode("utf-16"))
                self.assertIsNone(BNET["read_status"](str(path)))
        path.write_bytes(json.dumps({"state": 1004}).encode("utf-8"))
        self.assertEqual(BNET["read_status"](str(path)), {"state": 1004})

    def test_a_large_agent_log_is_rotated_before_a_job(self):
        self.install()
        os.makedirs(self.tmp / "cache")
        log = self.tmp / "cache" / "agent.log"
        log.write_bytes(b"x" * (BNET["AGENT_LOG_LIMIT"] + 1))
        self.files = {".game": json.dumps({"installed": False})}
        BNET["agent_job"](self.root, "uninstall", "gryphon", poll=0)
        self.assertEqual((self.tmp / "cache" / "agent.log.1").stat().st_size, BNET["AGENT_LOG_LIMIT"] + 1)
        self.assertEqual(log.stat().st_size, 0)

    def test_it_queues_behind_another_battle_net_job(self):
        self.install()
        self.files = {".game": json.dumps({"installed": False})}
        locks = iter([None, contextlib.nullcontext()])
        with mock.patch.dict(BNET, {"acquire": lambda *a: next(locks)}):
            self.assertEqual(BNET["agent_job"](self.root, "uninstall", "gryphon", poll=0), 0)
        self.assertEqual(self.lines[:2], [("STATE", "queued"), ("STATE", "starting")])

    def test_an_uninstall_needs_the_agent_to_say_the_game_is_gone(self):
        self.install()
        self.files = {".game": json.dumps({"error": 2})}
        self.assertEqual(BNET["agent_job"](self.root, "uninstall", "gryphon", poll=0), 1)
        self.assertEqual(self.lines[-1][0], "ERROR")
        self.files = {".game": json.dumps({})}
        self.assertEqual(BNET["agent_job"](self.root, "uninstall", "gryphon", poll=0), 0)
        self.assertEqual(self.lines[-1], ("DONE", json.dumps({"uninstalled": "gryphon"})))

    def test_without_battle_net_nothing_starts(self):
        self.assertEqual(BNET["agent_job"](self.root, "install", "gryphon", "gryphon", str(self.tmp / "g"), "enUS", "USA", "US", poll=0), 3)
        self.assertEqual(self.lines, [("ERROR", "client")])
        self.assertEqual(self.started, [])

    def test_it_waits_while_battle_net_or_a_game_is_open(self):
        self.install()
        self.states = [self.clients() + [(40, self.GAME)], [(40, self.GAME)], []]
        self.files = {".game": json.dumps({"installed": False})}
        self.assertEqual(BNET["agent_job"](self.root, "uninstall", "gryphon", poll=0), 0)
        self.assertEqual(self.lines[0], ("STATE", "waiting"))
        self.assertEqual(self.lines[1], ("STATE", "starting"))

    def test_gamescope_gets_no_display_but_the_agent_keeps_the_one_gamescope_gives_it(self):
        self.install()
        os.environ.update(DISPLAY=":5", WAYLAND_DISPLAY="wayland-1")
        self.files = {".game": json.dumps({"installed": False})}
        BNET["agent_job"](self.root, "uninstall", "gryphon", poll=0)
        self.assertNotIn("DISPLAY", self.envs[0])
        self.assertNotIn("WAYLAND_DISPLAY", self.envs[0])
        self.assertEqual(self.envs[0]["PROTON_USE_XALIA"], "0")
        os.environ["DISPLAY"] = ":0"
        self.assertEqual(BNET["agent_env"]()["DISPLAY"], ":0")

    def test_only_valid_arguments_start_a_job(self):
        calls = []
        with mock.patch.dict(BNET, {"agent_job": lambda *a, **k: calls.append(a[1:]) or 0}):
            for argv in (["agent-install", "gryphon", "gryphon", "relative/dir", "enUS", "USA", "US"],
                         ["agent-install", "gryphon", "Gry phon", "/g", "enUS", "USA", "US"],
                         ["agent-install", "gryphon", "gryphon", "/g", "english", "USA", "US"],
                         ["agent-update"], ["agent-uninstall", "Gryphon"]):
                with self.subTest(argv):
                    self.assertEqual(BNET["main"](argv), 2)
            self.assertEqual(BNET["main"](["agent-install", "gryphon", "gryphon", "/g/../g/Rumble", "enUS", "USA", "US"]), 0)
            self.assertEqual(BNET["main"](["agent-update", "wow_classic"]), 0)
        self.assertEqual(calls, [("install", "gryphon", "gryphon", "/g/Rumble", "enUS", "USA", "US"), ("update", "wow_classic")])


if __name__ == "__main__":
    unittest.main()
