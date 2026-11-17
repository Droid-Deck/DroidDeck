import contextlib
import io
import os
from pathlib import Path
import runpy
import subprocess
import sys
import tempfile
import unittest


BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
DEBUG = runpy.run_path(str(BIN / "droiddeck-game-debug"))


class DesktopProgramDiagnosticsTest(unittest.TestCase):
    def launch(self, folder, desktop=True, **settings):
        env = dict(os.environ, BL_DEBUG_DIR=folder, WINEDEBUG="-all", PROTON_LOG="0")
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
            env = {"DROIDDECK_DESKTOP_PROGRAM": "managed.exe", "BL_DEBUG_DIR": folder}
            for _ in range(2):
                with DEBUG["desktop_program_logs"](env) as (launch_env, output):
                    self.assertNotIn("DROIDDECK_DESKTOP_PROGRAM", launch_env)
                    child = subprocess.Popen([sys.executable, "-c", "import os; os.write(2,b'x'*2097152)"], stderr=output)
                    self.assertEqual(child.wait(timeout=10), 0)
            logs = list(Path(folder).glob("desktop-program-*.log"))
            self.assertEqual(len(logs), 2)
            self.assertTrue(all(path.stat().st_size == 1048576 for path in logs))
