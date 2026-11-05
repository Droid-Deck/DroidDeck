import contextlib
import io
import os
from pathlib import Path
import runpy
import signal
import subprocess
import sys
import tempfile
from unittest import mock
import unittest


BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
GAME_ENV = BIN / "droiddeck-game-env"
MODULE = runpy.run_path(str(GAME_ENV))


class GameExitDiagnosticsTest(unittest.TestCase):
    def run_child(self, body, app_id="42"):
        with contextlib.redirect_stderr(io.StringIO()) as said:
            status = MODULE["start"](
                [sys.executable, "-c", body], os.environ.copy(), None,
                diagnostic_app_id=app_id,
            )
            return status, said.getvalue()

    def test_clean_and_nonzero_exits_are_authoritative(self):
        for child_status in (0, 7):
            with self.subTest(status=child_status):
                status, said = self.run_child("import sys; sys.exit(%d)" % child_status)
                self.assertEqual(status, child_status)
                self.assertIn("event=game_start app_id=42", said)
                self.assertIn(
                    "event=game_exit app_id=42 raw_status=%d normalized_status=%d wrapper_status=%d signal=0" %
                    (child_status, child_status, child_status), said,
                )

    def test_signal_exit_is_normalized_and_records_the_signal(self):
        status, said = self.run_child("import os, signal; os.kill(os.getpid(), signal.SIGTERM)")
        self.assertEqual(status, 128 + signal.SIGTERM)
        self.assertIn(
            "event=game_exit app_id=42 raw_status=-15 normalized_status=143 wrapper_status=143 signal=15", said,
        )

    def test_xbox_exit_is_recorded_without_inventing_a_start(self):
        with contextlib.redirect_stderr(io.StringIO()) as said:
            MODULE["diagnostic_game_exit"]("42", -signal.SIGTERM, "xbox", 241)
        self.assertNotIn("event=game_start", said.getvalue())
        self.assertIn(
            "event=game_exit app_id=42 raw_status=-15 normalized_status=143 wrapper_status=241 signal=15 source=xbox",
            said.getvalue(),
        )

    def test_signal_handler_forwards_to_the_child(self):
        class Process:
            def __init__(self):
                self.forwarded = []

            def send_signal(self, signum):
                self.forwarded.append(signum)

            def wait(self):
                os.kill(os.getpid(), signal.SIGTERM)
                return -signal.SIGTERM

        process = Process()
        self.assertEqual(MODULE["wait_forwarding_signals"](process), 128 + signal.SIGTERM)
        self.assertEqual(process.forwarded, [signal.SIGTERM])

    def test_default_path_still_execs_in_place_without_diagnostic_output(self):
        called = []
        with mock.patch("os.execvpe", side_effect=lambda *args: called.append(args)), \
             contextlib.redirect_stderr(io.StringIO()) as said:
            MODULE["start"](["/game", "arg"], {"A": "B"}, None)
        self.assertEqual(called, [("/game", ["/game", "arg"], {"A": "B"})])
        self.assertNotIn("DROIDDECK_DIAGNOSTICS", said.getvalue())

    def test_failed_selected_launch_does_not_invent_a_start_or_exit(self):
        with mock.patch("subprocess.Popen", side_effect=FileNotFoundError("missing")), \
             contextlib.redirect_stderr(io.StringIO()) as said:
            with self.assertRaises(FileNotFoundError):
                MODULE["start"](["/missing"], {}, None, diagnostic_app_id="42")
        self.assertNotIn("DROIDDECK_DIAGNOSTICS", said.getvalue())

    def test_cleanup_runs_after_a_diagnostic_child(self):
        cleaned = []
        with contextlib.redirect_stderr(io.StringIO()):
            status = MODULE["start"](
                [sys.executable, "-c", "pass"], os.environ.copy(), None,
                after=lambda: cleaned.append(True), diagnostic_app_id="42",
            )
        self.assertEqual(status, 0)
        self.assertEqual(cleaned, [True])

    def test_diagnostic_supervision_keeps_ubisoft_composition_setup(self):
        events = []

        class Process:
            def wait(self):
                events.append("wait")
                return 0

        def composition_off(proton, env):
            events.append(("composition", proton, env))

        def popen(command, env):
            events.append(("launch", command, env))
            return Process()

        launch = ["/proton", "waitforexitandrun", "game.exe"]
        env = {"STEAM_COMPAT_DATA_PATH": "/compatdata/42"}
        with mock.patch.dict(MODULE["start"].__globals__, ubisoft_composition_off=composition_off), \
             mock.patch("subprocess.Popen", side_effect=popen), \
             contextlib.redirect_stderr(io.StringIO()):
            status = MODULE["start"](launch, env, None, diagnostic_app_id="42")
        self.assertEqual(status, 0)
        self.assertEqual(events, [("composition", "/proton", env), ("launch", launch, env), "wait"])

    def test_diagnostic_install_script_keeps_success_recording(self):
        recorded = []
        with mock.patch.dict(MODULE["start"].__globals__, record_install_scripts=lambda digest, env: recorded.append((digest, env))), \
             contextlib.redirect_stderr(io.StringIO()):
            status = MODULE["start"](
                [sys.executable, "-c", "pass"], {"CUSTOM": "kept"}, "digest",
                diagnostic_app_id="42",
            )
        self.assertEqual(status, 0)
        self.assertEqual(recorded, [("digest", {"CUSTOM": "kept"})])

    def test_cleanup_path_keeps_the_original_install_recording_precedence(self):
        recorded = []
        with mock.patch.dict(MODULE["start"].__globals__, record_install_scripts=lambda digest, env: recorded.append((digest, env))):
            status = MODULE["start"](
                [sys.executable, "-c", "pass"], {"CUSTOM": "kept"}, "digest",
                after=lambda: None,
            )
        self.assertEqual(status, 0)
        self.assertEqual(recorded, [])

    def test_main_only_traces_valid_waitforexitandrun_games(self):
        with tempfile.TemporaryDirectory(prefix="game-exit-diagnostics-") as directory:
            root = Path(directory)
            probe = root / "probe"
            probe.write_text("#!" + sys.executable + "\nimport sys\nsys.exit(7)\n")
            probe.chmod(0o755)
            base = dict(os.environ, HOME=directory, PATH=os.defpath,
                        DROIDDECK_ENHANCED_DIAGNOSTICS="1")
            valid = subprocess.run(
                [sys.executable, str(GAME_ENV), str(probe), "waitforexitandrun"],
                env={**base, "STEAM_COMPAT_DATA_PATH": "/compatdata/42"},
                capture_output=True, text=True, timeout=15,
            )
            self.assertEqual(valid.returncode, 7, valid.stderr)
            self.assertIn("event=game_start app_id=42", valid.stderr)
            self.assertIn(
                "event=game_exit app_id=42 raw_status=7 normalized_status=7 wrapper_status=7 signal=0", valid.stderr,
            )

            for verb, prefix in (("run", "/compatdata/42"), ("waitforexitandrun", "/compatdata/not-a-game")):
                result = subprocess.run(
                    [sys.executable, str(GAME_ENV), str(probe), verb],
                    env={**base, "STEAM_COMPAT_DATA_PATH": prefix},
                    capture_output=True, text=True, timeout=15,
                )
                self.assertEqual(result.returncode, 7, result.stderr)
                self.assertNotIn("DROIDDECK_DIAGNOSTICS", result.stderr)

    def test_main_records_xbox_result_without_changing_it(self):
        launch = lambda command, env: 23
        with tempfile.TemporaryDirectory(prefix="xbox-exit-diagnostics-") as directory, \
             mock.patch.dict(os.environ, {
                 "HOME": directory,
                 "PATH": os.defpath,
                 "STEAM_COMPAT_DATA_PATH": "/compatdata/42",
                 "DROIDDECK_ENHANCED_DIAGNOSTICS": "1",
             }, clear=True), \
             mock.patch("sys.argv", ["droiddeck-game-env", "/proton", "waitforexitandrun", "game.exe"]), \
             mock.patch("runpy.run_path", return_value={"launch": launch}), \
             contextlib.redirect_stderr(io.StringIO()) as said:
            status = MODULE["main"]()
        self.assertEqual(status, 23)
        self.assertNotIn("event=game_start", said.getvalue())
        self.assertIn(
            "event=game_exit app_id=42 raw_status=23 normalized_status=23 wrapper_status=23 signal=0 source=xbox",
            said.getvalue(),
        )

    def test_main_preserves_negative_xbox_result_and_reports_wrapper_status(self):
        launch = lambda command, env: -signal.SIGTERM
        with tempfile.TemporaryDirectory(prefix="xbox-signal-diagnostics-") as directory, \
             mock.patch.dict(os.environ, {
                 "HOME": directory,
                 "PATH": os.defpath,
                 "STEAM_COMPAT_DATA_PATH": "/compatdata/42",
                 "DROIDDECK_ENHANCED_DIAGNOSTICS": "1",
             }, clear=True), \
             mock.patch("sys.argv", ["droiddeck-game-env", "/proton", "waitforexitandrun", "game.exe"]), \
             mock.patch("runpy.run_path", return_value={"launch": launch}), \
             contextlib.redirect_stderr(io.StringIO()) as said:
            status = MODULE["main"]()
        self.assertEqual(status, -signal.SIGTERM)
        self.assertIn(
            "event=game_exit app_id=42 raw_status=-15 normalized_status=143 wrapper_status=241 signal=15 source=xbox",
            said.getvalue(),
        )


if __name__ == "__main__":
    unittest.main()
