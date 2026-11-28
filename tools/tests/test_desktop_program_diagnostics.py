import contextlib
import io
import os
from pathlib import Path
import runpy
import subprocess
import sys
import tempfile
import time
import unittest


BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
DEBUG = runpy.run_path(str(BIN / "droiddeck-game-debug"))


class ProtonLogCapTest(unittest.TestCase):
    def test_only_an_enabled_proton_log_is_watched(self):
        self.assertEqual(DEBUG["proton_log_paths"]({"PROTON_LOG": "0", "SteamGameId": "1"}), [])
        self.assertEqual(DEBUG["proton_log_paths"]({"SteamGameId": "1"}), [])
        self.assertEqual(DEBUG["proton_log_paths"]({"PROTON_LOG": "1", "PROTON_LOG_DIR": "/logs", "SteamGameId": "584400"}),
                         ["/logs/steam-584400.log", "/logs/steam-proton.log"])
        self.assertEqual(DEBUG["proton_log_paths"]({"PROTON_LOG": "+seh", "HOME": "/root"}), ["/root/steam-proton.log"])

    def test_the_watcher_outlives_nothing_but_the_launcher(self):
        calls = []
        DEBUG["cap_proton_log"]({"PROTON_LOG": "1", "PROTON_LOG_DIR": "/logs", "SteamGameId": "7"}, pid=42,
                                popen=lambda argv, **kw: calls.append((argv, kw)))
        argv, kw = calls[0]
        self.assertEqual(argv[3:], ["42", str(DEBUG["PROTON_LOG_CAP"]), str(DEBUG["PROTON_LOG_CHECK_S"]),
                                    "/logs/steam-7.log", "/logs/steam-proton.log"])
        self.assertTrue(kw["start_new_session"])
        self.assertIsNone(DEBUG["cap_proton_log"]({"PROTON_LOG": "0"}, popen=lambda *a, **k: self.fail("spawned")))

    def test_a_flooding_log_is_cut_back_and_appends_continue_after_the_cut(self):
        with tempfile.TemporaryDirectory() as folder:
            log = Path(folder) / "steam-9.log"
            launcher = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(3)"])
            with open(log, "ab") as proton:
                proton.write(b"Proton: header\n" + b"x" * 300_000)
                proton.flush()
                watcher = DEBUG["cap_proton_log"]({"PROTON_LOG": "1", "PROTON_LOG_DIR": folder, "SteamGameId": "9"},
                                                  pid=launcher.pid, limit=1000, every=0.1)
                deadline = time.monotonic() + 5
                while log.stat().st_size > 1000 and time.monotonic() < deadline:
                    time.sleep(0.05)
                self.assertEqual(log.stat().st_size, 1000)
                proton.write(b"tail")
                proton.flush()
            self.assertTrue(log.read_bytes().startswith(b"Proton: header"))
            self.assertTrue(log.read_bytes().endswith(b"tail"))
            self.assertEqual(log.stat().st_size, 1004)
            launcher.wait()
            watcher.wait(timeout=5)

    def test_symlinks_are_left_alone(self):
        with tempfile.TemporaryDirectory() as folder:
            target = Path(folder) / "other"
            target.write_bytes(b"y" * 5000)
            (Path(folder) / "steam-3.log").symlink_to(target)
            launcher = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(0.5)"])
            watcher = DEBUG["cap_proton_log"]({"PROTON_LOG": "1", "PROTON_LOG_DIR": folder, "SteamGameId": "3"},
                                              pid=launcher.pid, limit=10, every=0.1)
            launcher.wait()
            watcher.wait(timeout=5)
            self.assertEqual(target.stat().st_size, 5000)


