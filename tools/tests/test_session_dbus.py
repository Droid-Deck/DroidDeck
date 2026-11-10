"""Real subprocess checks that droiddeck-session starts its bus before the compositor."""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SESSION = ROOT / "tools/linuxfs/overlay/usr/local/bin/droiddeck-session"

FIXTURE = '''#!/usr/bin/env python3
import ctypes, json, os, signal, subprocess, sys, time


def stat_ppid(pid):
    try:
        with open(f"/proc/{pid}/stat", "r", encoding="utf-8") as f:
            data = f.read()
        return int(data.rsplit(") ", 1)[1].split()[1])
    except (FileNotFoundError, IndexError, ProcessLookupError, ValueError):
        return None


def wait_orphan(pid, timeout=2.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            wpid, status = os.waitpid(pid, os.WNOHANG)
            if wpid == pid:
                return status
        except ChildProcessError:
            return None
        time.sleep(0.05)
    return None


libc = ctypes.CDLL(None, use_errno=True)
if libc.prctl(36, 1, 0, 0, 0) != 0:
    raise OSError(ctypes.get_errno(), "prctl(PR_SET_CHILD_SUBREAPER) failed")

harness = sys.argv[1]
fpid = os.getpid()
sib = subprocess.Popen(["sleep", "60"])
orphan_pid = None
orphan_adopted = False
orphan_reaped = False
orphan_killed = False
out, rc = "", None
try:
    helper = (
        "import subprocess; "
        "p = subprocess.Popen(['sleep', '60'], start_new_session=True, "
        "stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL); "
        "print(p.pid)"
    )
    spawner = subprocess.run([sys.executable, "-c", helper],
                             capture_output=True, text=True, check=True, timeout=5)
    orphan_pid = int(spawner.stdout.strip())
    deadline = time.monotonic() + 5.0
    while time.monotonic() < deadline:
        if stat_ppid(orphan_pid) == fpid:
            orphan_adopted = True
            break
        time.sleep(0.05)
    res = subprocess.run(["bash", harness, "steam"],
                         env=dict(os.environ, BL_INSIDE="1"),
                         capture_output=True, text=True, timeout=15)
    out, rc = res.stdout, res.returncode
    if orphan_adopted:
        status = wait_orphan(orphan_pid)
        orphan_reaped = status is not None
        orphan_killed = (
            orphan_reaped
            and os.WIFSIGNALED(status)
            and os.WTERMSIG(status) == signal.SIGKILL
        )
except subprocess.TimeoutExpired as exc:
    out = exc.stdout or ""
    if isinstance(out, bytes):
        out = out.decode()
    rc = "timeout"
finally:
    alive = sib.poll() is None
    sib.kill()
    try:
        sib.wait(timeout=5)
    except subprocess.TimeoutExpired:
        pass
    if orphan_pid is not None and not orphan_reaped:
        try:
            os.kill(orphan_pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        wait_orphan(orphan_pid)

ppid = bus = None
for line in out.splitlines():
    if line.startswith("PPID="):
        ppid = int(line[5:])
    elif line.startswith("BUS="):
        bus = line[4:]
print(json.dumps({
    "fixture_pid": fpid,
    "inner_ppid": ppid,
    "inner_bus": bus,
    "fixture_bus": os.environ.get("DBUS_SESSION_BUS_ADDRESS", ""),
    "sibling_killed": not alive,
    "orphan_adopted": orphan_adopted,
    "orphan_killed": orphan_killed,
    "inner_rc": rc,
}))
'''

HARNESS = r"""#!/bin/bash
set -u
export HOME=${{HOME:-/root}}
mode=${{1:-desktop}}
shift || true
{guard_before}
if [ -z "${{BL_INSIDE:-}}" ]; then
  exec "$DD_FIXTURE" "$0"
fi
{guard_after}
printf 'PPID=%s\n' "$PPID"
printf 'BUS=%s\n' "${{DBUS_SESSION_BUS_ADDRESS:-}}"
{reap}
reap_orphans
"""

SETUP = 'if [ -z "${BL_INSIDE:-}" ]; then\n  /usr/local/bin/droiddeck-fonts'


def extract():
    src = SESSION.read_text(encoding="utf-8")
    gstart = src.index("# Steam and its helpers share one session bus per session")
    guard = src[gstart:src.index("\nfi\n", gstart) + 4]
    rstart = src.index("reap_orphans() {")
    reap = src[rstart:src.index("\n}\n", rstart) + 3]
    return src, guard, reap


def build_harness():
    src, guard, reap = extract()
    assert src.index("shift || true") < src.index(guard)
    if src.index(guard) < src.index(SETUP):
        before, after = guard, ""
    else:
        before, after = "", guard
    return HARNESS.format(guard_before=before, guard_after=after, reap=reap)


@unittest.skipUnless(
    sys.platform.startswith("linux")
    and shutil.which("dbus-run-session")
    and shutil.which("bash"),
    "requires Linux and dbus-run-session and bash on PATH",
)
class SessionDBusTest(unittest.TestCase):
    def _run(self, inherited):
        with tempfile.TemporaryDirectory() as td:
            td = Path(td)
            harness = td / "harness.sh"
            fixture = td / "fixture.py"
            harness.write_text(build_harness(), encoding="utf-8")
            harness.chmod(0o755)
            fixture.write_text(FIXTURE, encoding="utf-8")
            fixture.chmod(0o755)
            env = {k: v for k, v in os.environ.items()
                   if k not in ("DBUS_SESSION_BUS_ADDRESS", "BL_INSIDE")}
            env["DD_FIXTURE"] = str(fixture)
            cmd = ["bash", str(harness), "steam"]
            if inherited:
                cmd = ["dbus-run-session", "--"] + cmd
            res = subprocess.run(cmd, env=env, cwd=td, capture_output=True,
                                 text=True, timeout=30)
            self.assertEqual(res.returncode, 0, res.stderr)
            return json.loads(res.stdout.strip().splitlines()[-1])

    def _check(self, data):
        self.assertEqual(data["inner_ppid"], data["fixture_pid"])
        self.assertTrue(data["fixture_bus"])
        self.assertEqual(data["inner_bus"], data["fixture_bus"])
        self.assertTrue(data["sibling_killed"])
        self.assertTrue(data["orphan_adopted"])
        self.assertTrue(data["orphan_killed"])
        self.assertEqual(data["inner_rc"], 0)

    def test_fresh_bus(self):
        self._check(self._run(False))

    def test_inherited_bus(self):
        self._check(self._run(True))


if __name__ == "__main__":
    unittest.main()
