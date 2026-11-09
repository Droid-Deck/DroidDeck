import base64
import contextlib
import importlib.machinery
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import textwrap
import unittest
import unittest.mock
import zipfile

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))


def load(name):
    loader = importlib.machinery.SourceFileLoader(name.replace("-", "_"), str(TOOLS / name))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


ctl = load("droiddeckctl")
scenario = load("droiddeck-scenario")

FAKE_ADB = textwrap.dedent('''\
    #!/usr/bin/env python3
    import base64, json, os, sys
    config_path = os.environ["FAKE_ADB_CONFIG"]
    config = json.load(open(config_path))
    args = sys.argv[1:]
    if args[:1] == ["-s"]:
        args = args[2:]
    entry = {"args": args}
    if args[:1] == ["get-state"]:
        print("device"); sys.exit(0)
    if args[:3] == ["shell", "content", "call"]:
        method = args[args.index("--method") + 1]
        request = None
        if "--extra" in args:
            request = json.loads(base64.b64decode(args[args.index("--extra") + 1].split(":", 2)[2]))
        entry.update(method=method, request=request)
        answers = config["provider"].get(method, {"ok": False, "error": {"code": "UNKNOWN_COMMAND", "message": method}})
        if isinstance(answers, list):
            count = config.setdefault("counts", {}).get(method, 0)
            config["counts"][method] = count + 1
            answers = answers[min(count, len(answers) - 1)]
            json.dump(config, open(config_path, "w"))
        print("Result: Bundle[{json=" + json.dumps(answers) + "}]")
    elif args[:3] == ["shell", "dumpsys", "display"]:
        print(config.get("dumpsys_display", ""))
    elif args[:3] == ["shell", "dumpsys", "power"]:
        print("  mWakefulness=Awake")
    elif args[:3] == ["shell", "dumpsys", "SurfaceFlinger"] and "-dump" in args:
        print(config.get("timestats", ""))
    elif args[:2] == ["exec-out", "screencap"]:
        sys.stdout.buffer.write(b"\\x89PNG fake")
    elif args[:3] == ["shell", "dumpsys", "battery"]:
        temperatures = config.get("battery", [""])
        count = config.setdefault("counts", {}).get("battery", 0)
        config["counts"]["battery"] = count + 1
        json.dump(config, open(config_path, "w"))
        print(temperatures[min(count, len(temperatures) - 1)])
    elif args[:3] == ["shell", "dumpsys", "thermalservice"]:
        print(config.get("thermalservice", ""))
    elif args[:3] == ["shell", "grep", "."]:
        print(config.get("cpufreq", ""))
    elif args[:1] == ["pull"] and args[1] in config.get("files", {}):
        open(args[2], "wb").write(base64.b64decode(config["files"][args[1]]))
    elif args[:1] == ["pull"]:
        entry["denied"] = True
        print(f"adb: error: failed to stat remote object '{args[1]}': Permission denied", file=sys.stderr)
    elif args[:2] == ["shell", "rm"] and config.get("fail_cleanup"):
        entry["denied"] = True
        print("adb: error: cleanup denied", file=sys.stderr)
    with open(config["log"], "a") as log:
        log.write(json.dumps(entry) + "\\n")
    sys.exit(1 if entry.get("denied") else 0)
''')

DUMPSYS_DISPLAY = textwrap.dedent('''\
    Display Devices: size=2
      DisplayDeviceInfo{"Built-in Screen": uniqueId="local:111", 1080 x 1920}
      DisplayDeviceInfo{"Screen-2": uniqueId="local:222", 1080 x 1240}
    Logical Displays: size=2
      Display 0:
        mDisplayId=0
        mPrimaryDisplayDevice=Built-in Screen
        mBaseDisplayInfo=DisplayInfo{"Built-in Screen", displayId 0", real 1080 x 1920, x}
      Display 4:
        mDisplayId=4
        mPrimaryDisplayDevice=Screen-2
        mBaseDisplayInfo=DisplayInfo{"Screen-2", displayId 4", real 1080 x 1240, x}
''')


def timestats_record(name, frames, histogram, hz):
    bins = " ".join(f"{ms}ms={histogram.get(ms, 0)}" for ms in (8, 16, 17, 25, 33, 50))
    return (f"displayRefreshRate = {hz} fps\nrenderRate = {hz} fps\nuid = 10479\nlayerName = {name}\n"
            f"packageName = \ntotalFrames = {frames}\ndroppedFrames = 0\n"
            f"present2present histogram is as below:\n{bins}\nlatch2present histogram is as below:\n8ms={frames}\n")


TIMESTATS_SPLIT = "SurfaceFlinger TimeStats:\ntotalFrames = 2000\n" + "".join([
    timestats_record("droiddeck_wayland_game#6110", 900, {16: 700, 33: 199}, 60),
    timestats_record("droiddeck_wayland_game#6110", 900, {16: 850, 25: 49}, 120),
    timestats_record("com.droiddeck.launcher/com.droiddeck.launcher.SessionActivity#6097", 8, {16: 7}, 60),
    timestats_record("f291fb6 SurfaceView[com.droiddeck.launcher/com.droiddeck.launcher.SessionActivity]#6101",
                     30, {33: 29}, 60),
    timestats_record("StatusBar#106", 1, {}, 60),
])

TIMESTATS_SURFACEVIEW = "SurfaceFlinger TimeStats:\n" + "".join([
    timestats_record("a1b2c3 SurfaceView[com.droiddeck.launcher/com.droiddeck.launcher.SessionActivity]#77",
                     1800, {16: 1796, 33: 3}, 120),
    timestats_record("a1b2c3 SurfaceView[com.other.app/com.other.app.Main]#12", 500, {8: 499}, 120),
])


