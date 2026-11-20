#!/usr/bin/env python3
"""A/B the session's keep-top guard on a rooted dual-screen device.

See docs/development/memory-guard-ab.md. Needs `su` on the device and a running DroidDeck build
with debugging tools (tools/droiddeckctl).
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shlex
import subprocess
import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from adb_device import DeviceResolutionError, resolve_serial  # noqa: E402

TOOLS = Path(__file__).resolve().parent
PACKAGE = "com.droiddeck.launcher"
SESSION = f"{PACKAGE}/.SessionActivity"
HOG_PATH = "/data/local/tmp/dd-memory-hog.sh"
HOG_MOUNT = "/dev/dd-memory-hog"

# Incompressible data in a private tmpfs: zram cannot shrink it and no app owns it, so lmkd keeps
# killing by oom_score_adj while it is held.
HOG = f"""#!/system/bin/sh
stop_mb=$1; hold=$2
mkdir -p {HOG_MOUNT}
grep -q ' {HOG_MOUNT} ' /proc/mounts || mount -t tmpfs -o size=16g tmpfs {HOG_MOUNT}
i=0
while [ $i -lt 512 ]; do
  avail=$(awk '/MemAvailable/ {{print int($2/1024)}}' /proc/meminfo)
  echo "avail=$avail"
  [ "$avail" -le "$stop_mb" ] && break
  head -c 33554432 /dev/urandom > {HOG_MOUNT}/f$i || break
  i=$((i+1))