class DesktopProgramDiagnosticsTest(unittest.TestCase):
    def launch(self, folder, desktop=True, **settings):
        env = dict(os.environ, DD_DEBUG_DIR=folder, WINEDEBUG="-all", PROTON_LOG="0")
        env.pop("DROIDDECK_DESKTOP_PROGRAM", None)
        if desktop:
            env["DROIDDECK_DESKTOP_PROGRAM"] = "managed.exe"
        env.update(settings)
        child = "import os,sys; print(os.environ['WINEDEBUG']); sys.stderr.write('err:mscoree:CLRRuntimeInfo_GetRuntimeHost Wine Mono is not installed\\n'); sys.exit(7)"
        wrapper = "import runpy,sys; g=runpy.run_path(sys.argv[1]); sys.exit(g['start']([sys.executable,'-c',sys.argv[2]],dict(__import__('os').environ),None))"
        return subprocess.run([sys.executable, "-c", wrapper, str(BIN / "droiddeck-game-env"), child],
                              env=env, capture_output=True, text=True, timeout=15)

    def test_desktop_failure_keeps_reason_and_exit_status(self):
        with tempfile.TemporaryDirectory() as folder:
            result = self.launch(folder)
            self.assertEqual(result.returncode, 7)
            self.assertEqual(result.stdout.strip(), "-all,err+all,+mscoree,+loaddll")
            logs = list(Path(folder).glob("desktop-program-*.log"))
            self.assertEqual(len(logs), 1)
            self.assertIn("Wine Mono is not installed", logs[0].read_text())
            self.assertIn("Wine Mono is not installed", result.stderr)
            self.assertIn("exited with status 7", result.stderr)

    def test_games_and_explicit_debug_settings_stay_unchanged(self):
        with tempfile.TemporaryDirectory() as folder:
            ordinary = self.launch(folder, desktop=False)
            self.assertEqual(ordinary.stdout.strip(), "-all")
            self.assertEqual(list(Path(folder).glob("*.log")), [])
            opted_in = self.launch(folder, PROTON_LOG="1", WINEDEBUG="+relay")
            self.assertEqual(opted_in.stdout.strip(), "+relay")
            self.assertEqual(list(Path(folder).glob("*.log")), [])
            explicit = self.launch(folder, WINEDEBUG="-all,err+module")
            self.assertEqual(explicit.stdout.strip(), "-all,err+module")

    def test_unavailable_log_directory_does_not_stop_program(self):
        with tempfile.TemporaryDirectory() as folder:
            result = self.launch(str(Path(folder) / "missing"))
            self.assertEqual(result.returncode, 7)
            self.assertEqual(result.stdout.strip(), "-all")
            self.assertIn("diagnostics unavailable", result.stderr)

    def test_capped_log_drains_after_limit_and_preserves_following_output(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "program.log"
            output, thread = DEBUG["capped_log"](str(path), 1024)
            try:
                child = subprocess.Popen([sys.executable, "-c",
                    "import os; os.write(2,b'x'*2097152); print('completed')"],
                    stderr=output, stdout=subprocess.PIPE, text=True)
                stdout, _ = child.communicate(timeout=10)
                self.assertEqual(child.returncode, 0)
                self.assertEqual(stdout.strip(), "completed")
            finally:
                os.close(output)
                thread.join(timeout=2)
            self.assertFalse(thread.is_alive())
            self.assertEqual(path.read_bytes(), b"x" * 1024)

    def test_capped_log_continues_mirroring_after_file_limit_and_survives_mirror_errors(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "mirrored.log"
            mirrored = io.StringIO()
            with contextlib.redirect_stderr(mirrored):
                output, thread = DEBUG["capped_log"](str(path), 4, mirror=True)
                try:
                    os.write(output, b"abcdef")
                finally:
                    os.close(output)
                    thread.join(timeout=2)
            self.assertEqual(path.read_bytes(), b"abcd")
            self.assertEqual(mirrored.getvalue(), "abcdef")

    def test_desktop_logs_have_unique_names_and_a_hard_byte_limit(self):
        with tempfile.TemporaryDirectory() as folder, contextlib.redirect_stderr(io.StringIO()):
            env = {"DROIDDECK_DESKTOP_PROGRAM": "managed.exe", "DD_DEBUG_DIR": folder}
            for _ in range(2):
                with DEBUG["desktop_program_logs"](env) as (launch_env, output):
                    self.assertNotIn("DROIDDECK_DESKTOP_PROGRAM", launch_env)
                    child = subprocess.Popen([sys.executable, "-c", "import os; os.write(2,b'x'*2097152)"], stderr=output)
                    self.assertEqual(child.wait(timeout=10), 0)
            logs = list(Path(folder).glob("desktop-program-*.log"))
            self.assertEqual(len(logs), 2)
            self.assertTrue(all(path.stat().st_size == 1048576 for path in logs))

    def test_broken_stderr_does_not_abort_launch_or_replace_exit_status(self):
        class BrokenStderr:
            def write(self, data):
                raise BrokenPipeError("closed")

            def flush(self):
                raise BrokenPipeError("closed")

        game_env = runpy.run_path(str(BIN / "droiddeck-game-env"))
        with tempfile.TemporaryDirectory() as folder:
            env = dict(os.environ, DD_DEBUG_DIR=folder, WINEDEBUG="-all", PROTON_LOG="0",
                       DROIDDECK_DESKTOP_PROGRAM="managed.exe")
            with contextlib.redirect_stderr(BrokenStderr()):
                code = game_env["start"](
                    [sys.executable, "-c", "import sys; sys.stderr.write('err:child_line\\n'); sys.exit(7)"],
                    env,
                    None,
                )
            self.assertEqual(code, 7)
            logs = list(Path(folder).glob("desktop-program-*.log"))
            self.assertEqual(len(logs), 1)
            self.assertIn("err:child_line", logs[0].read_text())
