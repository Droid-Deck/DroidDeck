"""Real subprocess checks that droiddeck-session starts its bus before the compositor."""
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SESSION = ROOT / "tools/linuxfs/overlay/usr/local/bin/droiddeck-session"

FIXTURE = '''#!/usr/bin/env python3
import json, os, subprocess, sys

harness = sys.argv[1]
sib = subprocess.Popen(["sleep", "60"])
try:
    res = subprocess.run(["bash", harness, "steam"],
                         env=dict(os.environ, BL_INSIDE="1"),
                         capture_output=True, text=True, timeout=15)
    out, rc = res.stdout, res.returncode
except subprocess.TimeoutExpired as exc:
    out = exc.stdout or ""
    if isinstance(out, bytes):
        out = out.decode()
    rc = "timeout"
finally:
    alive = sib.poll() is None
    sib.kill()
    sib.wait()

ppid = bus = None
for line in out.splitlines():
    if line.startswith("PPID="):
        ppid = int(line[5:])
    elif line.startswith("BUS="):
        bus = line[4:]
print(json.dumps({
    "fixture_pid": os.getpid(),
    "inner_ppid": ppid,
    "inner_bus": bus,
    "fixture_bus": os.environ.get("DBUS_SESSION_BUS_ADDRESS", ""),
    "sibling_killed": not alive,
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
    shutil.which("dbus-run-session") and shutil.which("bash"),
    "missing required tools: dbus-run-session and bash must both be on PATH",
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
        self.assertEqual(data["inner_rc"], 0)

    def test_fresh_bus(self):
        self._check(self._run(False))

    def test_inherited_bus(self):
        self._check(self._run(True))


if __name__ == "__main__":
    unittest.main()