THERMALSERVICE = textwrap.dedent('''\
    IsStatusOverride: false
    Thermal Status: 2
    Cached temperatures:
    \tTemperature{mValue=41.3, mType=3, mName=skin, mStatus=2}
''')

CPUFREQ = textwrap.dedent('''\
    /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq:3532800
    /sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq:2918400
    /sys/devices/system/cpu/cpufreq/policy0/cpuinfo_max_freq:3532800
    /sys/devices/system/cpu/cpufreq/policy6/cpuinfo_max_freq:4473600
''')


def battery(tenths):
    return f"Current Battery Service state:\n  level: 80\n  temperature: {tenths}\n  technology: Li-poly"
PRIVATE_LOG_DIR = "/data/user/0/com.droiddeck.launcher/files/logs/sessions/s1"
AGENT_LOGS_DIR = "/storage/emulated/0/Android/data/com.droiddeck.launcher/files/agent-logs"
EXPORTED_DIR = AGENT_LOGS_DIR + "/123e4567-e89b-12d3-a456-426614174000"
EXPORTED_ZIP = EXPORTED_DIR + "/DroidDeck-s1.zip"


def share_zip(entries):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as z:
        for name, text in entries.items():
            z.writestr(name, text)
    return base64.b64encode(buffer.getvalue()).decode()


def ended_state():
    ended = state(phase="IDLE")
    ended["session"].update(logDir=PRIVATE_LOG_DIR, artifactsAvailable=True, artifactsComplete=True)
    return ended


def state(phase="READY", focused=None, focusable=()):
    focus = None if focused is None else {"focusedApp": focused, "focusableApps": list(focusable),
                                          "baselayerAppIds": [], "seq": 1, "t": 0}
    return {"ok": True, "schema": 2, "session": {"id": "s1", "phase": phase, "running": phase == "READY",
                                                  "focus": focus, "failure": None}}


class FakeAdbTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.dir = Path(self.temp.name)
        self.adb = self.dir / "adb"
        self.adb.write_text(FAKE_ADB)
        self.adb.chmod(self.adb.stat().st_mode | stat.S_IEXEC)
        self.config = self.dir / "config.json"
        self.log = self.dir / "log.jsonl"
        self.configure({})
        os.environ["FAKE_ADB_CONFIG"] = str(self.config)
        self.addCleanup(os.environ.pop, "FAKE_ADB_CONFIG", None)

    def configure(self, provider, **extra):
        self.config.write_text(json.dumps(dict({"provider": provider, "log": str(self.log)}, **extra)))

    def run_ctl(self, *argv):
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()):
            code = ctl.main(["--adb", str(self.adb), "--serial", "fake-1", *argv])
        return code, json.loads(out.getvalue())

    def calls(self, method=None):
        if not self.log.exists():
            return []
        entries = [json.loads(line) for line in self.log.read_text().splitlines()]
        return [e for e in entries if method is None or e.get("method") == method]


