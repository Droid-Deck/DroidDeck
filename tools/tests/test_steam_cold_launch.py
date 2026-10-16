"""Cold shortcut launches wait for Steam's CGameID-to-app overview mapping."""
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest

SESSION = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-session"
SOURCE = SESSION.read_text()
START = SOURCE.index("    steam_hold_launch() {")
END = SOURCE.index('    if [ -s "$HOME/.bl-autolaunch" ]; then', START)
assert START >= 0
LAUNCH_BLOCK = textwrap.dedent(SOURCE[START:END])


class SteamColdLaunchTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.launch_dir = self.root / "session"
        self.launch_dir.mkdir()
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.agent_args = self.root / "agent-args"
        self.sleep_args = self.root / "sleep-args"
        self.agent_count = self.root / "agent-count"

        xprop = self.bin / "xprop"
        xprop.write_text("#!/bin/sh\necho 'GAMESCOPE_FOCUSED_APP(CARDINAL) = 769'\n")
        xprop.chmod(0o755)
        agent = self.bin / "droiddeck-agent"
        agent.write_text(
            "#!/bin/sh\n"
            "printf '%s\\n' \"$@\" > \"$AGENT_ARGS\"\n"
            "n=$(cat \"$AGENT_COUNT\" 2>/dev/null || echo 0); n=$((n + 1)); echo $n > \"$AGENT_COUNT\"\n"
            "i=0; last=1\n"
            "for s in $AGENT_STATUS; do i=$((i + 1)); last=$s; [ \"$i\" -eq \"$n\" ] && exit \"$s\"; done\n"
            "exit \"$last\"\n")
        agent.chmod(0o755)
        sleep = self.bin / "sleep"
        sleep.write_text("#!/bin/sh\nprintf '%s\\n' \"$@\" >> \"$SLEEP_ARGS\"\n")
        sleep.chmod(0o755)
        self.block = LAUNCH_BLOCK.replace("/usr/local/bin/droiddeck-agent", str(agent))

    def run_launch(self, target, helper_status):
        env = dict(os.environ, HOME=str(self.root), BL_LAUNCH_DIR=str(self.launch_dir),
                   PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                   AGENT_ARGS=str(self.agent_args), SLEEP_ARGS=str(self.sleep_args),
                   AGENT_COUNT=str(self.agent_count), AGENT_STATUS=str(helper_status))
        script = self.block + "\nprintf 'ARG:%s\\n' \"$@\"\nwait\n"
        url = target if target.startswith("steam://") else "steam://rungameid/" + target
        result = subprocess.run(["bash", "-c", script, "cold-launch", url],
                                env=env, text=True, capture_output=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)
        return result

    def test_shortcut_overview_gate_releases_original_64_bit_cgameid_without_fixed_sleep(self):
        game_id = str((3551182975 << 32) | 0x02000000)
        result = self.run_launch(game_id, 0)
        self.assertEqual("steam-game", (self.launch_dir / "steam-game").name)
        self.assertEqual(game_id, (self.launch_dir / "steam-game").read_text().strip())
        self.assertEqual(["--wait-steam-shortcut-overview", game_id, "45"], self.agent_args.read_text().splitlines())
        self.assertFalse(self.sleep_args.exists(), result.stdout)

    def test_native_app_id_keeps_legacy_bounded_launch_path(self):
        game_id = "413150"
        self.run_launch(game_id, 3)
        self.assertEqual(game_id, (self.launch_dir / "steam-game").read_text().strip())
        self.assertEqual(["--wait-steam-shortcut-overview", game_id, "45"], self.agent_args.read_text().splitlines())
        self.assertEqual("4", self.sleep_args.read_text().strip())

    def test_a_shortcut_whose_overview_never_appears_is_launched_after_a_bounded_wait(self):
        game_id = str((3551182975 << 32) | 0x02000000)
        result = self.run_launch(game_id, 1)
        self.assertEqual(game_id, (self.launch_dir / "steam-game").read_text().strip(), result.stdout)
        self.assertEqual(["--wait-steam-shortcut-overview", game_id, "45"], self.agent_args.read_text().splitlines())
        self.assertEqual("2", self.agent_count.read_text().strip())
        self.assertEqual(["2", "2"], self.sleep_args.read_text().split())
        self.assertIn("launching the requested game anyway", result.stdout)

    def test_a_late_overview_is_still_waited_for_once_more(self):
        game_id = str((3551182975 << 32) | 0x02000000)
        result = self.run_launch(game_id, "1 0")
        self.assertEqual(game_id, (self.launch_dir / "steam-game").read_text().strip(), result.stdout)
        self.assertEqual("2", self.agent_count.read_text().strip())
        self.assertEqual(["2"], self.sleep_args.read_text().split())
        self.assertIn("attempt 1 of 2", result.stdout)
        self.assertNotIn("anyway", result.stdout)

    def test_an_agent_failure_other_than_a_timeout_launches_without_retrying(self):
        game_id = str((3551182975 << 32) | 0x02000000)
        result = self.run_launch(game_id, 2)
        self.assertEqual(game_id, (self.launch_dir / "steam-game").read_text().strip(), result.stdout)
        self.assertEqual("1", self.agent_count.read_text().strip())
        self.assertFalse(self.sleep_args.exists())

    def test_a_restart_for_a_game_waits_for_its_overview_too(self):
        game_id = str((3551182975 << 32) | 0x02000000)
        loop = SOURCE[SOURCE.index('      if [ -s "$HOME/.bl-restart-game" ]; then'):SOURCE.index('      if [ -f "$HOME/.bl-restart-once" ]; then')]
        self.assertIn('steam_hold_launch "$_bl_again" &', loop)
        self.assertNotIn("set -- \"steam://rungameid/", loop)
        script = self.block + '\nsteam_hold_launch "%s"\n' % game_id
        env = dict(os.environ, HOME=str(self.root), BL_LAUNCH_DIR=str(self.launch_dir),
                   PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                   AGENT_ARGS=str(self.agent_args), SLEEP_ARGS=str(self.sleep_args),
                   AGENT_COUNT=str(self.agent_count), AGENT_STATUS="1 0")
        result = subprocess.run(["bash", "-c", script, "restart"], env=env, text=True, capture_output=True, timeout=10, check=True)
        self.assertEqual(game_id, (self.launch_dir / "steam-game").read_text().strip(), result.stdout)
        self.assertEqual(["--wait-steam-shortcut-overview", game_id, "45"], self.agent_args.read_text().splitlines())

    def test_non_game_steam_urls_remain_on_the_original_startup_arguments(self):
        url = "steam://open/library"
        result = self.run_launch(url, 0)
        self.assertIn("ARG:" + url, result.stdout)
        self.assertFalse((self.launch_dir / "steam-game").exists())
        self.assertFalse(self.agent_args.exists())


if __name__ == "__main__":
    unittest.main()
