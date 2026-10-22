import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "device-smoke.sh"
FAKE_ADB = r'''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
p = Path(os.environ["SMOKE_STATE"])
s = json.loads(p.read_text())
a = sys.argv[1:]
if a == ["get-state"]:
    print("device")
elif a[:1] == ["shell"]:
    a = a[1:]
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
    elif a[:3] == ["pm", "list", "packages"]: print("package:com.droiddeck.launcher")
    elif a[:1] == ["run-as"]: print("package not debuggable")
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


if __name__ == "__main__":
    unittest.main()