class DroiddeckctlTest(FakeAdbTest):
    def test_oversized_requests_are_refused_before_adb(self):
        with self.assertRaises(ctl.ControlError) as caught:
            ctl.provider_call(str(self.adb), "fake-1", "guest", request={"stdin": "x" * ctl.REQUEST_CAP})
        self.assertEqual("INVALID_REQUEST", caught.exception.code)
        self.assertFalse(self.calls())

    def test_a_variant_package_is_reached_through_its_own_provider_and_activity(self):
        with unittest.mock.patch.dict(os.environ, {"DROIDDECK_PACKAGE": "com.antutu.benchmark.full"}):
            variant = load("droiddeckctl")
        self.assertEqual("content://com.antutu.benchmark.full.agent", variant.AUTHORITY)
        with self.assertRaises(variant.ControlError):
            variant.start_agent_activity(str(self.adb), "fake-1", {"mode": "steam"})
        started = [e["args"] for e in self.calls() if e["args"][:3] == ["shell", "am", "start"]]
        self.assertIn("com.antutu.benchmark.full/com.droiddeck.launcher.agent.AgentStartActivity", started[0])

    def test_a_malformed_package_is_refused_before_adb(self):
        with unittest.mock.patch.dict(os.environ, {"DROIDDECK_PACKAGE": "com.example;reboot"}):
            variant = load("droiddeckctl")
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()):
            code = variant.main(["--adb", str(self.adb), "--serial", "fake-1", "state"])
        self.assertEqual(2, code)
        self.assertEqual("INVALID_REQUEST", json.loads(out.getvalue())["error"]["code"])
        self.assertFalse(self.calls())

    def test_state_passes_through_with_the_serial(self):
        self.configure({"state": state()})
        code, out = self.run_ctl("state")
        self.assertEqual(0, code)
        self.assertEqual("READY", out["session"]["phase"])
        self.assertEqual("fake-1", out["deviceSerial"])

    def test_launch_sends_the_app_id_and_waits_for_focus(self):
        self.configure({
            "launch": {"ok": True, "command": "launch", "appId": "489830"},
            "state": [state(focused=769, focusable=[769]), state(focused=489830, focusable=[769])],
        })
        code, out = self.run_ctl("launch", "489830", "--wait", "--timeout", "1")
        self.assertEqual(5, code)
        self.assertEqual("TIMEOUT", out["error"]["code"])
        self.assertIn("focused app 489830", out["error"]["message"])
        self.assertEqual({"appId": "489830"}, self.calls("launch")[0]["request"])

        self.configure({
            "launch": {"ok": True, "command": "launch", "appId": "489830"},
            "state": [state(focused=769, focusable=[769]), state(focused=489830, focusable=[489830, 769])],
        })
        code, out = self.run_ctl("launch", "489830", "--wait", "--timeout", "5")
        self.assertEqual(0, code)
        self.assertEqual(489830, out["focus"]["focusedApp"])

    def test_start_reuse_keeps_a_running_session(self):
        running = state(focused=769)
        running["session"]["mode"] = "steam"
        self.configure({"state": running})
        code, out = self.run_ctl("start", "steam", "--reuse", "--wait")
        self.assertEqual(0, code)
        self.assertTrue(out["reused"])
        self.assertFalse([c for c in self.calls() if c["args"][:2] == ["shell", "am"]])

    def test_start_without_reuse_refuses_a_running_session(self):
        running = state()
        running["session"]["mode"] = "steam"
        self.configure({"state": running})
        code, out = self.run_ctl("start", "steam")
        self.assertEqual(4, code)
        self.assertEqual("SESSION_ACTIVE", out["error"]["code"])

    def test_wait_game_ignores_the_client(self):
        self.configure({"state": [state(focused=769), state(focused=752590, focusable=[752590])]})
        code, out = self.run_ctl("wait", "game", "--timeout", "5")
        self.assertEqual(0, code)
        self.assertEqual(752590, out["session"]["focus"]["focusedApp"])

    def test_a_failed_session_ends_a_wait(self):
        failed = state(phase="FAILED")
        failed["session"]["failure"] = {"code": "GUEST_EXIT", "message": "boom"}
        self.configure({"state": failed})
        code, out = self.run_ctl("wait", "focused", "1", "--timeout", "5")
        self.assertEqual(4, code)
        self.assertEqual("GUEST_EXIT", out["error"]["code"])

    def test_disabled_agent_commands_exit_7(self):
        self.configure({"guest": {"ok": False, "error": {"code": "AGENT_COMMANDS_DISABLED", "message": "off"}}})
        code, out = self.run_ctl("guest", "--", "true")
        self.assertEqual(7, code)
        self.assertEqual("AGENT_COMMANDS_DISABLED", out["error"]["code"])

    def test_run_checks_access_before_launching_an_activity(self):
        self.configure({"access": {"ok": True, "commands": False}})
        code, out = self.run_ctl("run", "/usr/bin/sh", "--", "-c", "true")
        self.assertEqual(7, code)
        self.assertEqual("AGENT_COMMANDS_DISABLED", out["error"]["code"])
        self.assertIn("Setup > Diagnostics > Debugging tools", out["error"]["message"])
        self.assertEqual(["access"], [c.get("method") for c in self.calls()])

    def test_override_checks_names_before_pushing_any_file(self):
        self.configure({"access": {"ok": True, "commands": True, "inbox": "/tmp/inbox"}})
        source = self.dir / "binary"
        source.write_bytes(b"test")
        code, out = self.run_ctl("override", "../outside", str(source))
        self.assertEqual(2, code)
        self.assertEqual("INVALID_NAME", out["error"]["code"])
        self.assertEqual(["access"], [c.get("method") for c in self.calls()])

    def test_guest_request_and_check(self):
        self.configure({"guest": {"ok": True, "exitCode": 3, "stdout": {"text": ""}, "stderr": {"text": "nope"}}})
        code, out = self.run_ctl("guest", "--timeout", "7", "--env", "A=1", "--check", "--", "xprop", "-root")
        self.assertEqual(4, code)
        self.assertEqual("GUEST_EXIT", out["error"]["code"])
        self.assertEqual({"argv": ["xprop", "-root"], "timeout": 7.0, "env": {"A": "1"}},
                         self.calls("guest")[0]["request"])

    def test_screenshot_resolves_the_logical_display(self):
        self.configure({}, dumpsys_display=DUMPSYS_DISPLAY)
        target = self.dir / "shot.png"
        code, out = self.run_ctl("screenshot", str(target), "--display", "4")
        self.assertEqual(0, code)
        self.assertTrue(target.read_bytes().startswith(b"\x89PNG"))
        shot = [c for c in self.calls() if c["args"][:2] == ["exec-out", "screencap"]][0]
        self.assertEqual(["exec-out", "screencap", "-p", "-d", "222"], shot["args"])

    def test_displays(self):
        self.configure({}, dumpsys_display=DUMPSYS_DISPLAY)
        code, out = self.run_ctl("displays")
        self.assertEqual(0, code)
        self.assertEqual([0, 4], [d["display"] for d in out["displays"]])
        self.assertEqual("222", out["displays"][1]["physicalId"])
        self.assertEqual([1080, 1240], out["displays"][1]["size"])

    def test_displays_named_with_their_unique_id(self):
        text = DUMPSYS_DISPLAY.replace("mPrimaryDisplayDevice=Screen-2",
                                       "mPrimaryDisplayDevice=Screen-2(local:222)")
        self.configure({}, dumpsys_display=text)
        code, out = self.run_ctl("screenshot", str(self.dir / "shot.png"), "--display", "4")
        self.assertEqual(0, code)
        shot = [c for c in self.calls() if c["args"][:2] == ["exec-out", "screencap"]][0]
        self.assertEqual(["exec-out", "screencap", "-p", "-d", "222"], shot["args"])

    def test_unknown_display_is_an_error(self):
        self.configure({}, dumpsys_display=DUMPSYS_DISPLAY)
        code, out = self.run_ctl("screenshot", str(self.dir / "x.png"), "--display", "9")
        self.assertEqual(2, code)
        self.assertEqual("NO_SUCH_DISPLAY", out["error"]["code"])

    def test_pacing_sums_split_records_of_the_game_layer_only(self):
        summary = ctl.pacing_summary(TIMESTATS_SPLIT, "com.droiddeck.launcher")
        self.assertEqual(["droiddeck_wayland_game"], summary["layers"])
        self.assertEqual(1800, summary["frames"])
        self.assertEqual([60, 120], summary["refreshRates"])
        self.assertEqual({"16": 1550, "25": 49, "33": 199}, summary["presentToPresentMs"])
        self.assertEqual(16, summary["cadenceMs"])
        self.assertEqual(248, summary["offCadence"])
        self.assertEqual(13.8, summary["offCadencePercent"])

    def test_pacing_reads_a_dump_with_windows_line_ends(self):
        summary = ctl.pacing_summary(TIMESTATS_SPLIT.replace("\n", "\r\n"), "com.droiddeck.launcher")
        self.assertEqual(ctl.pacing_summary(TIMESTATS_SPLIT, "com.droiddeck.launcher"), summary)
        self.assertEqual(1800, summary["frames"])

    def test_pacing_falls_back_to_the_sessions_surfaceview(self):
        summary = ctl.pacing_summary(TIMESTATS_SURFACEVIEW, "com.droiddeck.launcher")
        self.assertEqual(1, len(summary["layers"]))
        self.assertIn("SurfaceView[com.droiddeck.launcher/", summary["layers"][0])
        self.assertEqual(1800, summary["frames"])
        self.assertEqual(3, summary["offCadence"])

    def test_pacing_captures_then_turns_timestats_off(self):
        self.configure({}, timestats=TIMESTATS_SURFACEVIEW)
        with unittest.mock.patch.object(ctl.time, "sleep"):
            code, out = self.run_ctl("pacing", "--seconds", "1")
        self.assertEqual(0, code)
        self.assertEqual("pacing", out["command"])
        self.assertIn("fpsOnScreen", out)
        sf = [c["args"][3:] for c in self.calls() if c["args"][:3] == ["shell", "dumpsys", "SurfaceFlinger"]]
        self.assertEqual([["--timestats", "-clear", "-enable"], ["--timestats", "-dump"], ["--timestats", "-disable"]], sf)

    def test_pacing_without_a_session_on_screen_exits_4(self):
        self.configure({}, timestats="SurfaceFlinger TimeStats:\n" + timestats_record("StatusBar#1", 3, {16: 2}, 60))
        with unittest.mock.patch.object(ctl.time, "sleep"):
            code, out = self.run_ctl("pacing", "--seconds", "1")
        self.assertEqual(4, code)
        self.assertEqual("NO_FRAMES", out["error"]["code"])

    def test_pacing_counts_both_buckets_of_one_cadence_and_only_the_chosen_layers_rates(self):
        text = "SurfaceFlinger TimeStats:\n" + "".join([
            timestats_record("droiddeck_wayland_game#7", 910, {16: 450, 17: 450, 33: 10}, 60),
            timestats_record("StatusBar#106", 4, {8: 3}, 90),
        ])
        summary = ctl.pacing_summary(text, "com.droiddeck.launcher")
        self.assertEqual(10, summary["offCadence"])
        self.assertEqual([60], summary["refreshRates"])

    def test_pacing_skips_a_game_layer_with_no_frames_for_the_surfaceview(self):
        text = "SurfaceFlinger TimeStats:\n" + "".join([
            timestats_record("droiddeck_wayland_game#7", 0, {}, 120),
            timestats_record("a1 SurfaceView[com.droiddeck.launcher/com.droiddeck.launcher.SessionActivity]#8", 600, {16: 598, 33: 1}, 120),
        ])
        summary = ctl.pacing_summary(text, "com.droiddeck.launcher")
        self.assertEqual(600, summary["frames"])
        self.assertIn("SurfaceView[com.droiddeck.launcher/", summary["layers"][0])

    def test_pacing_with_a_layer_that_drew_no_frames_exits_4(self):
        self.configure({}, timestats="SurfaceFlinger TimeStats:\n" + timestats_record("droiddeck_wayland_game#7", 0, {}, 120))
        with unittest.mock.patch.object(ctl.time, "sleep"):
            code, out = self.run_ctl("pacing", "--seconds", "1")
        self.assertEqual(4, code)
        self.assertEqual("NO_FRAMES", out["error"]["code"])

    def test_provider_call_falls_back_to_a_broadcast_without_content(self):
        calls = []
        answers = iter([b"", b'{"ok": true, "command": "state"}'])

        def adb(_adb, _serial, *args, timeout=30):
            calls.append(args)
            if args[:2] == ("shell", "content"):
                return ctl.subprocess.CompletedProcess(args, 127, b"", b"/system/bin/sh: content: inaccessible or not found")
            if args[:2] == ("shell", "am"):
                return ctl.subprocess.CompletedProcess(args, 0, b"Broadcast completed: result=0", b"")
            return ctl.subprocess.CompletedProcess(args, 0, next(answers), b"")

        with unittest.mock.patch.object(ctl, "run_adb", adb), unittest.mock.patch.object(ctl.time, "sleep"):
            answer = ctl.provider_call("adb", "fake-1", "state", arg="a b", request={"x": 1})
        self.assertEqual({"ok": True, "command": "state"}, answer)
        broadcast = calls[1]
        self.assertEqual(("shell", "am", "broadcast", "-a", ctl.PACKAGE + ".AGENT_CALL", "-p", ctl.PACKAGE), broadcast[:7])
        self.assertIn("'a b'", broadcast)
        self.assertEqual(ctl.encode_request({"x": 1}), broadcast[broadcast.index("request") + 1])
        call_id = broadcast[broadcast.index("id") + 1]
        self.assertIn("agent-calls/%s.json" % call_id, calls[-1][1])

    def test_pacing_turns_timestats_off_when_the_dump_fails(self):
        calls = []

        def adb(_adb, _serial, *args, timeout=30):
            calls.append(args[3:])
            if "-dump" in args:
                raise ctl.ControlError("timed out", "ADB_FAILED")
            return ctl.subprocess.CompletedProcess(args, 0, b"", b"")

        with unittest.mock.patch.object(ctl, "run_adb", adb), unittest.mock.patch.object(ctl.time, "sleep"):
            with self.assertRaises(ctl.ControlError) as caught:
                ctl.pacing("adb", "fake-1", 1)
        self.assertEqual("PACING_FAILED", caught.exception.code)
        self.assertEqual(("--timestats", "-disable"), calls[-1])

    def test_pacing_fails_when_timestats_cannot_be_turned_off(self):
        def adb(_adb, _serial, *args, timeout=30):
            return ctl.subprocess.CompletedProcess(args, 1 if "-disable" in args else 0, b"", b"")

        with unittest.mock.patch.object(ctl, "run_adb", adb), unittest.mock.patch.object(ctl.time, "sleep"):
            with self.assertRaises(ctl.ControlError) as caught:
                ctl.pacing("adb", "fake-1", 1)
        self.assertEqual("PACING_FAILED", caught.exception.code)
        self.assertIn("timestats off", str(caught.exception))

    def test_pacing_maps_an_adb_failure_while_enabling_to_pacing_failed(self):
        def adb(*_args, **_kwargs):
            raise ctl.ControlError("adb vanished", "ADB_FAILED")

        with unittest.mock.patch.object(ctl, "run_adb", adb):
            with self.assertRaises(ctl.ControlError) as caught:
                ctl.pacing("adb", "fake-1", 1)
        self.assertEqual("PACING_FAILED", caught.exception.code)

    def test_input_requests(self):
        self.configure({"input": {"ok": True, "command": "input"}})
        self.run_ctl("input", "tap", "0.5", "0.25", "--space", "fraction")
        self.run_ctl("input", "key", "enter", "--mod", "CTRL")
        self.run_ctl("input", "pad", "a", "--hold", "50")
        requests = [c["request"] for c in self.calls("input")]
        self.assertEqual({"type": "tap", "x": 0.5, "y": 0.25, "space": "fraction", "holdMs": 60}, requests[0])
        self.assertEqual({"type": "key", "key": "enter", "action": "tap", "modifiers": ["CTRL"], "holdMs": 40}, requests[1])
        self.assertEqual({"type": "pad", "button": "a", "action": "tap", "holdMs": 50}, requests[2])

    def test_ui_click_by_tag_or_match(self):
        self.configure({"ui": {"ok": True, "command": "ui"}})
        self.run_ctl("ui", "click", "rail-steam")
        self.run_ctl("ui", "click", "--match", "Play", "--index", "1")
        requests = [c["request"] for c in self.calls("ui")]
        self.assertEqual({"op": "click", "index": 0, "tag": "rail-steam"}, requests[0])
        self.assertEqual({"op": "click", "index": 1, "match": "Play"}, requests[1])

    def test_cdp_targets_and_eval_requests(self):
        self.configure({"cdp": {"ok": True, "targets": []}})
        self.assertEqual(0, self.run_ctl("cdp", "targets")[0])
        self.assertEqual(0, self.run_ctl("cdp", "eval", "1+1", "--target", "Big Picture")[0])
        requests = [c["request"] for c in self.calls("cdp")]
        self.assertEqual({"op": "targets"}, requests[0])
        self.assertEqual({"op": "eval", "expression": "1+1", "awaitPromise": True, "timeout": 15.0,
                          "target": "Big Picture"}, requests[1])

    def test_a_bug_still_answers_json(self):
        self.configure({"state": {"ok": True, "session": "not an object"}})
        code, out = self.run_ctl("wait", "game", "--timeout", "1")
        self.assertEqual(2, code)
        self.assertEqual("INTERNAL_ERROR", out["error"]["code"])

    def test_wake(self):
        code, out = self.run_ctl("wake")
        self.assertEqual(0, code)
        self.assertEqual("Awake", out["wakefulness"])
        self.assertIn(["shell", "input", "keyevent", "KEYCODE_WAKEUP"], [c["args"] for c in self.calls()])

    def test_proot_profile_queues_only_the_next_session(self):
        self.configure({"env": {"ok": True}})
        code, out = self.run_ctl("proot-profile", "enable", "--slow-us", "250")
        self.assertEqual(0, code, out)
        self.assertEqual({"op": "set", "lines": ["PROOT_PROFILE=1", "PROOT_PROFILE_SLOW_US=250"], "persistent": False},
                         self.calls("env")[0]["request"])

    def test_proot_profile_report_needs_no_adb_or_device(self):
        path = self.dir / "session.log"
        path.write_text('ordinary log')
        with unittest.mock.patch.object(ctl, "adb_path", side_effect=AssertionError("must stay offline")):
            code, out = self.run_ctl("proot-profile", "report", str(path))
        self.assertEqual(0, code, out)
        self.assertFalse(out["hasCounters"])

    def test_override_pushes_to_the_inbox_first(self):
        self.configure({
            "access": {"ok": True, "commands": True, "inbox": "/sdcard/Android/data/x/files/agent-inbox"},
            "override": {"ok": True, "installed": "/opt/droiddeck-agent/bin/gamescope"},
        })
        binary = self.dir / "gamescope"
        binary.write_bytes(b"\x7fELF")
        code, out = self.run_ctl("override", "gamescope", str(binary))
        self.assertEqual(0, code)
        push = [c for c in self.calls() if c["args"][:1] == ["push"]][0]
        self.assertEqual("/sdcard/Android/data/x/files/agent-inbox/gamescope", push["args"][2])
        self.assertEqual({"name": "gamescope"}, self.calls("override")[0]["request"])

    def test_override_refused_before_pushing_when_commands_are_off(self):
        self.configure({"access": {"ok": True, "commands": False, "inbox": "/x"}})
        binary = self.dir / "gamescope"
        binary.write_bytes(b"x")
        code, out = self.run_ctl("override", "gamescope", str(binary))
        self.assertEqual(7, code)
        self.assertFalse([c for c in self.calls() if c["args"][:1] == ["push"]])

    def test_thermal_reads_battery_status_and_cpu_caps(self):
        self.configure({}, battery=[battery(386)], thermalservice=THERMALSERVICE, cpufreq=CPUFREQ)
        code, out = self.run_ctl("thermal")
        self.assertEqual(0, code, out)
        self.assertEqual(38.6, out["batteryC"])
        self.assertEqual([2, "MODERATE"], [out["status"], out["statusName"]])
        self.assertEqual({"policy0": {"maxKHz": 3532800, "hardwareMaxKHz": 3532800, "capped": False},
                          "policy6": {"maxKHz": 2918400, "hardwareMaxKHz": 4473600, "capped": True}}, out["cpu"])

    def test_thermal_without_readings_answers_nulls(self):
        code, out = self.run_ctl("thermal")
        self.assertEqual(0, code, out)
        self.assertEqual([None, None, None, {}], [out["batteryC"], out["status"], out["statusName"], out["cpu"]])

    def test_wait_cool_polls_until_the_battery_is_below(self):
        self.configure({}, battery=[battery(412), battery(395), battery(379)], thermalservice=THERMALSERVICE)
        with unittest.mock.patch.object(ctl, "wait_sleep") as slept:
            code, out = self.run_ctl("wait", "cool", "--below", "38", "--timeout", "600")
        self.assertEqual(0, code, out)
        self.assertEqual(["wait", 37.9], [out["command"], out["batteryC"]])
        self.assertEqual(2, slept.call_count)

    def test_wait_cool_times_out_with_exit_5(self):
        self.configure({}, battery=[battery(412)])
        code, out = self.run_ctl("wait", "cool", "--below", "38", "--timeout", "0")
        self.assertEqual(5, code)
        self.assertIn("41.2", out["error"]["message"])

    def test_wait_cool_needs_below_and_a_battery_temperature(self):
        code, out = self.run_ctl("wait", "cool")
        self.assertEqual(["INVALID_ARGUMENTS", 2], [out["error"]["code"], code])
        code, out = self.run_ctl("wait", "cool", "--below", "38")
        self.assertEqual("THERMAL_FAILED", out["error"]["code"])
    def test_logs_pull_the_exported_zip_instead_of_the_private_folder(self):
        self.configure({"state": ended_state(), "logs": {"ok": True, "sessionId": "s1", "zip": EXPORTED_ZIP,
                                                                  "cleanupDir": EXPORTED_DIR}},
                       files={EXPORTED_ZIP: share_zip({"s1/session.log": "booted", "s1/steam/console.log": "c",
                                                       "stores/epic.log": "e", "../outside": "x"})})
        destination = self.dir / "artifacts"
        code, out = self.run_ctl("logs", "latest", str(destination))
        self.assertEqual(0, code, out)
        self.assertEqual("booted", (destination / "session.log").read_text())
        self.assertEqual("c", (destination / "steam" / "console.log").read_text())
        self.assertEqual("e", (destination / "stores" / "epic.log").read_text())
        self.assertFalse((self.dir / "outside").exists())
        self.assertEqual([["pull", EXPORTED_ZIP]], [c["args"][:2] for c in self.calls() if c["args"][:1] == ["pull"]])
        self.assertIn(["shell", "rm", "-rf", EXPORTED_DIR], [c["args"] for c in self.calls()])

    def test_logs_of_a_session_that_started_meanwhile_are_refused(self):
        self.configure({"state": ended_state(), "logs": {"ok": True, "sessionId": "s2", "zip": EXPORTED_ZIP,
                                                                  "cleanupDir": EXPORTED_DIR}},
                       files={EXPORTED_ZIP: share_zip({"s2/session.log": "other"})})
        code, out = self.run_ctl("logs", "latest", str(self.dir / "artifacts"))
        self.assertEqual(6, code)
        self.assertEqual("LOG_PULL_FAILED", out["error"]["code"])
        self.assertFalse((self.dir / "artifacts").exists())
        self.assertIn(["shell", "rm", "-rf", EXPORTED_DIR], [c["args"] for c in self.calls()])

    def test_logs_from_a_legacy_provider_remove_only_the_export_zip(self):
        legacy_zip = AGENT_LOGS_DIR + "/DroidDeck-s1.zip"
        self.configure({"state": ended_state(),
                        "logs": {"ok": True, "sessionId": "s1", "zip": legacy_zip}},
                       files={legacy_zip: share_zip({"s1/session.log": "booted"})})
        code, out = self.run_ctl("logs", "latest", str(self.dir / "artifacts"))
        self.assertEqual(0, code, out)
        commands = [c["args"] for c in self.calls()]
        self.assertIn(["shell", "rm", "-f", legacy_zip], commands)
        self.assertNotIn(["shell", "rm", "-rf", AGENT_LOGS_DIR], commands)

    def test_logs_report_when_remote_export_cleanup_fails(self):
        self.configure({"state": ended_state(),
                        "logs": {"ok": True, "sessionId": "s1", "zip": EXPORTED_ZIP,
                                 "cleanupDir": EXPORTED_DIR}},
                       fail_cleanup=True, files={EXPORTED_ZIP: share_zip({"s1/session.log": "booted"})})
        code, out = self.run_ctl("logs", "latest", str(self.dir / "artifacts"))
        self.assertEqual(6, code, out)
        self.assertEqual("LOG_CLEANUP_FAILED", out["error"]["code"])
        self.assertIn("may remain in shared storage", out["error"]["message"])

    def test_logs_keep_pull_failure_primary_when_remote_cleanup_fails(self):
        self.configure({"state": ended_state(),
                        "logs": {"ok": True, "sessionId": "s1", "zip": EXPORTED_ZIP,
                                 "cleanupDir": EXPORTED_DIR}}, fail_cleanup=True)
        code, out = self.run_ctl("logs", "latest", str(self.dir / "artifacts"))
        self.assertEqual(6, code)
        self.assertEqual("LOG_PULL_FAILED", out["error"]["code"])
        self.assertIn("Permission denied", out["error"]["message"])
        self.assertIn(["shell", "rm", "-rf", EXPORTED_DIR], [call["args"] for call in self.calls()])

    def test_logs_replace_the_previous_snapshot_and_preserve_unrelated_files(self):
        destination = self.dir / "artifacts"
        destination.mkdir()
        (destination / "notes.txt").write_text("keep me")
        first = self.dir / "first.zip"
        with zipfile.ZipFile(first, "w") as archive:
            archive.writestr("s1/session.log", "first")
            archive.writestr("s1/steam/console.log", "old")
        ctl.unpack_logs(first, destination, "s1")
        self.assertEqual("old", (destination / "steam/console.log").read_text())

        second = self.dir / "second.zip"
        with zipfile.ZipFile(second, "w") as archive:
            archive.writestr("s1/session.log", "second")
        ctl.unpack_logs(second, destination, "s1")
        self.assertEqual("second", (destination / "session.log").read_text())
        self.assertFalse((destination / "steam/console.log").exists())
        self.assertEqual("keep me", (destination / "notes.txt").read_text())

    def test_logs_map_destination_creation_failure_to_pull_error(self):
        destination = (self.dir / "artifacts").resolve()
        archive = self.dir / "logs.zip"
        real_mkdir = Path.mkdir

        def fail_destination(path, *args, **kwargs):
            if path == destination:
                raise PermissionError("injected destination failure")
            return real_mkdir(path, *args, **kwargs)

        with unittest.mock.patch.object(Path, "mkdir", new=fail_destination):
            with self.assertRaises(ctl.ControlError) as raised:
                ctl.unpack_logs(archive, destination, "s1")

        self.assertEqual("LOG_PULL_FAILED", raised.exception.code)
        self.assertIn("injected destination failure", str(raised.exception))

    def test_logs_do_not_follow_a_stale_managed_symlink(self):
        if os.name == "nt":
            self.skipTest("symlink creation is unavailable on this Windows host")
        destination = self.dir / "artifacts"
        destination.mkdir()
        victim = destination / "notes.txt"
        victim.write_text("keep me")
        (destination / ".droiddeckctl-managed-logs.json").write_text(json.dumps(["old.log"]))
        (destination / "old.log").symlink_to(victim)
        archive = self.dir / "logs.zip"
        with zipfile.ZipFile(archive, "w") as z:
            z.writestr("s1/session.log", "current")

        ctl.unpack_logs(archive, destination, "s1")

        self.assertEqual("keep me", victim.read_text())
        self.assertTrue((destination / "old.log").is_symlink())
        self.assertEqual("current", (destination / "session.log").read_text())

    def test_logs_roll_back_a_failed_manifest_commit_and_can_retry(self):
        destination = (self.dir / "artifacts").resolve()
        destination.mkdir()
        (destination / "notes.txt").write_text("keep me")
        first = self.dir / "first.zip"
        with zipfile.ZipFile(first, "w") as z:
            z.writestr("s1/session.log", "first")
            z.writestr("s1/steam/console.log", "old")
        ctl.unpack_logs(first, destination, "s1")
        old_manifest = (destination / ".droiddeckctl-managed-logs.json").read_bytes()
        second = self.dir / "second.zip"
        with zipfile.ZipFile(second, "w") as z:
            z.writestr("s1/session.log", "second")
            z.writestr("s1/new.log", "new")

        real_replace = ctl.os.replace
        failed = False
        def fail_manifest_once(source, target):
            nonlocal failed
            if Path(target) == destination / ".droiddeckctl-managed-logs.json" and not failed:
                failed = True
                raise OSError("injected manifest failure")
            return real_replace(source, target)

        with unittest.mock.patch.object(ctl.os, "replace", side_effect=fail_manifest_once):
            with self.assertRaises(ctl.ControlError):
                ctl.unpack_logs(second, destination, "s1")

        self.assertEqual("first", (destination / "session.log").read_text())
        self.assertEqual("old", (destination / "steam/console.log").read_text())
        self.assertFalse((destination / "new.log").exists())
        self.assertEqual(old_manifest, (destination / ".droiddeckctl-managed-logs.json").read_bytes())
        ctl.unpack_logs(second, destination, "s1")
        self.assertEqual("second", (destination / "session.log").read_text())
        self.assertEqual("new", (destination / "new.log").read_text())
        self.assertFalse((destination / "steam/console.log").exists())
        self.assertEqual("keep me", (destination / "notes.txt").read_text())

    def test_logs_preserve_the_old_manifest_when_its_backup_fails(self):
        destination = (self.dir / "artifacts").resolve()
        destination.mkdir()
        archive = self.dir / "logs.zip"
        with zipfile.ZipFile(archive, "w") as z:
            z.writestr("s1/session.log", "current")
        manifest = destination / ".droiddeckctl-managed-logs.json"
        manifest.write_text(json.dumps(["old.log"]))
        original = manifest.read_bytes()
        real_replace = ctl.os.replace

        def fail_manifest_backup(source, target):
            if Path(source) == manifest:
                raise OSError("injected manifest backup failure")
            return real_replace(source, target)

        with unittest.mock.patch.object(ctl.os, "replace", side_effect=fail_manifest_backup):
            with self.assertRaises(ctl.ControlError):
                ctl.unpack_logs(archive, destination, "s1")

        self.assertEqual(original, manifest.read_bytes())

    def test_logs_from_an_older_build_fall_back_to_the_private_folder(self):
        self.configure({"state": ended_state()})
        code, out = self.run_ctl("logs", "latest", str(self.dir / "artifacts"))
        self.assertEqual(6, code)
        self.assertEqual("LOG_PULL_FAILED", out["error"]["code"])
        self.assertIn("Permission denied", out["error"]["message"])
        self.assertEqual([["pull", PRIVATE_LOG_DIR]], [c["args"][:2] for c in self.calls() if c["args"][:1] == ["pull"]])

    def test_logs_without_a_session_folder_exit_6(self):
        self.configure({"state": ended_state(),
                        "logs": {"ok": False, "error": {"code": "NO_SESSION_LOGS", "message": "none"}}})
        code, out = self.run_ctl("logs", "latest", str(self.dir / "artifacts"))
        self.assertEqual(6, code)
        self.assertEqual("NO_SESSION_LOGS", out["error"]["code"])


