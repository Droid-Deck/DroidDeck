import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "device-smoke.sh"
FAKE_ADB = r'''#!/usr/bin/env python3
import json, os, shlex, sys
from pathlib import Path
p = Path(os.environ["SMOKE_STATE"])
s = json.loads(p.read_text())
a = sys.argv[1:]
if a == ["get-state"]:
    print("device")
elif a[:1] == ["shell"]:
    a = shlex.split(a[1])
    if a[:1] == ["wm"]:
        kind = a[1]
        if len(a) == 2:
            if s.get("unreadable") == kind:
                print("permission denied")
            else:
                print("Physical " + kind + ": " + ("400" if kind == "density" else "1080x1920"))
                if s.get(kind): print("Override " + kind + ": " + s[kind])
        else:
            value = a[2]
            s["writes"].append([kind, value])
            restoring = value in ("reset", "420", "1000x1800")
            if not (s.get("ignored") == kind or restoring and s.get("restore_ignored") == kind):
                s[kind] = "" if value == "reset" else value
            p.write_text(json.dumps(s))
    elif a[:1] == ["getconf"]: print("4096")
    elif a[:1] == ["pidof"]: print("123")
    elif a[:2] == ["dumpsys", "activity"]:
        print("topResumedActivity=ActivityRecord{abc u0 com.droiddeck.launcher/.MainActivity t1}")
    elif a[:3] == ["pm", "list", "packages"]:
        if s.get("query_failed"): sys.exit(1)
        print("package:" + s.get("package", "com.droiddeck.launcher"))
    elif a[:2] == ["am", "start"] and s.get("launch_failed"):
        print("Error: activity could not be started")
        sys.exit(1 if s["launch_failed"] == "exit" else 0)
    elif a[:1] == ["run-as"]:
        if s.get("session"):
            s.setdefault("log_commands", []).append(a)
            p.write_text(json.dumps(s))
            if "ls" in a: print(s["session"])
            elif a[-1].endswith("/device.txt"): print(s.get("report", ""))
            elif a[-1].endswith("/app.log"): print(s.get("app_log", ""))
        else: print("package not debuggable")
'''


class DeviceSmokeTest(unittest.TestCase):
    def run_smoke(self, **scenario):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            adb = root / "adb"
            adb.write_text(FAKE_ADB)
            adb.chmod(0o755)
            sleep = root / "sleep"
            sleep.write_text("#!/bin/sh\nexit 0\n")
            sleep.chmod(0o755)
            state = root / "state.json"
            state.write_text(json.dumps({"density": "", "size": "", "writes": [], **scenario}))
            env = dict(os.environ, ADB=str(adb), SMOKE_STATE=str(state), PATH=str(root) + os.pathsep + os.environ["PATH"])
            result = subprocess.run(["bash", str(SCRIPT), "--no-fold"], env=env, capture_output=True, text=True, timeout=30)
            return result, json.loads(state.read_text())

    def test_restores_preexisting_display_overrides(self):
        result, state = self.run_smoke(density="420", size="1000x1800")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("420", state["density"])
        self.assertEqual("1000x1800", state["size"])

    def test_ignored_display_changes_do_not_pass(self):
        for kind in ("density", "size"):
            with self.subTest(kind=kind):
                result, state = self.run_smoke(ignored=kind)
                self.assertEqual(1, result.returncode, result.stdout)
                self.assertIn("wm " + kind + " failed", result.stdout)
                self.assertNotIn("PASS  same process " + ("after" if kind == "density" else "at"), result.stdout)
                self.assertEqual("", state[kind])

    def test_ignored_restore_remains_a_failure_and_is_retried_on_exit(self):
        for kind in ("density", "size"):
            with self.subTest(kind=kind):
                result, state = self.run_smoke(restore_ignored=kind)
                self.assertEqual(1, result.returncode, result.stdout)
                self.assertIn("could not restore", result.stdout)
                self.assertNotEqual("", state[kind])
                self.assertEqual(2, state["writes"].count([kind, "reset"]))

    def test_unreadable_original_settings_are_not_changed(self):
        for kind in ("density", "size"):
            with self.subTest(kind=kind):
                result, state = self.run_smoke(unreadable=kind)
                self.assertEqual(0, result.returncode, result.stdout)
                self.assertFalse(any(item[0] == kind for item in state["writes"]))

    def test_package_filter_does_not_accept_another_variant_or_a_failed_query(self):
        for scenario in ({"package": "com.droiddeck.launcher.debug"}, {"query_failed": True}):
            with self.subTest(scenario=scenario):
                result, state = self.run_smoke(**scenario)
                self.assertEqual(2, result.returncode, result.stdout)
                self.assertEqual([], state["writes"])

    def test_failed_launch_is_not_masked_by_an_existing_process(self):
        for failure in ("exit", "message"):
            with self.subTest(failure=failure):
                result, _ = self.run_smoke(launch_failed=failure)
                self.assertEqual(1, result.returncode, result.stdout)
                self.assertIn("could not launch", result.stdout)

    def test_fast_path_requires_a_session_activation_log(self):
        for active in (False, True):
            with self.subTest(active=active):
                result, _ = self.run_smoke(
                    session="session", report="proot fast path (eligible) true",
                    app_log="proot: fast path on (66 binds)" if active else "proot: fast path off - too many binds",
                )
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertEqual(active, "PASS  proot fast path was on" in result.stdout)

    def test_session_name_remains_one_remote_argument(self):
        session = "x';id;#"
        result, state = self.run_smoke(session=session)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn(["run-as", "com.droiddeck.launcher", "cat", "files/logs/" + session + "/events.jsonl"], state["log_commands"])


if __name__ == "__main__":
    unittest.main()
