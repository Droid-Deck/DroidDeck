import os
import shutil
import signal
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

HELPER = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-curseforge"
URL = "ws://127.0.0.1:43117/0f8fad5b-d9cb-469f-a165-70867728950e"


class CurseForgeHelperTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.proc = self.tmp / "proc"
        self.proc.mkdir()
        self.dir = self.tmp / "curseforge"
        (self.dir / "app").mkdir(parents=True)
        self.state = self.tmp / "state"
        self.runner = self.tmp / "runner"
        self.seen = self.tmp / "seen"
        self.runner.write_text(
            "#!/bin/bash\n"
            "printf '%%s\\n' \"DISPLAY=$DISPLAY\" \"XAUTHORITY=${XAUTHORITY-}\" \"WAYLAND=${WAYLAND_DISPLAY-}\" "
            "\"DBUS=${DBUS_SESSION_BUS_ADDRESS-}\" \"$@\" > %s\n"
            "mkdir -p %s/$$ && printf '%%s\\0' bash \"$0\" \"$@\" > %s/$$/cmdline\n"
            "echo 'Debugger listening on %s'\n"
            "echo 'For help, see: https://nodejs.org/en/docs/inspector'\n"
            "exec sleep 60\n"
            % (self.seen, self.proc, self.proc, URL))
        self.runner.chmod(0o755)

    def fake(self, pid, *argv):
        d = self.proc / str(pid)
        d.mkdir()
        (d / "cmdline").write_bytes(b"".join(a.encode() + b"\0" for a in argv))

    def env(self, extra=None):
        base = {k: v for k, v in os.environ.items() if k not in ("DISPLAY", "XAUTHORITY")}
        base.update(DD_PROC=str(self.proc), DD_CURSEFORGE_STATE=str(self.state), DD_APPIMAGE_RUN=str(self.runner),
                    DD_CURSEFORGE_WAIT="10", WAYLAND_DISPLAY="wayland-0", DBUS_SESSION_BUS_ADDRESS="unix:path=/x")
        base.update(extra or {})
        return base

    def run_helper(self, *args, env=None):
        return subprocess.run(["bash", str(HELPER), *args], env=self.env(env), capture_output=True, text=True, timeout=60)

    def stop(self):
        self.run_helper("stop", str(self.dir))

    def gone(self, pid):
        for _ in range(50):
            try:
                os.kill(pid, 0)
            except ProcessLookupError:
                return True
            time.sleep(0.1)
        return False

    def test_the_desktop_x_display_is_found_from_xwayland(self):
        self.fake(40, "/usr/bin/kwin_wayland", ":9")
        self.fake(41, "/usr/bin/Xwayland", ":0", "-rootless", "-auth", "/run/droiddeck/xauth_abc", "-listenfd", "5")
        out = self.run_helper("display", "")
        self.assertEqual(0, out.returncode, out.stderr)
        self.assertEqual([":0", "/run/droiddeck/xauth_abc"], out.stdout.splitlines())

    def test_the_session_display_wins(self):
        self.fake(41, "/usr/bin/Xwayland", ":3")
        out = self.run_helper("display", "", env={"DISPLAY": ":0"})
        self.assertEqual(":0", out.stdout.splitlines()[0])

    def test_no_display_is_reported(self):
        out = self.run_helper("start", str(self.dir))
        self.assertEqual(4, out.returncode)

    def test_a_running_instance_without_the_inspector_is_busy(self):
        self.fake(50, "/usr/bin/FEX", str(self.dir) + "/app/curseforge", "--no-sandbox")
        out = self.run_helper("start", str(self.dir), env={"DISPLAY": ":0"})
        self.assertEqual(3, out.returncode)
        self.assertEqual("busy", out.stdout.strip())
        self.stop()
        self.assertTrue((self.proc / "50").exists())

    def test_start_uses_a_random_port_and_a_secret_inspector_url_kept_private(self):
        self.fake(41, "/usr/bin/Xwayland", ":0", "-auth", "/run/droiddeck/xauth_abc")
        out = self.run_helper("start", str(self.dir))
        self.addCleanup(self.stop)
        self.assertEqual(0, out.returncode, out.stderr)
        self.assertEqual(URL, out.stdout.strip())
        seen = self.seen.read_text().splitlines()
        self.assertEqual(["DISPLAY=:0", "XAUTHORITY=/run/droiddeck/xauth_abc", "WAYLAND=", "DBUS=", str(self.dir),
                          "--minimized", "--inspect=127.0.0.1:0", "--inspect-publish-uid=stderr"], seen)
        self.assertEqual(0o700, self.state.stat().st_mode & 0o777)
        self.assertEqual(0o600, (self.state / "log").stat().st_mode & 0o777)
        again = self.run_helper("start", str(self.dir))
        self.assertEqual(URL, again.stdout.strip())
        pid = int((self.state / "pid").read_text())
        self.stop()
        self.assertFalse((self.state / "pid").exists())
        self.assertTrue(self.gone(pid), "the started instance is still running")

    def test_hold_keeps_the_app_until_it_is_signalled(self):
        held = subprocess.Popen(["bash", str(HELPER), "hold", str(self.dir)], env=self.env({"DISPLAY": ":0"}),
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.addCleanup(lambda: held.poll() is None and held.kill())
        for _ in range(100):
            if (self.state / "pid").exists() and self.seen.exists():
                break
            time.sleep(0.1)
        pid = int((self.state / "pid").read_text())
        time.sleep(1)
        self.assertIsNone(held.poll())
        held.send_signal(signal.SIGTERM)
        self.assertEqual(0, held.wait(timeout=30))
        self.assertTrue(self.gone(pid), "hold left the app running")
        self.assertFalse((self.state / "pid").exists())

    def test_hold_ends_when_the_app_exits(self):
        self.runner.write_text("#!/bin/bash\nmkdir -p %s/$$ && printf '%%s\\0' bash \"$0\" \"$@\" > %s/$$/cmdline\n"
                               "echo 'Debugger listening on %s'\nsleep 2\nrm -r %s/$$\n" % (self.proc, self.proc, URL, self.proc))
        out = subprocess.run(["bash", str(HELPER), "hold", str(self.dir)], env=self.env({"DISPLAY": ":0"}),
                             capture_output=True, text=True, timeout=60)
        self.assertEqual(0, out.returncode, out.stderr)

    def test_bad_arguments_are_refused(self):
        self.assertEqual(2, self.run_helper("start", str(self.tmp / "missing")).returncode)
        self.assertEqual(2, self.run_helper("launch", str(self.dir)).returncode)


if __name__ == "__main__":
    unittest.main()