class ScenarioTest(FakeAdbTest):
    def write(self, body):
        path = self.dir / "scenario.json"
        path.write_text(json.dumps(body))
        return path

    def run_scenario(self, path, *extra):
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()):
            code = scenario.main([str(path), "--adb", str(self.adb), "--serial", "fake-1",
                                  "--out", str(self.dir / "runs"), *extra])
        return code, json.loads(out.getvalue())

    def test_variables_asserts_and_repeats(self):
        self.configure({"state": state(focused=489830, focusable=[489830])})
        path = self.write({
            "name": "t", "vars": {"appId": 1}, "repeat": 2,
            "steps": [["state"], {"assert": "session.focus.focusedApp", "equals": "${appId}"}],
        })
        code, report = self.run_scenario(path, "--var", "appId=489830")
        self.assertEqual(0, code)
        self.assertEqual(2, report["passed"])
        output = Path(report["out"])
        self.assertEqual(0o700, output.stat().st_mode & 0o777)
        for artifact in output.rglob("*.json"):
            self.assertEqual(0o600, artifact.stat().st_mode & 0o777, str(artifact))
        for run in report["runs"]:
            self.assertEqual(0o700, Path(run["folder"]).stat().st_mode & 0o777)

    def test_a_failed_assert_fails_the_run_and_collects_evidence(self):
        self.configure({"state": state(focused=769, focusable=[769]), "focus": {"ok": True, "focusedApp": 769}},
                       dumpsys_display=DUMPSYS_DISPLAY)
        path = self.write({"name": "t", "repeat": 3,
                           "steps": [["state"], {"assert": "session.focus.focusedApp", "equals": 489830}]})
        code, report = self.run_scenario(path)
        self.assertEqual(1, code)
        self.assertEqual(1, len(report["runs"]))
        self.assertIn("is 769", report["runs"][0]["error"])
        evidence = json.loads((Path(report["runs"][0]["folder"]) / "evidence.json").read_text())
        self.assertEqual(769, evidence["focus"]["focusedApp"])

    def test_a_failed_run_keeps_the_session_logs(self):
        failing = ended_state()
        failing["session"]["focus"] = {"focusedApp": 769}
        self.configure({"state": failing, "focus": {"ok": True, "focusedApp": 769},
                        "logs": {"ok": True, "sessionId": "s1", "zip": EXPORTED_ZIP}},
                       dumpsys_display=DUMPSYS_DISPLAY, files={EXPORTED_ZIP: share_zip({"s1/session.log": "booted"})})
        path = self.write({"name": "t", "steps": [["state"], {"assert": "session.focus.focusedApp", "equals": 7}]})
        code, report = self.run_scenario(path)
        self.assertEqual(1, code)
        folder = Path(report["runs"][0]["folder"])
        self.assertTrue(json.loads((folder / "evidence.json").read_text())["logs"]["ok"])
        self.assertEqual("booted", (folder / "session" / "session.log").read_text())

    def test_after_steps_run_after_a_failure(self):
        self.configure({"state": state(focused=769), "quit": {"ok": True, "remaining": []}})
        path = self.write({"name": "t", "repeat": 2, "vars": {"appId": 7},
                           "steps": [["state"], {"assert": "session.focus.focusedApp", "equals": 7}],
                           "after": [["quit", "${appId}"]]})
        code, report = self.run_scenario(path, "--keep-going")
        self.assertEqual(1, code)
        self.assertEqual(2, len(report["runs"]))
        self.assertEqual([{"appId": 7, "timeout": 15.0}] * 2, [c["request"] for c in self.calls("quit")])


if __name__ == "__main__":
    unittest.main()
