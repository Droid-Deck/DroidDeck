import os
from pathlib import Path
import runpy
import subprocess
import tempfile
import unittest

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
COMPAT = runpy.run_path(str(BIN / "steam-compatibility"))


class LiveGameCpusTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        base = Path(self.temp.name)
        self.home = base / "home"
        (self.home / ".config/droiddeck").mkdir(parents=True)
        tools = base / "tools"
        tools.mkdir()
        for name, body in (("taskset", "exit 0"), ("nproc", "echo 8")):
            tool = tools / name
            tool.write_text("#!/bin/sh\n" + body + "\n")
            tool.chmod(0o755)
        self.path = str(tools) + os.pathsep + os.environ.get("PATH", "")

    def run_setup(self, live=None, **extra):
        if live is not None:
            (self.home / ".config/droiddeck/game-cores").write_text(live)
        script = COMPAT["DD_GAME_CPUS_SETUP"] + '\ndd_game_cpus\necho "taskset=${DD_TASKSET:-}"\necho "cpus=${DD_GAME_CPUS-unset}"\n'
        env = {key: value for key, value in os.environ.items() if not key.startswith("DD_")}
        env.update(HOME=str(self.home), PATH=self.path, **extra)
        result = subprocess.run(["bash", "-c", script], env=env, capture_output=True, text=True, check=True)
        return result.stdout.splitlines()

    def test_a_change_made_during_the_session_wins_over_the_session_value(self):
        self.assertEqual(self.run_setup("6,7\n", DD_GAME_CPUS="3,4,5,6,7"),
                         ["taskset=taskset -c 6,7", "cpus=6,7"])

    def test_every_core_chosen_during_the_session_drops_the_session_pin(self):
        self.assertEqual(self.run_setup("\n", DD_GAME_CPUS="3,4,5,6,7"), ["taskset=", "cpus=unset"])

    def test_every_core_with_a_pinned_client_still_frees_the_game_from_the_client_cores(self):
        self.assertEqual(self.run_setup("\n", DD_GAME_CPUS="0-7", DD_CLIENT_CPUS="0"),
                         ["taskset=taskset -c 0-7", "cpus=0-7"])

    def test_without_the_live_file_the_session_value_is_used(self):
        self.assertEqual(self.run_setup(DD_GAME_CPUS="3,4,5,6,7"),
                         ["taskset=taskset -c 3,4,5,6,7", "cpus=3,4,5,6,7"])
        self.assertEqual(self.run_setup(), ["taskset=", "cpus=unset"])

    def test_an_unreadable_value_keeps_the_session_value(self):
        self.assertEqual(self.run_setup("3;rm\n", DD_GAME_CPUS="4,5"), ["taskset=taskset -c 4,5", "cpus=4,5"])


if __name__ == "__main__":
    unittest.main()