done
sleep "$hold"
rm -f {HOG_MOUNT}/f*
umount {HOG_MOUNT}
echo released
"""

def focused_display(input_dump: str) -> int | None:
    match = re.search(r"FocusedDisplayId: (-?\d+)", input_dump)
    return int(match.group(1)) if match else None


def activity_count(activities_dump: str, activity: str) -> int:
    return len(re.findall(r"Hist\s+#\d+: ActivityRecord\{\S+ u\d+ " + re.escape(activity) + r" ", activities_dump))


def guard_count(activities_dump: str) -> int:
    return activity_count(activities_dump, f"{PACKAGE}/.session.KeepTopActivity")


def session_count(activities_dump: str) -> int:
    return activity_count(activities_dump, SESSION)


def panel_windows(windows_dump: str, display: int) -> int:
    count, current = 0, None
    for line in windows_dump.splitlines():
        header = re.match(r"\s*Window #\d+ Window\{\S+ u\d+ (.+?)\}:", line)
        if header:
            current = header.group(1)
            continue
        shown = re.search(r"mDisplayId=(\d+)", line)
        if shown and current is not None:
            if current == PACKAGE and int(shown.group(1)) == display:
                count += 1
            current = None
    return count


def bottom_home(activities_dump: str, session_display: int) -> tuple[int, str] | None:
    display = None
    for line in activities_dump.splitlines():
        section = re.match(r"Display #(\d+) \(activities", line)
        if section:
            display = int(section.group(1))
            continue
        if line.strip() == "" or display is None or display == session_display:
            continue
        home = re.search(r"type=home A=\d+:(\S+) .*visible=true", line)
        if home:
            return display, home.group(1)
    return None


def logical_size(displays_dump: str, display: int) -> tuple[int, int] | None:
    current = None
    for line in displays_dump.splitlines():
        header = re.search(r"Display: mDisplayId=(\d+)", line)
        if header:
            current = int(header.group(1))
            continue
        size = re.search(r"\bcur=(\d+)x(\d+)", line)
        if size and current == display:
            return int(size.group(1)), int(size.group(2))
    return None


def max_adj(samples: list[str]) -> int | None:
    values = [int(sample) for sample in samples if re.fullmatch(r"-?\d+", sample)]
    return max(values) if values else None


def summarize_events(events: str) -> dict:
    kills = [tuple(int(field) for field in match.groups())
             for match in re.finditer(r"killinfo: \[(\d+),(\d+),(-?\d+),(-?\d+),", events)]
    died = [(match.group(1), int(match.group(2)))
            for match in re.finditer(r"am_proc_died: \[\d+,\d+,([^,\]]+),(-?\d+),", events)]
    session_died = [adj for name, adj in died if name == PACKAGE]
    return {
        "lmkd_kills": len(kills),
        "lmkd_kills_down_to_adj0": sum(1 for kill in kills if kill[3] <= 0),
        "processes_died": {name: sum(1 for other, _ in died if other == name) for name in sorted({n for n, _ in died})},
        "droiddeck_died_at_adj": session_died[0] if session_died else None,
        "session_top_lost": len(re.findall(r"wm_on_top_resumed_lost_called: \[\d+,com\.droiddeck\.launcher\.SessionActivity,", events)),
        "task_to_front": len(re.findall(r"wm_task_to_front:", events)),
        "guard_creates": len(re.findall(r"wm_create_activity: .*KeepTopActivity", events)),
    }


class Device:
    def __init__(self, adb: str, serial: str):
        self.adb, self.serial = adb, serial

    def run(self, *args: str, timeout: int = 120, check: bool = False) -> str:
        result = subprocess.run([self.adb, "-s", self.serial, *args], capture_output=True, text=True, timeout=timeout)
        if check and result.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)} failed: {result.stderr.strip()}")
        return result.stdout.replace("\r", "")

    def sh(self, command: str, timeout: int = 120) -> str:
        return self.run("shell", command, timeout=timeout)

    def su(self, command: str, timeout: int = 120) -> str:
        return self.sh(f"su -c {shlex.quote(command)}", timeout=timeout)

    def ctl(self, *args: str, timeout: int = 300) -> str:
        return subprocess.run([str(TOOLS / "droiddeckctl"), "--adb", self.adb, "--serial", self.serial, *args],
                              capture_output=True, text=True, timeout=timeout).stdout

    def phase(self) -> str | None:
        match = re.search(r'"phase":\s*"(\w+)"', self.ctl("state"))
        return match.group(1) if match else None

    def pid(self) -> str:
        return self.sh(f"pidof {PACKAGE}").strip()

    def size(self, display: int) -> tuple[int, int]:
        size = logical_size(self.sh("dumpsys window displays"), display)
        if not size:
            raise RuntimeError(f"no size for display {display}")
        return size


class Bench:
    def __init__(self, device: Device, out: Path, label: str, session_display: int, bottom_tap: tuple[float, float],
                 panel_close: tuple[float, float]):
        self.d, self.out, self.label, self.session_display = device, out, label, session_display
        self.bottom_tap, self.panel_close = bottom_tap, panel_close
        self.out.mkdir(parents=True, exist_ok=True)

    def bottom(self) -> tuple[int, str]:
        found = bottom_home(self.d.sh("dumpsys activity activities"), self.session_display)
        if not found:
            raise RuntimeError("no visible home activity on another display")
        return found

    def tap(self, display: int, fraction: tuple[float, float]):
        width, height = self.d.size(display)
        self.d.sh(f"input -d {display} tap {int(width * fraction[0])} {int(height * fraction[1])}")

    def tap_bottom(self):
        self.tap(self.bottom()[0], self.bottom_tap)

    def game_front(self):
        self.d.su(f"am start --display {self.session_display} -n {SESSION} -f 0x20000000")
        time.sleep(3)

    def close_panel(self):
        display = self.bottom()[0]
        for _ in range(3):
            if panel_windows(self.d.sh("dumpsys window windows"), display) == 0:
                return
            self.tap(display, self.panel_close)
            time.sleep(3)

    def prepare(self, apk: str | None):
        self.d.sh("input keyevent KEYCODE_WAKEUP")
        self.d.ctl("stop")
        if apk:
            env = dict(os.environ, ADB_SERIAL=self.d.serial)
            subprocess.run([str(TOOLS / "deploy_local.sh"), apk], env=env, check=True, capture_output=True)
        self.d.sh(f"monkey -p {PACKAGE} -c android.intent.category.LAUNCHER 1")
        time.sleep(6)
        for _ in range(2):
            self.d.ctl("start", "steam", "--wait", "--timeout", "240")
            if self.d.phase() == "READY":
                break
            time.sleep(5)
        time.sleep(15)
        self.game_front()

    def snapshot(self) -> dict:
        activities = self.d.sh("dumpsys activity activities")
        display = self.bottom()[0]
        pid = self.d.pid()
        return {
            "focused_display": focused_display(self.d.sh("dumpsys input")),
            "guards": guard_count(activities),
            "sessions": session_count(activities),
            "panel": panel_windows(self.d.sh("dumpsys window windows"), display),
            "adj": int(self.d.su(f"cat /proc/{pid}/oom_score_adj").strip() or -9999),
            "pid": pid,
        }

    def write(self, kind: str, record: dict):
        record = {"label": self.label, "kind": kind, **record}
        with (self.out / f"{self.label}.jsonl").open("a") as results:
            results.write(json.dumps(record) + "\n")
        print(json.dumps(record))

    def probe(self, state: str):
        self.d.run("logcat", "-b", "events", "-c")
        time.sleep(3)
        idle_creates = summarize_events(self.d.run("logcat", "-b", "events", "-d"))["guard_creates"]
        before = self.snapshot()
        _, home = self.bottom()
        self.d.run("logcat", "-b", "events", "-c")
        samples = self.d.su(f"kill -9 $(pidof {home}); for i in $(seq 50); do cat /proc/{before['pid']}/oom_score_adj"
                            " 2>/dev/null || echo DEAD; sleep 0.1; done").split()
        events = self.d.run("logcat", "-b", "events", "-d")
        (self.out / f"{self.label}-{state}-events.txt").write_text(events)
        summary = summarize_events(events)
        self.write("state", {
            "state": state, "bottom_home": home, **{k: v for k, v in before.items() if k != "pid"},
            "guard_creates_idle_3s": idle_creates, "adj_max_after_home_kill": max_adj(samples),
            "session_top_lost": summary["session_top_lost"], "droiddeck_alive": self.d.pid() == before["pid"],
            "phase": self.d.phase(),
        })
        time.sleep(4)

    def states(self):
        self.probe("panel_open")
        self.game_front()
        self.close_panel()
        self.probe("panel_closed_untouched")
        self.game_front()
        self.tap_bottom()
        time.sleep(1)
        self.probe("one_bottom_tap_just_now")
        self.game_front()
        self.tap_bottom()
        time.sleep(8)
        self.probe("one_bottom_tap_then_stayed")
        self.game_front()
        self.tap_bottom()
        time.sleep(1)
        self.tap_bottom()
        time.sleep(1)
        self.tap(self.session_display, (0.36, 0.11))
        time.sleep(3)
        self.probe("used_bottom_then_tapped_game")
        self.game_front()
        self.d.sh("input keyevent KEYCODE_HOME")
        time.sleep(4)
        self.probe("home_pip")
        self.game_front()
        self.tap_bottom()
        time.sleep(1)
        self.tap_bottom()
        time.sleep(2)
        self.probe("using_bottom_now")
        self.game_front()

    def pressure(self, run: str, stop_mb: int, hold: int):
        with tempfile.NamedTemporaryFile("w", suffix=".sh", delete=False) as script:
            script.write(HOG)
        try:
            self.d.run("push", script.name, HOG_PATH, check=True)
        finally:
            os.unlink(script.name)
        self.d.sh(f"chmod 755 {HOG_PATH}")
        self.game_front()
        self.close_panel()
        before = self.snapshot()
        self.d.run("logcat", "-b", "events", "-c")
        hog = subprocess.Popen([self.d.adb, "-s", self.d.serial, "shell", f"su -c '{HOG_PATH} {stop_mb} {hold}'"],
                               stdout=subprocess.PIPE, text=True)
        samples = []
        while hog.poll() is None:
            samples.append(self.d.su(f"cat /proc/{before['pid']}/oom_score_adj 2>/dev/null || echo DEAD").strip())
            time.sleep(0.5)
        output = hog.stdout.read() if hog.stdout else ""
        time.sleep(3)
        events = self.d.run("logcat", "-b", "events", "-d")
        (self.out / f"{self.label}-{run}-events.txt").write_text(events)
        available = [int(value) for value in re.findall(r"avail=(\d+)", output)]
        self.write("pressure", {
            "run": run, "stop_mb": stop_mb, "hold_s": hold, **{k: v for k, v in before.items() if k != "pid"},
            "min_available_mb": min(available) if available else None, "adj_max": max_adj(samples),
            "droiddeck_alive": self.d.pid() == before["pid"], "phase": self.d.phase(), **summarize_events(events),
        })


def fraction_pair(text: str) -> tuple[float, float]:
    x, y = (float(part) for part in text.split(","))
    return x, y


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--adb", default=os.environ.get("ADB", "adb"))
    parser.add_argument("--serial")
    parser.add_argument("--out", type=Path, default=Path.home() / ".cache" / "droiddeck-memory-guard-ab")
    parser.add_argument("--label", required=True, help="build name for the results, e.g. main or fix")
    parser.add_argument("--apk", help="install this APK and start a fresh Steam session first")
    parser.add_argument("--session-display", type=int, default=0)
    parser.add_argument("--bottom-tap", type=fraction_pair, default=(0.78, 0.47),
                        help="x,y fractions of an inert spot on the bottom home (default 0.78,0.47)")
    parser.add_argument("--panel-close", type=fraction_pair, default=(0.053, 0.071),
                        help="x,y fractions of the dual-screen panel's close button")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("states", help="kill the bottom home like lmkd in each session state")
    pressure = commands.add_parser("pressure", help="hold real memory pressure and record lmkd kills")
    pressure.add_argument("--stop-mb", type=int, default=650, help="stop filling at this MemAvailable")
    pressure.add_argument("--hold", type=int, default=30)
    pressure.add_argument("--runs", type=int, default=1)
    summarize = commands.add_parser("summarize", help="summarize a saved events log")
    summarize.add_argument("events", type=Path)
    args = parser.parse_args()

    if args.command == "summarize":
        print(json.dumps(summarize_events(args.events.read_text()), indent=2))
        return 0
    try:
        device = Device(args.adb, resolve_serial(args.adb, args.serial))
    except DeviceResolutionError as exc:
        print(f"memory-guard-ab: {exc}", file=sys.stderr)
        return 2
    if device.su("id -u").strip() != "0":
        print("memory-guard-ab: needs a rooted device (su)", file=sys.stderr)
        return 2
    bench = Bench(device, args.out, args.label, args.session_display, args.bottom_tap, args.panel_close)
    if args.command == "states":
        bench.prepare(args.apk)
        bench.states()
    else:
        for run in range(args.runs):
            bench.prepare(args.apk if run == 0 else None)
            bench.pressure(f"stop{args.stop_mb}-{run + 1}", args.stop_mb, args.hold)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
