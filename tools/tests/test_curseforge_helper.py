import os
import shlex
import shutil
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
HELPER = BIN / "droiddeck-curseforge"
HEADLESS = BIN / "droiddeck-headless"
URL = "ws://127.0.0.1:43117/0f8fad5b-d9cb-469f-a165-70867728950e"


class HeadlessTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.bin = self.tmp / "bin"
        self.bin.mkdir()
        self.seen = self.tmp / "seen"
        gamescope = self.bin / "gamescope"
        gamescope.write_text(
            "#!/bin/bash\n"
            "printf '%%s\\n' \"DISPLAY=${DISPLAY-}\" \"WAYLAND=${WAYLAND_DISPLAY-}\" \"GAMESCOPE=${GAMESCOPE_WAYLAND_DISPLAY-}\" "
            "\"XAUTH=${XAUTHORITY-}\" \"RUNTIME=$XDG_RUNTIME_DIR\" \"$@\" > %s\n"
            "echo logged-by-gamescope\n" % shlex.quote(str(self.seen)))
        gamescope.chmod(0o755)

    def env(self, extra=None):
        base = dict(os.environ, PATH="%s:%s" % (self.bin, os.environ["PATH"]), DISPLAY=":0", WAYLAND_DISPLAY="wayland-0",
                    GAMESCOPE_WAYLAND_DISPLAY="gamescope-0", XAUTHORITY="/run/xauth")
        base.update(extra or {})
        return base

    def run_headless(self, *args, env=None):
        return subprocess.run(["bash", str(HEADLESS), *args], env=self.env(env), capture_output=True, text=True, timeout=30)

    def test_it_runs_the_command_in_headless_gamescope_with_a_private_runtime_and_no_session_display(self):
        runtime = self.tmp / "runtime"
        out = self.run_headless(str(runtime), "--", "prog", "arg one")
        self.assertEqual(0, out.returncode, out.stderr)
        self.assertEqual(["DISPLAY=", "WAYLAND=", "GAMESCOPE=", "XAUTH=", "RUNTIME=%s" % runtime, "--backend", "headless",
                          "-w", "640", "-h", "480", "--", "prog", "arg one"], self.seen.read_text().splitlines())
        self.assertEqual(0o700, runtime.stat().st_mode & 0o777)

    def test_output_goes_to_the_log_which_rotates_when_large(self):
        log = self.tmp / "agent.log"
        log.write_bytes(b"x" * 100)
        self.assertEqual(0, self.run_headless(str(self.tmp / "r"), "--log", str(log), "--", "prog").returncode)
        self.assertTrue(log.read_text().endswith("logged-by-gamescope\n"))
        self.assertEqual(100, log.stat().st_size - len("logged-by-gamescope\n"))
        self.assertFalse((self.tmp / "agent.log.1").exists())
        self.run_headless(str(self.tmp / "r"), "--log", str(log), "--", "prog", env={"DD_HEADLESS_LOG_LIMIT": "50"})
        self.assertEqual(100 + len("logged-by-gamescope\n"), (self.tmp / "agent.log.1").stat().st_size)
        self.assertEqual("logged-by-gamescope\n", log.read_text())

    def test_bad_arguments_are_refused(self):
        self.assertEqual(2, self.run_headless(str(self.tmp / "r"), "prog").returncode)
        self.assertEqual(2, self.run_headless(str(self.tmp / "r"), "--").returncode)
        self.assertEqual(2, self.run_headless().returncode)


class CurseForgeHelperTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.proc = self.tmp / "proc"
        self.proc.mkdir()
        self.dir = self.tmp / "curseforge"
        (self.dir / "app").mkdir(parents=True)
        self.state = self.tmp / "state"
        self.bin = self.tmp / "bin"
        self.bin.mkdir()
        self.runner = self.tmp / "runner"
        self.seen = self.tmp / "seen"
        self.runner.write_text(
            "#!/bin/bash\n"
            "printf '%%s\\n' \"DISPLAY=${DISPLAY-}\" \"WAYLAND=${WAYLAND_DISPLAY-}\" \"DBUS=${DBUS_SESSION_BUS_ADDRESS-}\" "
            "\"RUNTIME=$XDG_RUNTIME_DIR\" \"$@\" > %s\n"
            "mkdir -p %s/$$ && printf '%%s\\0' bash \"$0\" \"$@\" > %s/$$/cmdline\n"
            "echo 'Debugger listening on %s' >&2\n"
            "echo 'For help, see: https://nodejs.org/en/docs/inspector' >&2\n"
            "sleep 60\n"
            % (shlex.quote(str(self.seen)), shlex.quote(str(self.proc)), shlex.quote(str(self.proc)), URL))
        self.runner.chmod(0o755)
        gamescope = self.bin / "gamescope"
        gamescope.write_text('#!/bin/bash\nwhile [ "$1" != -- ]; do shift; done\nshift\n"$@"\n')
        gamescope.chmod(0o755)

    def fake(self, pid, *argv):
        d = self.proc / str(pid)
        d.mkdir()
        (d / "cmdline").write_bytes(b"".join(a.encode() + b"\0" for a in argv))

    def env(self, extra=None):
        base = dict(os.environ, PATH="%s:%s" % (self.bin, os.environ["PATH"]), DD_PROC=str(self.proc),
                    DD_CURSEFORGE_STATE=str(self.state), DD_APPIMAGE_RUN=str(self.runner), DD_HEADLESS_RUN=str(HEADLESS),
                    DD_CURSEFORGE_WAIT="10", DISPLAY=":0", WAYLAND_DISPLAY="wayland-0", XDG_RUNTIME_DIR="/run/user/session",
                    DBUS_SESSION_BUS_ADDRESS="unix:path=/x")
        base.update(extra or {})
        return base

    def run_helper(self, *args, env=None):
        return subprocess.run(["bash", str(HELPER), *args], env=self.env(env), capture_output=True, text=True, timeout=60)

    def launch(self, directory=None):
        holder = subprocess.Popen(["bash", str(HELPER), "start", str(directory or self.dir)], env=self.env(),
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.addCleanup(self.finish, holder)
        return holder, holder.stdout.readline().strip()

    def finish(self, holder):
        if holder.poll() is None:
            holder.kill()
        holder.communicate()

    def stop(self):
        self.run_helper("stop", str(self.dir))

    def pid(self):
        return int((self.state / "pid").read_text().split()[0])

    def gone(self, pid):
        for _ in range(50):
            try:
                os.kill(pid, 0)
            except ProcessLookupError:
                return True
            time.sleep(0.1)
        return False

    def test_a_running_instance_without_the_inspector_is_busy(self):
        self.fake(50, "/usr/bin/FEX", str(self.dir) + "/app/curseforge", "--no-sandbox")
        out = self.run_helper("start", str(self.dir))
        self.assertEqual(3, out.returncode)
        self.assertEqual("busy", out.stdout.strip())
        self.stop()
        self.assertTrue((self.proc / "50").exists())

    def test_start_runs_the_app_in_headless_gamescope_with_a_secret_inspector_and_no_session_display(self):
        holder, url = self.launch()
        self.addCleanup(self.stop)
        self.assertEqual(URL, url)
        seen = self.seen.read_text().splitlines()
        self.assertEqual(["DISPLAY=", "WAYLAND=", "DBUS=", "RUNTIME=%s/runtime" % self.state, str(self.dir), "--minimized",
                          "--inspect=127.0.0.1:0", "--inspect-publish-uid=stderr"], seen)
        self.assertEqual(0o700, self.state.stat().st_mode & 0o777)
        self.assertEqual(0o700, (self.state / "runtime").stat().st_mode & 0o777)
        self.assertEqual(0o600, (self.state / "log").stat().st_mode & 0o777)
        _, again = self.launch()
        self.assertEqual(URL, again)

    def test_start_holds_the_app_until_it_is_stopped(self):
        holder, url = self.launch()
        self.assertEqual(URL, url)
        app = self.pid()
        time.sleep(1)
        self.assertIsNone(holder.poll())
        os.kill(app, 0)
        self.stop()
        self.assertEqual(0, holder.wait(timeout=30))
        self.assertTrue(self.gone(app), "the app is still running")

    def test_stop_ends_the_app_and_the_gamescope_it_ran_in(self):
        (self.bin / "gamescope").write_text('#!/bin/bash\nwhile [ "$1" != -- ]; do shift; done\nshift\n"$@" &\nwait\n')
        self.assertEqual(URL, self.launch()[1])
        gamescope = self.pid()
        app = int(next(self.proc.iterdir()).name)
        self.assertNotEqual(gamescope, app)
        os.kill(app, 0)
        self.stop()
        self.assertFalse((self.state / "pid").exists())
        self.assertTrue(self.gone(gamescope), "gamescope is still running")
        self.assertTrue(self.gone(app), "the app is still running")

    def test_a_launch_for_another_directory_is_busy_and_left_alone(self):
        other = self.tmp / "other"
        (other / "app").mkdir(parents=True)
        self.addCleanup(self.stop)
        self.assertEqual(URL, self.launch()[1])
        out = self.run_helper("start", str(other))
        self.assertEqual(3, out.returncode)
        self.assertEqual("busy", out.stdout.strip())
        pid = self.pid()
        self.run_helper("stop", str(other))
        self.assertEqual(pid, self.pid())
        os.kill(pid, 0)

    def test_each_launch_starts_a_fresh_log(self):
        first, _ = self.launch()
        self.stop()
        first.wait(timeout=30)
        self.addCleanup(self.stop)
        self.assertEqual(URL, self.launch()[1])
        self.assertEqual(1, (self.state / "log").read_text().count("Debugger listening"))
        self.assertTrue((self.state / "log.1").exists())

    def test_stop_without_a_launch_is_quiet(self):
        out = self.run_helper("stop", str(self.dir))
        self.assertEqual(0, out.returncode)
        self.assertEqual("", out.stderr)
        self.assertEqual("", out.stdout)

    def test_bad_arguments_are_refused(self):
        self.assertEqual(2, self.run_helper("start", str(self.tmp / "missing")).returncode)
        self.assertEqual(2, self.run_helper("launch", str(self.dir)).returncode)
        self.assertEqual(2, self.run_helper("hold", str(self.dir)).returncode)


if __name__ == "__main__":
    unittest.main()
