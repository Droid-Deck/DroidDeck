"""Cold shortcut launches wait for Steam's CGameID-to-app overview mapping."""
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest

SESSION = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-session"
SOURCE = SESSION.read_text()
END = SOURCE.index(
    '    if [ -s "$HOME/.bl-autolaunch" ]; then',
    SOURCE.index("          _bl_launch_ready="),
)
START = SOURCE.rfind(
    '    if [ -n "${BL_LAUNCH_DIR:-}" ] && [ -z "${BL_DESKTOP:-}" ]; then',
    0,
    END,
)
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

        xprop = self.bin / "xprop"
        xprop.write_text("#!/bin/sh\necho 'GAMESCOPE_FOCUSED_APP(CARDINAL) = 769'\n")
        xprop.chmod(0o755)
        agent = self.bin / "droiddeck-agent"
        agent.write_text("#!/bin/sh\nprintf '%s\\n' \"$@\" > \"$AGENT_ARGS\"\nexit \"$AGENT_STATUS\"\n")
        agent.chmod(0o755)
        sleep = self.bin / "sleep"
        sleep.write_text("#!/bin/sh\nprintf '%s\\n' \"$@\" >> \"$SLEEP_ARGS\"\n")
        sleep.chmod(0o755)
        self.block = LAUNCH_BLOCK.replace("/usr/local/bin/droiddeck-agent", str(agent))

    def run_launch(self, target, helper_status):
        env = dict(os.environ, HOME=str(self.root), BL_LAUNCH_DIR=str(self.launch_dir),
                   PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                   AGENT_ARGS=str(self.agent_args), SLEEP_ARGS=str(self.sleep_args),
                   AGENT_STATUS=str(helper_status))
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

    def test_shortcut_without_the_expected_overview_does_not_forward_cgameid(self):
        game_id = str((3551182975 << 32) | 0x02000000)
        result = self.run_launch(game_id, 1)
        self.assertFalse((self.launch_dir / "steam-game").exists(), result.stdout)
        self.assertEqual(["--wait-steam-shortcut-overview", game_id, "45"], self.agent_args.read_text().splitlines())
        self.assertFalse(self.sleep_args.exists())

    def test_non_game_steam_urls_remain_on_the_original_startup_arguments(self):
        url = "steam://open/library"
        result = self.run_launch(url, 0)
        self.assertIn("ARG:" + url, result.stdout)
        self.assertFalse((self.launch_dir / "steam-game").exists())
        self.assertFalse(self.agent_args.exists())


if __name__ == "__main__":
    unittest.main()
