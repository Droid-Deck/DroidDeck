import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest

SESSION = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-session"
SOURCE = SESSION.read_text()
START = SOURCE.index("    steam_other_game_running() {")
END = SOURCE.index("    steam_bring_forward() {", START)
FUNCTIONS = textwrap.dedent(SOURCE[START:END])
GAME = str((3333273269 << 32) | 0x02000000)


class SteamShortcutRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.launch = self.root / "session"
        self.launch.mkdir()
        self.bin = self.root / "bin"
        self.bin.mkdir()
        steam = self.root / "steam/steamrtarm64"
        steam.mkdir(parents=True)
        self.steam_log = self.root / "steam-args"
        (steam / "steam").write_text("#!/bin/sh\nprintf '%s\\n' \"$*\" >> \"$STEAM_LOG\"\n")
        (steam / "steam").chmod(0o755)
        self.agent = self.bin / "droiddeck-agent"
        self.agent.write_text(
            "#!/bin/sh\n"
            "n=$(cat \"$AGENT_COUNT\" 2>/dev/null || echo 0); n=$((n + 1)); echo $n > \"$AGENT_COUNT\"\n"
            "i=0; last=0\n"
            "for s in $AGENT_STATES; do i=$((i + 1)); last=$s; [ \"$i\" -eq \"$n\" ] && exit \"$s\"; done\n"
            "exit \"$last\"\n")
        self.agent.chmod(0o755)

    def launch_game(self, states, focusable="769", extra=None, client=None):
        xprop = self.bin / "xprop"
        xprop.write_text("#!/bin/sh\necho 'GAMESCOPE_FOCUSABLE_APPS(CARDINAL) = %s'\n" % focusable)
        xprop.chmod(0o755)
        script = "nap() { :; }\nsteam_root=%s\n%s%s\nsteam_launch_game %s\n" % (
            self.root / "steam", "steam_client=(%s --exec)\n" % client if client else "",
            FUNCTIONS.replace("/usr/local/bin/droiddeck-agent", str(self.agent)), GAME)
        env = dict(os.environ, HOME=str(self.root), BL_LAUNCH_DIR=str(self.launch),
                   PATH=str(self.bin) + os.pathsep + os.environ["PATH"], STEAM_LOG=str(self.steam_log),
                   AGENT_COUNT=str(self.root / "agent-count"), AGENT_STATES=states, **(extra or {}))
        result = subprocess.run(["bash", "-c", script], env=env, text=True, capture_output=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)
        steam = self.steam_log.read_text().splitlines() if self.steam_log.exists() else []
        restart = self.root / ".bl-restart-game"
        return steam, restart.read_text().strip() if restart.exists() else None, result.stdout

    def test_a_shortcut_that_stays_known_launches_once(self):
        steam, restart, _ = self.launch_game("0")
        self.assertEqual(["steam://rungameid/" + GAME], steam)
        self.assertIsNone(restart)
        self.assertEqual("9", (self.root / "agent-count").read_text().strip())

    def test_a_shortcut_dropped_while_launching_restarts_steam_for_it(self):
        steam, restart, out = self.launch_game("0 0 4")
        self.assertEqual(["steam://rungameid/" + GAME, "-shutdown"], steam)
        self.assertEqual(GAME, restart)
        self.assertIn("dropped shortcut", out)

    def test_the_running_x86_64_client_gets_the_launch_and_the_restart(self):
        x64 = self.bin / "droiddeck-steam-x64"
        x64_log = self.root / "x64-args"
        x64.write_text("#!/bin/sh\nprintf '%s\\n' \"$*\" >> \"$X64_LOG\"\n")
        x64.chmod(0o755)
        steam, restart, _ = self.launch_game("0 0 4", client=x64, extra={"X64_LOG": str(x64_log)})
        self.assertEqual([], steam)
        self.assertEqual(["--exec steam://rungameid/" + GAME, "--exec -shutdown"], x64_log.read_text().splitlines())
        self.assertEqual(GAME, restart)

    def test_a_shortcut_already_lost_restarts_steam_without_launching(self):
        steam, restart, out = self.launch_game("4")
        self.assertEqual(["-shutdown"], steam)
        self.assertEqual(GAME, restart)
        self.assertIn("lost shortcut", out)

    def test_steam_is_restarted_only_once_for_the_same_game(self):
        steam, restart, _ = self.launch_game("4")
        self.assertEqual(GAME, restart)
        (self.root / ".bl-restart-game").unlink()
        self.steam_log.unlink()
        (self.root / "agent-count").unlink()
        steam, restart, out = self.launch_game("4")
        self.assertEqual(["steam://rungameid/" + GAME], steam)
        self.assertIsNone(restart)
        self.assertIn("already restarted", out)

    def test_a_ready_shortcut_allows_a_later_restart_again(self):
        self.launch_game("4")
        (self.root / ".bl-restart-game").unlink()
        (self.root / "agent-count").unlink()
        self.launch_game("0")
        self.assertFalse((self.root / ".bl-restarted-for").exists())

    def test_a_running_game_is_never_cut_off_by_a_restart(self):
        steam, restart, _ = self.launch_game("0 4", focusable="769,3703311242")
        self.assertEqual(["steam://rungameid/" + GAME], steam)
        self.assertIsNone(restart)
        steam, restart, _ = self.launch_game("4", focusable="769,3703311242")
        self.assertIsNone(restart)

    def test_the_open_launcher_does_not_count_as_a_running_game(self):
        (self.launch / "epic-open").write_text("2702387189\n")
        steam, restart, _ = self.launch_game("0 4", focusable="769,2702387189")
        self.assertEqual(GAME, restart)

    def test_the_launcher_without_its_steam_id_still_does_not_count_as_a_running_game(self):
        (self.launch / "epic-open").write_text("1\n")
        launcher = {"BL_EPIC_APP_ID": "2702387189"}
        steam, restart, _ = self.launch_game("0 4", focusable="769,2702387189", extra=launcher)
        self.assertEqual(GAME, restart)
        for marker in (".bl-restart-game", ".bl-restarted-for", "agent-count"):
            (self.root / marker).unlink(missing_ok=True)
        steam, restart, _ = self.launch_game("4", focusable="769,2702387189,3703311242", extra=launcher)
        self.assertIsNone(restart)

    def test_steam_games_are_launched_without_watching(self):
        steam, restart, _ = self.launch_game("3")
        self.assertEqual(["steam://rungameid/" + GAME], steam)
        self.assertIsNone(restart)
        self.assertEqual("1", (self.root / "agent-count").read_text().strip())

    def check_exits(self, listings, states, beats):
        pgrep = self.bin / "pgrep"
        pgrep.write_text(
            "#!/bin/bash\n"
            "n=$(cat \"$PGREP_COUNT\" 2>/dev/null || echo 0); n=$((n + 1)); echo $n > \"$PGREP_COUNT\"\n"
            "IFS='|' read -r -a runs <<< \"$PGREP_RUNS\"\n"
            "line=${runs[$((n - 1))]:-}\n"
            "[ -n \"$line\" ] || exit 1\n"
            "printf '%s\\n' \"$line\"\n")
        pgrep.chmod(0o755)
        script = "steam_root=%s\n%s\ndeclare -A _bl_ran=()\n_bl_exited=\n%s" % (
            self.root / "steam", FUNCTIONS.replace("/usr/local/bin/droiddeck-agent", str(self.agent)),
            "steam_check_exits\n" * beats)
        env = dict(os.environ, HOME=str(self.root), BL_LAUNCH_DIR=str(self.launch),
                   PATH=str(self.bin) + os.pathsep + os.environ["PATH"], STEAM_LOG=str(self.steam_log),
                   AGENT_COUNT=str(self.root / "agent-count"), AGENT_STATES=states,
                   PGREP_COUNT=str(self.root / "pgrep-count"), PGREP_RUNS="|".join(listings))
        result = subprocess.run(["bash", "-c", script], env=env, text=True, capture_output=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)
        steam = self.steam_log.read_text().splitlines() if self.steam_log.exists() else []
        agent = self.root / "agent-count"
        return steam, (self.root / ".bl-restart-lost").exists(), int(agent.read_text()) if agent.exists() else 0, result.stdout

    RUNNING = "41 /root/.local/share/Steam/steamrtarm64/reaper SteamLaunch AppId=3333273269 -- /x/.droiddeck-launch.bat"

    def test_a_shortcut_dropped_when_its_game_exits_restarts_steam_once_nothing_runs(self):
        steam, marker, asked, out = self.check_exits([self.RUNNING, "", "", ""], "4", 3)
        self.assertEqual(["-shutdown"], steam)
        self.assertTrue(marker)
        self.assertEqual(1, asked)
        self.assertIn("3333273269", out)

    def test_a_shortcut_still_listed_after_its_game_exits_is_left_alone(self):
        steam, marker, asked, _ = self.check_exits([self.RUNNING, "", "", ""], "0", 4)
        self.assertEqual([], steam)
        self.assertFalse(marker)
        self.assertEqual(1, asked)

    def test_the_check_waits_a_quiet_beat_after_the_exit(self):
        steam, _, asked, _ = self.check_exits([self.RUNNING, ""], "4", 2)
        self.assertEqual([], steam)
        self.assertEqual(0, asked)

    def test_a_game_started_meanwhile_is_never_cut_off(self):
        steam, marker, _, _ = self.check_exits([self.RUNNING, "", "", self.RUNNING], "4", 3)
        self.assertEqual([], steam)
        self.assertFalse(marker)

    def test_steam_games_are_not_checked(self):
        game = "41 /root/.local/share/Steam/steamrtarm64/reaper SteamLaunch AppId=1245620 -- /x/eldenring.exe"
        steam, _, asked, _ = self.check_exits([game, "", "", ""], "4", 3)
        self.assertEqual([], steam)
        self.assertEqual(0, asked)


if __name__ == "__main__":
    unittest.main()
