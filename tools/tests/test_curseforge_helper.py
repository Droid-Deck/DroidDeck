import os
import shutil
import socket
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

HELPER = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-curseforge"


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class CurseForgeHelperTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.proc = self.tmp / "proc"
        self.proc.mkdir()
        self.dir = self.tmp / "curseforge"
        (self.dir / "app").mkdir(parents=True)
        self.state = self.tmp / "state"
        self.port = free_port()
        self.runner = self.tmp / "runner"
        self.seen = self.tmp / "seen"
        self.runner.write_text(
            "#!/bin/bash\n"
            "printf '%%s\\n' \"DISPLAY=$DISPLAY\" \"XAUTHORITY=${XAUTHORITY-}\" \"WAYLAND=${WAYLAND_DISPLAY-}\" "
            "\"DBUS=${DBUS_SESSION_BUS_ADDRESS-}\" \"$@\" > %s\n"
            "mkdir -p %s/$$ && printf '%%s\\0' bash \"$0\" \"$@\" > %s/$$/cmdline\n"
            "exec python3 -c 'import http.server,json,sys\n"
            "class H(http.server.BaseHTTPRequestHandler):\n"
            "    def do_GET(self):\n"
            "        b=json.dumps([{\"type\":\"node\"}]).encode(); self.send_response(200); self.end_headers(); self.wfile.write(b)\n"
            "    def log_message(self,*a): pass\n"
            "http.server.HTTPServer((\"127.0.0.1\",int(sys.argv[1])),H).serve_forever()' %d\n"
            % (self.seen, self.proc, self.proc, self.port))
        self.runner.chmod(0o755)

    def fake(self, pid, *argv):
        d = self.proc / str(pid)
        d.mkdir()
        (d / "cmdline").write_bytes(b"".join(a.encode() + b"\0" for a in argv))

    def run_helper(self, *args, env=None):
        base = {k: v for k, v in os.environ.items() if k not in ("DISPLAY", "XAUTHORITY")}
        base.update(DD_PROC=str(self.proc), DD_CURSEFORGE_STATE=str(self.state), DD_APPIMAGE_RUN=str(self.runner),
                    DD_CURSEFORGE_WAIT="10", WAYLAND_DISPLAY="wayland-0", DBUS_SESSION_BUS_ADDRESS="unix:path=/x")
        base.update(env or {})
        return subprocess.run(["bash", str(HELPER), *args], env=base, capture_output=True, text=True, timeout=60)

    def stop(self):
        self.run_helper("stop", str(self.dir), str(self.port))

    def test_the_desktop_x_display_is_found_from_xwayland(self):
        self.fake(40, "/usr/bin/kwin_wayland", ":9")
        self.fake(41, "/usr/bin/Xwayland", ":0", "-rootless", "-auth", "/run/droiddeck/xauth_abc", "-listenfd", "5")
        out = self.run_helper("display", "", str(self.port))
        self.assertEqual(0, out.returncode, out.stderr)
        self.assertEqual([":0", "/run/droiddeck/xauth_abc"], out.stdout.splitlines())

    def test_the_session_display_wins(self):
        self.fake(41, "/usr/bin/Xwayland", ":3")
        out = self.run_helper("display", "", str(self.port), env={"DISPLAY": ":0"})
        self.assertEqual(":0", out.stdout.splitlines()[0])

    def test_no_display_is_reported(self):
        out = self.run_helper("start", str(self.dir), str(self.port))
        self.assertEqual(4, out.returncode)

    def test_a_running_instance_without_the_inspector_is_busy(self):
        self.fake(50, "/usr/bin/FEX", str(self.dir) + "/app/curseforge", "--no-sandbox")
        out = self.run_helper("start", str(self.dir), str(self.port), env={"DISPLAY": ":0"})
        self.assertEqual(3, out.returncode)
        self.assertEqual("busy", out.stdout.strip())
        self.stop()
        self.assertTrue((self.proc / "50").exists())

    def test_start_runs_hidden_on_x11_with_a_loopback_inspector_and_stop_ends_it(self):
        self.fake(41, "/usr/bin/Xwayland", ":0", "-auth", "/run/droiddeck/xauth_abc")
        out = self.run_helper("start", str(self.dir), str(self.port))
        self.addCleanup(self.stop)
        self.assertEqual(0, out.returncode, out.stderr)
        self.assertEqual("ready", out.stdout.strip())
        seen = self.seen.read_text().splitlines()
        self.assertEqual(["DISPLAY=:0", "XAUTHORITY=/run/droiddeck/xauth_abc", "WAYLAND=", "DBUS=", str(self.dir),
                          "--minimized", "--inspect=127.0.0.1:%d" % self.port], seen)
        again = self.run_helper("start", str(self.dir), str(self.port))
        self.assertEqual("ready", again.stdout.strip())
        pid = int((self.state / "pid").read_text())
        self.stop()
        self.assertFalse((self.state / "pid").exists())
        for _ in range(50):
            try:
                os.kill(pid, 0)
            except ProcessLookupError:
                break
            time.sleep(0.1)
        else:
            self.fail("the started instance is still running")

    def test_bad_arguments_are_refused(self):
        self.assertEqual(2, self.run_helper("start", str(self.tmp / "missing"), str(self.port)).returncode)
        self.assertEqual(2, self.run_helper("start", str(self.dir), "92x").returncode)
        self.assertEqual(2, self.run_helper("launch", str(self.dir)).returncode)


if __name__ == "__main__":
    unittest.main()
