"""Requested Steam games are handed over as soon as the interface is up; a shortcut is handed over again only if it never took focus."""
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
SHORTCUT = str((3551182975 << 32) | 0x02000000)


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
        self.focus_count = self.root / "focus-count"

        # FOCUS lists the focused app per xprop call; the last value repeats.
        xprop = self.bin / "xprop"
        xprop.write_text(
            "#!/bin/sh\n"
            "n=$(cat \"$FOCUS_COUNT\" 2>/dev/null || echo 0); n=$((n + 1)); echo $n > \"$FOCUS_COUNT\"\n"
            "i=0; last=769\n"
            "for f in $FOCUS; do i=$((i + 1)); last=$f; [ \"$i\" -eq \"$n\" ] && break; done\n"
            "echo \"GAMESCOPE_FOCUSED_APP(CARDINAL) = $last\"\n")
        xprop.chmod(0o755)
        agent = self.bin / "droiddeck-agent"
        agent.write_text(
            "#!/bin/sh\n"
            "printf '%s\n' \"$@\" > \"$AGENT_ARGS\"\n"
            "n=$(cat \"$AGENT_COUNT\" 2>/dev/null || echo 0); n=$((n + 1)); echo $n > \"$AGENT_COUNT\"\n"
            "exit \"$AGENT_STATUS\"\n")
        agent.chmod(0o755)
        sleep = self.bin / "sleep"
        sleep.write_text("#!/bin/sh\nprintf '%s\n' \"$@\" >> \"$SLEEP_ARGS\"\n")
        sleep.chmod(0o755)
        self.block = LAUNCH_BLOCK.replace("/usr/local/bin/droiddeck-agent", str(agent))

    def run_launch(self, target, focus="769", helper_status=0):
        env = dict(os.environ, HOME=str(self.root), BL_LAUNCH_DIR=str(self.launch_dir),
                   PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                   AGENT_ARGS=str(self.agent_args), SLEEP_ARGS=str(self.sleep_args),
                   AGENT_COUNT=str(self.agent_count), AGENT_STATUS=str(helper_status),
                   FOCUS=focus, FOCUS_COUNT=str(self.focus_count))
        script = self.block + "\nprintf 'ARG:%s\n' \"$@\"\nwait\n"
        url = target if target.startswith("steam://") else "steam://rungameid/" + target
        result = subprocess.run(["bash", "-c", script, "cold-launch", url],
                                env=env, text=True, capture_output=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)
        return result

    def hand_offs(self, result):
        return [l for l in result.stdout.splitlines() if "launching the requested game" in l]

    def test_a_shortcut_that_takes_focus_is_handed_over_once_without_waiting_for_the_overview(self):
        result = self.run_launch(SHORTCUT, focus="769 769 4242")
        self.assertEqual(SHORTCUT, (self.launch_dir / "steam-game").read_text().strip())
        self.assertEqual(1, len(self.hand_offs(result)), result.stdout)
        self.assertFalse(self.agent_args.exists(), result.stdout)
        self.assertEqual(["1"], self.sleep_args.read_text().split())

    def test_a_shortcut_that_never_takes_focus_is_handed_over_again_after_the_overview_wait(self):
        result = self.run_launch(SHORTCUT, focus="769", helper_status=0)
        self.assertEqual(2, len(self.hand_offs(result)), result.stdout)
        self.assertIn("launching the requested game again", result.stdout)
        self.assertEqual(["--wait-steam-shortcut-overview", SHORTCUT, "45"], self.agent_args.read_text().splitlines())
        self.assertEqual(["1"] * 20, self.sleep_args.read_text().split())

    def test_a_timed_out_overview_still_gets_one_more_hand_off(self):
        result = self.run_launch(SHORTCUT, focus="769", helper_status=1)
        self.assertEqual(2, len(self.hand_offs(result)), result.stdout)
        self.assertIn("overview status 1", result.stdout)

    def test_a_game_that_takes_focus_during_the_overview_wait_is_not_started_twice(self):
        focus = " ".join(["769"] * 23) + " 4242"
        result = self.run_launch(SHORTCUT, focus=focus, helper_status=0)
        self.assertEqual(1, len(self.hand_offs(result)), result.stdout)
        self.assertIn("took focus while waiting", result.stdout)
        self.assertNotIn("launching the requested game again", result.stdout)

    def test_native_app_id_keeps_the_short_delay_and_is_handed_over_once(self):
        result = self.run_launch("413150")
        self.assertEqual("413150", (self.launch_dir / "steam-game").read_text().strip())
        self.assertEqual(1, len(self.hand_offs(result)), result.stdout)
        self.assertEqual("4", self.sleep_args.read_text().strip())
        self.assertFalse(self.agent_args.exists())

    def test_a_restart_for_a_game_uses_the_same_hand_off(self):
        loop = SOURCE[SOURCE.index('      if [ -s "$HOME/.bl-restart-game" ]; then'):SOURCE.index('      if [ -f "$HOME/.bl-restart-once" ]; then')]
        self.assertIn('steam_hold_launch "$_bl_again" &', loop)
        self.assertNotIn("set -- \"steam://rungameid/", loop)
        script = self.block + '\nsteam_hold_launch "%s"\n' % SHORTCUT
        env = dict(os.environ, HOME=str(self.root), BL_LAUNCH_DIR=str(self.launch_dir),
                   PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                   AGENT_ARGS=str(self.agent_args), SLEEP_ARGS=str(self.sleep_args),
                   AGENT_COUNT=str(self.agent_count), AGENT_STATUS="1",
                   FOCUS="769", FOCUS_COUNT=str(self.focus_count))
        result = subprocess.run(["bash", "-c", script, "restart"], env=env, text=True, capture_output=True, timeout=10, check=True)
        self.assertEqual(SHORTCUT, (self.launch_dir / "steam-game").read_text().strip(), result.stdout)
        self.assertEqual(2, len(self.hand_offs(result)), result.stdout)

    def test_non_game_steam_urls_remain_on_the_original_startup_arguments(self):
        url = "steam://open/library"
        result = self.run_launch(url)
        self.assertIn("ARG:" + url, result.stdout)
        self.assertFalse((self.launch_dir / "steam-game").exists())
        self.assertFalse(self.agent_args.exists())


if __name__ == "__main__":
    unittest.main()
