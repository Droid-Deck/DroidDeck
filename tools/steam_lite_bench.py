#!/usr/bin/env python3
"""Measure the Steam web helper on a device (docs/development/steam-lite-runtime.md).

    tools/steam_lite_bench.py info --out runs/duo
    tools/steam_lite_bench.py steady --app 584400 --arms stock,softcomp,stock,softcomp --out runs/duo/steady
    tools/steam_lite_bench.py cycle --app 584400 --arms stock,softcomp --repeat 3 --out runs/duo
    tools/steam_lite_bench.py ablate --actions gc,moderate,pageout --label gc --out runs/duo
    tools/steam_lite_bench.py report runs/duo

Needs a DroidDeck build with debugging tools on and tools/droiddeckctl. An arm is a set of
persistent session variables (ARMS); every run starts a fresh session with them. Samples come from
droiddeck-steam-lite inside the guest: the client's process tree (smaps_rollup PSS and SwapPss, CPU
ticks), KGSL GPU memory, MemAvailable and PSI. `ablate` applies single DevTools or zRAM actions to
a running session and samples around each one.
"""

from __future__ import annotations

import argparse
import base64
import csv
import gzip
import json
import os
import random
import statistics
import subprocess
import sys
import threading
import time
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
CTL = TOOLS / "droiddeckctl"
ARMS = {
    "stock": {"DD_STEAM_SOFT_COMPOSITING": "0", "DD_STEAM_CEF_GPU": "1"},
    "softcomp": {"DD_STEAM_SOFT_COMPOSITING": "1", "DD_STEAM_CEF_GPU": "1"},
    "cef-software": {"DD_STEAM_SOFT_COMPOSITING": "0", "DD_STEAM_CEF_GPU": "0"},
}
WEB = ("browser", "renderer", "gpu-process", "zygote")
# Not part of the client: the measuring tool ("sampler"), and the supervisor ("steam-lite") in the
# recordings made while it existed.
NOT_CLIENT = ("steam-lite", "sampler")
REMOTE = "/root/.cache/steam-lite-bench"
LITE = os.environ.get("DD_STEAM_LITE_BIN", "droiddeck-steam-lite")


class Device:
    def __init__(self, serial: str | None):
        self.serial = serial

    def ctl(self, *args: str, timeout: float = 120) -> dict:
        cmd = [sys.executable, str(CTL)] + (["--serial", self.serial] if self.serial else []) + list(args)
        done = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout + 60)
        try:
            body = json.loads(done.stdout or "{}")
        except ValueError:
            body = {"ok": False, "raw": done.stdout[-2000:]}
        body.setdefault("exit", done.returncode)
        if done.returncode and "error" not in body:
            body["error"] = done.stderr[-2000:]
        return body

    def guest(self, *argv: str, timeout: float = 60) -> dict:
        return self.ctl("guest", "--timeout", str(timeout), "--", *argv, timeout=timeout)

    def sh(self, script: str, *args: str, timeout: float = 60) -> str:
        reply = self.guest("sh", "-c", script, "sh", *args, timeout=timeout)
        return (reply.get("stdout") or {}).get("text", "")

    def adb(self, *args: str, timeout: float = 30) -> str:
        cmd = ["adb"] + (["-s", self.serial] if self.serial else []) + list(args)
        return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout).stdout

    def mark(self, text: str) -> None:
        self.sh('printf %s "$1" > ' + REMOTE + "/mark", text)

    def fetch(self, path: str) -> str:
        text = self.sh("gzip -c \"$1\" | base64 -w0", path, timeout=120)
        return gzip.decompress(base64.b64decode(text)).decode() if text else ""


def info(dev: Device, out: Path) -> dict:
    props = {k: dev.adb("shell", "getprop", k).strip() for k in (
        "ro.product.model", "ro.product.manufacturer", "ro.soc.model", "ro.build.version.release",
        "ro.build.fingerprint")}
    mem = dev.adb("shell", "grep -E 'MemTotal|SwapTotal' /proc/meminfo")
    package = dev.adb("shell", "dumpsys package com.droiddeck.launcher | grep -m2 -E 'versionName|versionCode'")
    probe = dev.sh(LITE + " probe", timeout=60)
    zram = dev.sh("cat /proc/swaps; ls /sys/block | grep zram; cat /sys/block/zram0/mm_stat 2>&1 | head -1;"
                  " cat /proc/pressure/memory 2>&1 | head -2; ls /sys/class/kgsl/kgsl/proc 2>&1 | head -3")
    env = dev.sh("env | grep -E '^(DD_STEAM_CEF_GPU|DD_ZRAM|DD_WEBHELPER_ARGS|DD_STEAM_CLIENT_ARCH|DD_VKBRIDGE|"
                 "DD_STEAM_SOFT_COMPOSITING|DD_OPEN_DRIVER)=' | sort")
    body = {"props": props, "meminfo": mem, "package": package, "env": env, "counters": zram}
    try:
        body["probe"] = json.loads(probe)
    except ValueError:
        body["probe"] = probe
    out.mkdir(parents=True, exist_ok=True)
    (out / "device.json").write_text(json.dumps(body, indent=1))
    return body


ABLATE = r"""
import json, subprocess, sys, time
lite, out, actions, waits = sys.argv[1], sys.argv[2], sys.argv[3].split(","), [float(w) for w in sys.argv[4].split(",")]
def run(*args):
    return subprocess.run([lite] + list(args), capture_output=True, text=True).stdout.strip()
with open(out, "w") as stream:
    for action in actions:
        print(json.dumps({"label": "before " + action}), file=stream)
        print(run("sample"), file=stream)
        start = time.monotonic()
        result = json.loads(run("apply", action) or "{}")
        print(json.dumps({"label": "apply " + action, "result": result,
                          "ms": round((time.monotonic() - start) * 1000)}), file=stream)
        for wait in waits:
            time.sleep(max(0.0, start + wait - time.monotonic()))
            print(json.dumps({"label": "after %s +%gs" % (action, wait)}), file=stream)
            print(run("sample"), file=stream)
        stream.flush()
"""


def ablate(dev: Device, actions: list[str], waits: list[float], out: Path, label: str) -> list[dict]:
    """One action at a time: a sample before it, then one after each wait (seconds since the action)."""
    total = len(actions) * (max(waits) + 20) + 30
    remote = "%s/ablate-%s-%d.jsonl" % (REMOTE, label, int(time.time()))
    dev.sh("mkdir -p " + REMOTE)
    reply = dev.ctl("guest", "--timeout", str(min(600, total)), "--stdin", ABLATE, "--", "python3", "-", LITE,
                    remote, ",".join(actions), ",".join(str(w) for w in waits), timeout=min(600, total))
    if reply.get("exitCode") != 0:
        raise RuntimeError("the ablation failed in the guest: %s" % json.dumps(reply)[:400])
    lines = [json.loads(x) for x in dev.fetch(remote).splitlines() if x.strip()]
    out.mkdir(parents=True, exist_ok=True)
    with open(out / (label + ".jsonl"), "w") as stream:
        for item in lines:
            print(json.dumps(item), file=stream)
    rows, label_now, previous = [], None, None
    for item in lines:
        if "result" in item:
            rows.append({"label": item["label"], "ms": item.get("ms"), "result": item["result"]})
        elif "label" in item and "processes" not in item:
            label_now = item["label"]
        elif "processes" in item:
            by = {}
            for r in item["processes"]:
                if r["role"] not in NOT_CLIENT:
                    by[r["role"]] = by.get(r["role"], 0) + (r.get("Pss") or 0)
            rows.append({"label": label_now, "total_mib": round(sum(by.values()) / 1024, 1),
                         **{k: round(v / 1024, 1) for k, v in sorted(by.items())},
                         "swappss_mib": round(sum(r.get("SwapPss") or 0 for r in item["processes"]) / 1024, 1),
                         "gpu_mib": round(sum((item.get("gpu") or {}).values()) / 1048576, 1),
                         "avail_mib": round((item["memory"].get("MemAvailable") or 0) / 1024, 1),
                         "web_cpu_pct_since_last": round(cpu(previous, item, WEB) or 0, 1) if previous else None})
            previous = item
    return rows


def wait_focus(dev: Device, want, timeout: float) -> float | None:
    start = time.monotonic()
    while time.monotonic() - start < timeout:
        focus = dev.ctl("focus", timeout=40)
        if want(focus.get("focusedApp")):
            return time.monotonic() - start
        time.sleep(0.5)
    return None


def running(dev: Device, tries: int = 3) -> bool | None:
    """Whether a session runs; None when the device could not be asked."""
    for attempt in range(tries):
        state = dev.ctl("state", timeout=40)
        if state.get("ok") and isinstance(state.get("session"), dict):
            return bool(state["session"].get("running"))
        time.sleep(1 + attempt)
    return None


def ensure_steam(dev: Device, fresh: bool = False) -> None:
    if fresh and running(dev) is not False:
        dev.ctl("stop", "--timeout", "90", timeout=100)
        if running(dev) is not False:
            raise RuntimeError("the old session did not stop")
    if running(dev) is not True:
        dev.adb("shell", "monkey -p com.droiddeck.launcher -c android.intent.category.LAUNCHER 1")
        time.sleep(6)
        dev.ctl("wake", timeout=30)
        started = dev.ctl("start", "steam", "--wait", "--timeout", "300", timeout=320)
        if not started.get("ok"):
            raise RuntimeError("Steam did not start: %s" % json.dumps(started)[:400])
    time.sleep(5)


def last_exit(dev: Device) -> dict:
    text = dev.adb("shell", "dumpsys activity exit-info com.droiddeck.launcher")
    entry = {}
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("timestamp=") and entry:
            break
        for part in line.split(" "):
            key, sep, value = part.partition("=")
            if sep and key in ("timestamp", "reason", "pss", "rss", "importance"):
                entry.setdefault(key, value)
        if line.startswith("timestamp="):
            entry["timestamp"] = line.split("=", 1)[1].split(" pid=")[0]
    return entry


def recorder(dev: Device, seconds: float, interval: float, name: str) -> subprocess.Popen:
    return subprocess.Popen(
        [sys.executable, str(CTL)] + (["--serial", dev.serial] if dev.serial else []) +
        ["guest", "--timeout", str(seconds + 30), "--", LITE, "record", str(seconds),
         str(interval), REMOTE + "/" + name, "--mark", REMOTE + "/mark"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def system_available(dev: Device) -> int | None:
    text = dev.sh("grep MemAvailable /proc/meminfo")
    parts = text.split()
    return int(parts[1]) // 1024 if len(parts) >= 2 and parts[1].isdigit() else None


def set_arm(dev: Device, arm: str) -> dict:
    for key, value in ARMS[arm].items():
        set_env(dev, key, value)
    return ARMS[arm]


def oom_trial(dev: Device, app: str, arm: str, run_dir: Path, args) -> dict:
    """Fresh Steam, launch, load the save, and see whether the session survives."""
    run_dir.mkdir(parents=True, exist_ok=True)
    env = set_arm(dev, arm)
    ensure_steam(dev, fresh=True)
    name = "oom-%s.jsonl" % run_dir.name
    dev.sh("mkdir -p " + REMOTE + " && rm -f " + REMOTE + "/" + name)
    dev.mark("idle")
    before_exit = last_exit(dev).get("timestamp")
    total = args.idle + 60 + args.title + 10 + args.watch + 20
    rec = recorder(dev, total, args.interval, name)
    events = {"profile": arm, "app": app, "started": time.time(), "kind": "oom", "env": env, "arm": arm}
    time.sleep(args.idle)
    dev.mark("launch")
    t0 = time.monotonic()
    launched = dev.ctl("launch", app, "--wait", "--timeout", "120", timeout=130)
    events["launch_s"] = round(time.monotonic() - t0, 2) if launched.get("ok") else None
    if not launched.get("ok"):
        events["launch_error"] = launched.get("error") or launched.get("message")
        rec.kill()
        (run_dir / "events.json").write_text(json.dumps(events, indent=1))
        raise RuntimeError("app %s did not launch: %s" % (app, events["launch_error"]))
    dev.mark("game")
    time.sleep(args.title)
    load_attempts = []
    base = system_available(dev)
    for attempt in range(args.load_attempts):
        dev.mark("load")
        attempt_t = time.time()
        for step in args.load_steps.split(";"):
            kind, _, rest = step.partition(":")
            if kind == "pad":
                buttons, _, timing = rest.partition("@")
                hold, _, gap = (timing or "200").partition("/")
                dev.ctl("input", "gamepad", *buttons.split(","), "--hold", hold, "--gap", gap or "300", timeout=60)
            elif kind == "key":
                dev.ctl("input", "key", rest, timeout=30)
            elif kind == "tap":
                x, _, y = rest.partition(",")
                dev.ctl("input", "tap", x, y, timeout=30)
            elif kind == "sleep":
                time.sleep(float(rest))
        started = time.monotonic()
        lowest = base
        died = False
        while time.monotonic() - started < args.load_check:
            if running(dev) is False:
                died = True
                break
            now = system_available(dev)
            if now is None:
                break
            lowest = min(lowest or now, now)
            if base is not None and base - lowest >= 400:
                break
            time.sleep(1)
        load_attempts.append({"attempt": attempt + 1, "t": attempt_t, "died": died,
                              "drop_mib": None if base is None or lowest is None else base - lowest})
        if died or base is None or lowest is None or base - lowest >= 400:
            break
    events["load_attempts"] = load_attempts
    events["loaded"] = any(a["died"] or (a["drop_mib"] or 0) >= 400 for a in load_attempts)
    load_t = load_attempts[-1]["t"] if load_attempts else time.time()
    events["load_t"] = load_t
    before_load = base
    alive = running(dev) is not False
    if alive:
        dev.ctl("screenshot", str(run_dir / "after-inputs.png"), timeout=60)
    else:
        events["died_after_s"] = round(time.time() - load_t, 1)
    while alive and time.time() - load_t < args.watch:
        time.sleep(5)
        if running(dev) is False:
            alive = False
            events["died_after_s"] = round(time.time() - load_t, 1)
            break
    events["survived"] = alive
    events["available_before_load_mib"] = before_load
    exit_now = last_exit(dev)
    events["exit"] = exit_now if exit_now.get("timestamp") != before_exit else None
    if events["exit"]:
        try:
            killed = time.mktime(time.strptime(events["exit"]["timestamp"].split(".")[0], "%Y-%m-%d %H:%M:%S"))
            events["died_after_s"] = round(killed - load_t, 1)
        except (KeyError, ValueError):
            pass
    if alive:
        dev.ctl("screenshot", str(run_dir / "loaded.png"), timeout=60)
        dev.ctl("quit", app, "--timeout", "30", timeout=40)
    rec.wait(timeout=total + 120) if alive else rec.kill()
    if not alive:
        ensure_steam(dev)
    text = dev.fetch(REMOTE + "/" + name)
    (run_dir / "samples.jsonl").write_text(text)
    events["finished"] = time.time()
    (run_dir / "events.json").write_text(json.dumps(events, indent=1))
    return events


FLAG_BASE = "--no-zygote --in-process-gpu -AudioServiceOutOfProcess"
# Every variant sets all of these, so one variant never inherits another's persistent setting.
FLAG_DEFAULTS = {"DD_WEBHELPER_ARGS": FLAG_BASE, "DD_STEAM_SOFT_COMPOSITING": "0", "DD_STEAM_CEF_GPU": "1"}
FLAGS = {
    "default": {},
    "v8-small": {"DD_WEBHELPER_ARGS": FLAG_BASE + " --js-flags=--optimize-for-size,--max-semi-space-size=2"},
    "gpu-budget": {"DD_WEBHELPER_ARGS": FLAG_BASE +
                   " --force-gpu-mem-available-mb=96 --force-gpu-mem-discardable-limit-mb=32"},
    "soft-compositing": {"DD_STEAM_SOFT_COMPOSITING": "1"},
    "cef-software": {"DD_STEAM_CEF_GPU": "0"},
}


def set_variant(dev: Device, variant: str) -> dict:
    env = dict(FLAG_DEFAULTS, **FLAGS[variant])
    for key, value in env.items():
        set_env(dev, key, value)
    return env


def flags_run(dev: Device, variant: str, run_dir: Path, args) -> dict:
    """A fresh session with one web helper flag set, sampled in the library after it settles."""
    run_dir.mkdir(parents=True, exist_ok=True)
    env = set_variant(dev, variant)
    value = env["DD_WEBHELPER_ARGS"]
    ensure_steam(dev, fresh=True)
    cmdline = dev.sh("xargs -0 echo < /proc/$(pgrep -f 'steamwebhelper -nocrash' | head -1)/cmdline")
    time.sleep(args.settle)
    samples = []
    for _ in range(args.samples):
        text = dev.sh(LITE + " sample")
        try:
            samples.append(json.loads(text))
        except ValueError:
            pass
        time.sleep(args.interval)
    with open(run_dir / "samples.jsonl", "w") as stream:
        for item in samples:
            print(json.dumps(item), file=stream)
    dev.ctl("screenshot", str(run_dir / "library.png"), timeout=60)
    events = {"kind": "flags", "variant": variant, "env": env, "helper_cmdline": cmdline.strip()[:4000],
              "helper_cmdline_has": [f for f in (value or "").split() if f.startswith("--") and f in cmdline],
              "started": time.time()}
    (run_dir / "events.json").write_text(json.dumps(events, indent=1))
    rows = []
    for item in samples:
        roles = {}
        for r in item["processes"]:
            if r["role"] not in NOT_CLIENT:
                roles[r["role"]] = roles.get(r["role"], 0) + (r.get("Pss") or 0)
        rows.append({"total": sum(roles.values()) / 1024,
                     "renderer": roles["renderer"] / 1024 if roles.get("renderer") else None,
                     "browser": roles.get("browser", 0) / 1024,
                     "gpu": sum((item.get("gpu") or {}).values()) / 1048576})
    return {"variant": variant, "applied": events["helper_cmdline_has"],
            **{k: round(statistics.median([r[k] for r in rows]), 1) for k in ("total", "renderer", "browser", "gpu")}}


def cycle(dev: Device, app: str, arm: str, run_dir: Path, args) -> dict:
    run_dir.mkdir(parents=True, exist_ok=True)
    env = set_arm(dev, arm)
    ensure_steam(dev, fresh=True)
    dev.sh("mkdir -p " + REMOTE + " && rm -f " + REMOTE + "/samples.jsonl")
    dev.mark("idle")
    total = args.idle + args.launch_timeout + args.game + args.overlay + args.back + 30
    cdp = ["--cdp"] if args.cdp else []
    recorder = subprocess.Popen(
        [sys.executable, str(CTL)] + (["--serial", dev.serial] if dev.serial else []) +
        ["guest", "--timeout", str(total + 30), "--", LITE, "record", str(total),
         str(args.interval), REMOTE + "/samples.jsonl", "--mark", REMOTE + "/mark"] + cdp,
        stdout=open(run_dir / "recorder.json", "w"), stderr=subprocess.STDOUT)
    events = {"profile": arm, "env": env, "app": app, "started": time.time()}
    try:
        time.sleep(args.idle)
        dev.mark("launch")
        t0 = time.monotonic()
        launched = dev.ctl("launch", app, "--wait", "--timeout", str(args.launch_timeout), timeout=args.launch_timeout)
        events["launch_s"] = round(time.monotonic() - t0, 2) if launched.get("ok") else None
        events["launch_error"] = None if launched.get("ok") else launched.get("error") or launched.get("message")
        if not launched.get("ok"):
            raise RuntimeError("the game did not launch: %s" % events["launch_error"])
        dev.mark("game")
        time.sleep(max(0, args.game - args.pacing))
        pacing = dev.ctl("pacing", "--seconds", str(args.pacing), timeout=args.pacing + 30)
        events["pacing"] = {k: pacing.get(k) for k in ("fpsOnScreen", "presentToPresentMs", "cadenceMs",
                                                        "offCadencePercent", "frames", "error")}
        if args.overlay:
            dev.mark("overlay")
            opens = []
            for _ in range(args.overlay_rounds):
                t1 = time.monotonic()
                dev.ctl("input", "gamepad", "guide", timeout=20)
                shown = wait_focus(dev, lambda a: a == 769, 10)
                opens.append(None if shown is None else round(time.monotonic() - t1, 2))
                time.sleep(2)
                dev.ctl("input", "gamepad", "guide", timeout=20)
                wait_focus(dev, lambda a: a not in (None, 769), 10)
                time.sleep(2)
            events["overlay_open_s"] = opens
        dev.mark("quit")
        dev.ctl("quit", app, "--timeout", "30", timeout=40)
        dev.mark("back")
        time.sleep(args.back)
    finally:
        recorder.wait(timeout=total + 120)
    text = dev.fetch(REMOTE + "/samples.jsonl")
    (run_dir / "samples.jsonl").write_text(text)
    try:
        reply = json.loads((run_dir / "recorder.json").read_text() or "{}")
    except ValueError:
        reply = {}
    samples = [line for line in text.splitlines() if line.strip()]
    if recorder.returncode != 0 or reply.get("exitCode") not in (0, None) or len(samples) < 2:
        raise RuntimeError("the guest recorder failed (exit %s, %d samples); see %s"
                           % (reply.get("exitCode", recorder.returncode), len(samples), run_dir / "recorder.json"))
    events["finished"] = time.time()
    (run_dir / "events.json").write_text(json.dumps(events, indent=1))
    return events


def set_env(dev: Device, key: str, value: str) -> None:
    """Set one persistent session variable; a refused write stops the run instead of mislabelling it."""
    reply = dev.ctl("env", "set", "--persistent", "%s=%s" % (key, value), timeout=30)
    if not reply.get("ok"):
        raise RuntimeError("could not set %s=%s: %s" % (key, value, json.dumps(reply)[:300]))


def fresh_session(dev: Device, env: dict) -> bool:
    for key, value in env.items():
        set_env(dev, key, value)
    dev.ctl("stop", "--timeout", "60", timeout=80)
    if running(dev) is not False:
        return False
    dev.adb("shell", "monkey -p com.droiddeck.launcher -c android.intent.category.LAUNCHER 1")
    time.sleep(5)
    return bool(dev.ctl("start", "steam", "--wait", "--timeout", "300", timeout=320).get("ok"))


def snapshot(dev: Device, label: str) -> dict | None:
    """The client tree's memory and the web helper's CPU ticks, printed as it is taken."""
    try:
        d = json.loads(dev.sh(LITE + " sample"))
    except ValueError:
        print(time.strftime("%H:%M:%S"), label, "sample failed", flush=True)
        return None
    rows = [r for r in d["processes"] if r["role"] not in NOT_CLIENT]
    out = {"label": label, "t": d["t"], "mono": d["mono"], "tck": d["clk_tck"],
           "pss": sum(r.get("Pss") or 0 for r in rows) // 1024,
           "renderer": sum(r.get("Pss") or 0 for r in rows if r["role"] == "renderer") // 1024,
           "swappss": sum(r.get("SwapPss") or 0 for r in rows) // 1024,
           "gpu": sum((d.get("gpu") or {}).values()) // 1048576,
           "avail": d["memory"]["MemAvailable"] // 1024,
           "ticks": {str(r["pid"]): r.get("ticks") or 0 for r in rows if r["role"] in WEB}}
    print(time.strftime("%H:%M:%S"), label, *("%s %s" % (k, out[k]) for k in
                                                ("pss", "renderer", "swappss", "gpu", "avail")), flush=True)
    return out


def web_cpu(a: dict | None, b: dict | None) -> float | None:
    if not a or not b or b["mono"] <= a["mono"]:
        return None
    used = sum(b["ticks"][p] - a["ticks"][p] for p in b["ticks"] if p in a["ticks"])
    return round(100.0 * used / a["tck"] / (b["mono"] - a["mono"]), 1)


def wait_app(dev: Device, want: int, limit: float) -> float | None:
    start = time.monotonic()
    while time.monotonic() - start < limit:
        if dev.ctl("focus", timeout=30).get("focusedApp") == want:
            return round(time.monotonic() - start, 2)
        time.sleep(0.2)
    return None


def pacing_summary(pace: dict) -> dict:
    hist = {int(k): v for k, v in (pace.get("presentToPresentMs") or {}).items()}
    total = sum(hist.values())
    out = {"fps": pace.get("fpsOnScreen"), "off_cadence_pct": pace.get("offCadencePercent"),
           "hist": pace.get("presentToPresentMs"), "error": pace.get("error")}
    for q in (50, 95, 99):
        seen = 0
        for ms, count in sorted(hist.items()):
            seen += count
            if total and seen >= total * q / 100:
                out["p%d" % q] = ms
                break
    return out


def steady(dev: Device, arm: str, app: str, out: Path) -> dict:
    """One fresh session per arm: library, a game in front for 80 s, three overlay round trips, library."""
    out.mkdir(parents=True, exist_ok=True)
    run = {"level": arm, "env": ARMS[arm], "app": app, "started": fresh_session(dev, ARMS[arm])}
    if not run["started"]:
        raise RuntimeError("Steam did not start for arm %s" % arm)
    time.sleep(30)
    run["idle"] = snapshot(dev, "library idle")
    t0 = time.monotonic()
    run["launch_ok"] = bool(dev.ctl("launch", app, "--wait", "--timeout", "180", timeout=200).get("ok"))
    run["launch_s"] = round(time.monotonic() - t0, 1)
    if not run["launch_ok"]:
        (out / "run.json").write_text(json.dumps(run, indent=1))
        raise RuntimeError("app %s did not take focus" % app)
    time.sleep(60)
    a = snapshot(dev, "game +60s")
    run["pacing"] = pacing_summary(dev.ctl("pacing", "--seconds", "20", timeout=60))
    run["game"] = snapshot(dev, "game +80s")
    run["web_cpu_pct"] = web_cpu(a, run["game"])
    opens, closes = [], []
    for i in range(3):
        t1 = time.monotonic()
        dev.ctl("input", "gamepad", "guide", "--hold", "150", timeout=30)
        opens.append(None if wait_app(dev, 769, 10) is None else round(time.monotonic() - t1, 2))
        if i == 0:
            dev.ctl("screenshot", str(out / "overlay.png"), timeout=60)
        time.sleep(2)
        t2 = time.monotonic()
        dev.ctl("input", "gamepad", "guide", "--hold", "150", timeout=30)
        closes.append(None if wait_app(dev, int(app), 10) is None else round(time.monotonic() - t2, 2))
        time.sleep(3)
    run["overlay_open_s"], run["overlay_close_s"] = opens, closes
    print(time.strftime("%H:%M:%S"), "web cpu", run["web_cpu_pct"], "overlay open", opens, "close", closes, flush=True)
    run["after_overlay"] = snapshot(dev, "after overlay")
    dev.ctl("quit", app, "--timeout", "30", timeout=40)
    time.sleep(15)
    run["back"] = snapshot(dev, "library after quit")
    (out / "run.json").write_text(json.dumps(run, indent=1))
    return run


def library(dev: Device, soft: str, out: Path) -> dict:
    """The settled library (90 s after start) with the web helper composited on the GPU or in software."""
    out.mkdir(parents=True, exist_ok=True)
    run = {"soft": soft, "started": fresh_session(dev, {"DD_STEAM_SOFT_COMPOSITING": soft})}
    if not run["started"]:
        raise RuntimeError("Steam did not start with DD_STEAM_SOFT_COMPOSITING=%s" % soft)
    time.sleep(90)
    a = snapshot(dev, "library +90s")
    time.sleep(20)
    b = snapshot(dev, "library +110s")
    run.update({"idle_cpu": web_cpu(a, b), "idle_pss": b and b["pss"], "idle_gpu": b and b["gpu"],
                "idle_avail": b and b["avail"]})
    dev.ctl("screenshot", str(out / "library.png"), timeout=60)
    (out / "run.json").write_text(json.dumps(run, indent=1))
    return run


UI_WINDOW = "Steam Big Picture Mode"
# Big Picture's frosted bars and panels are backdrop-filter blurs, some on ::before pseudo-elements.
NO_BACKDROP = "*,*::before,*::after{backdrop-filter:none!important}"
NO_SCREENSAVER = "yNwLAA=="  # CMsgClientSettings field 24009 (system_idle_screensaver_ac_sec) = 0

RECORD_JS = r"""new Promise(done => {
  const seconds = %(seconds)s, speed = %(speed)s / 1000;
  const el = document.querySelector("._TabContentsScroll");
  if (speed && !el) return done(JSON.stringify({error: "no scroll container"}));
  if (speed) el.scrollTop = 0;
  const frames = [], loaf = [];
  const obs = new PerformanceObserver(list => { for (const e of list.getEntries())
    loaf.push([Math.round(e.startTime), Math.round(e.duration), Math.round(e.blockingDuration || 0)]); });
  obs.observe({type: "long-animation-frame"});
  setTimeout(() => {
    let t0 = null;
    function step(ts) {
      if (t0 === null) t0 = ts;
      frames.push(Math.round((ts - t0) * 10) / 10);
      if (speed) el.scrollTop = speed * (ts - t0);
      if (ts - t0 < seconds * 1000) return requestAnimationFrame(step);
      setTimeout(() => { obs.disconnect();
        done(JSON.stringify({frames, loaf: loaf.filter(x => x[0] >= t0).map(x => [x[0] - Math.round(t0), x[1], x[2]]),
                             images: document.images.length})); }, 300);
    }
    requestAnimationFrame(step);
  }, %(delay)s);
})"""


def frame_stats(frames: list[float]) -> dict:
    """Steam's own frame cadence from requestAnimationFrame timestamps (ms)."""
    gaps = [b - a for a, b in zip(frames, frames[1:])]
    if not gaps:
        return {"frames": len(frames)}
    ordered = sorted(gaps)

    def pct(p):
        return round(ordered[min(len(ordered) - 1, int(len(ordered) * p / 100))], 1)
    return {"frames": len(frames), "fps": round(1000 * len(gaps) / (frames[-1] - frames[0]), 1),
            "p50": pct(50), "p95": pct(95), "p99": pct(99), "max": round(ordered[-1], 1),
            "over_25ms": sum(1 for g in gaps if g > 25), "over_50ms": sum(1 for g in gaps if g > 50),
            "time_over_25ms": round(sum(g for g in gaps if g > 25) / 1000, 2)}


def ui_record(dev: Device, seconds: float, speed: float = 0, delay: float = 1000, during=None) -> dict:
    """Record Steam's frames for `seconds`, scrolling the library at `speed` px/s or running `during()`."""
    reply: dict = {}
    js = RECORD_JS % {"seconds": seconds, "speed": speed, "delay": delay}
    worker = threading.Thread(target=lambda: reply.update(
        dev.ctl("cdp", "eval", "--target", UI_WINDOW, "--timeout", str(seconds + 30), js, timeout=seconds + 60)))
    worker.start()
    if during:
        time.sleep(delay / 1000 + 0.3)
        during()
    worker.join()
    try:
        data = json.loads(reply.get("value") or "")
    except ValueError:
        raise RuntimeError("the frame recorder failed: %s" % json.dumps(reply)[:300])
    if "error" in data:
        raise RuntimeError(data["error"])
    loaf = data.get("loaf") or []
    return {**frame_stats(data["frames"]), "loaf": len(loaf), "loaf_ms": sum(x[1] for x in loaf),
            "loaf_blocking_ms": sum(x[2] for x in loaf), "images": data.get("images"), "raw": data["frames"]}


def ui_style(dev: Device, css: str) -> None:
    js = ('(()=>{let s=document.getElementById("dd-bench-style"); if(!s){s=document.createElement("style");'
          's.id="dd-bench-style"; document.head.appendChild(s);} s.textContent=%s; return "ok"})()' % json.dumps(css))
    if dev.ctl("cdp", "eval", "--target", UI_WINDOW, js, timeout=60).get("value") != "ok":
        raise RuntimeError("could not set the UI style")


def library_tab(dev: Device) -> None:
    js = ('(()=>{const w=SteamUIStore.WindowStore.SteamUIWindows[0]; w.m_Navigator.LibraryTab("AllGames");'
          'return w.m_locationPathname})()')
    if "AllGames" not in str(dev.ctl("cdp", "eval", js, timeout=60).get("value")):
        raise RuntimeError("could not open the library grid")


def smooth(dev: Device, arm: str, out: Path, rounds: int) -> dict:
    """One fresh session: UI frame cadence while scrolling the library grid and opening the main menu,
    with Steam's styles as shipped and with every backdrop blur removed."""
    out.mkdir(parents=True, exist_ok=True)
    run = {"arm": arm, "env": ARMS[arm], "started": fresh_session(dev, ARMS[arm]), "rounds": []}
    if not run["started"]:
        raise RuntimeError("Steam did not start for arm %s" % arm)
    dev.ctl("cdp", "eval", "SteamClient.Settings.SetSetting(%s)" % json.dumps(NO_SCREENSAVER), timeout=60)
    time.sleep(45)
    library_tab(dev)
    time.sleep(10)
    run["idle"] = snapshot(dev, "library grid")
    for i in range(rounds):
        for style in ("shipped", "no-backdrop"):
            ui_style(dev, NO_BACKDROP if style != "shipped" else "")
            time.sleep(2)
            entry = {"round": i + 1, "style": style}
            a = snapshot(dev, "%s before scroll" % style)
            entry["scroll"] = ui_record(dev, 12, speed=1500)
            b = snapshot(dev, "%s after scroll" % style)
            entry["scroll"]["web_cpu_pct"] = web_cpu(a, b)
            entry["scroll"]["gpu_mib"] = b and b["gpu"]
            presses = ["down"] * 20 + ["up"] * 20
            entry["pad"] = ui_record(dev, 14, during=lambda: dev.ctl(
                "input", "gamepad", *presses, "--hold", "60", "--gap", "250", timeout=60))
            c = snapshot(dev, "%s after pad" % style)
            entry["pad"]["web_cpu_pct"] = web_cpu(b, c)
            entry["pad"]["gpu_mib"] = c and c["gpu"]

            def menu():
                for _ in range(3):
                    dev.ctl("input", "gamepad", "guide", "--hold", "120", timeout=30)
                    time.sleep(1.5)
                    dev.ctl("input", "gamepad", "b", "--hold", "120", timeout=30)
                    time.sleep(1.5)
            entry["menu"] = ui_record(dev, 13, during=menu)
            d = snapshot(dev, "%s after menu" % style)
            entry["menu"]["web_cpu_pct"] = web_cpu(c, d)
            entry["menu"]["gpu_mib"] = d and d["gpu"]
            entry["menu"]["pss_mib"] = d and d["pss"]
            print(time.strftime("%H:%M:%S"), arm, style, *("%s %s fps p95 %s" % (k, entry[k].get("fps"), entry[k].get("p95"))
                                                          for k in ("scroll", "pad", "menu")), flush=True)
            run["rounds"].append(entry)
            if i == 0:
                dev.ctl("screenshot", str(out / ("grid-%s.png" % style)), timeout=60)
    ui_style(dev, "")
    (out / "run.json").write_text(json.dumps(run, indent=1))
    return run


# ---- analysis -----------------------------------------------------------------------------------

def load(run_dir: Path) -> tuple[list[dict], dict]:
    samples = [json.loads(line) for line in (run_dir / "samples.jsonl").read_text().splitlines() if line.strip()]
    events = json.loads((run_dir / "events.json").read_text())
    return samples, events


def tree(sample: dict, roles=None) -> dict:
    rows = [r for r in sample["processes"] if r["role"] not in NOT_CLIENT and (roles is None or r["role"] in roles)]
    pss = [r.get("Pss") for r in rows]
    known = [p for p in pss if p is not None]
    return {"pss": sum(known) if known else None, "swap": sum(r.get("SwapPss") or 0 for r in rows),
            "unreadable": len(pss) - len(known), "ticks": {r["pid"]: r.get("ticks") or 0 for r in rows}}


def cpu(a: dict, b: dict, roles=None) -> float | None:
    dt = b["mono"] - a["mono"]
    if dt <= 0:
        return None
    ta, tb = tree(a, roles)["ticks"], tree(b, roles)["ticks"]
    used = sum(tb[p] - ta[p] for p in tb if p in ta and tb[p] >= ta[p])
    return 100.0 * used / b["clk_tck"] / dt


def phases(samples: list[dict]) -> dict:
    out: dict[str, list[dict]] = {}
    for s in samples:
        out.setdefault(s.get("mark") or "?", []).append(s)
    return out


def summarize(samples: list[dict], events: dict) -> dict:
    row = {"profile": events["profile"], "launch_s": events.get("launch_s")}
    by = phases(samples)
    for name, group in by.items():
        trees = [tree(s) for s in group]
        web = [tree(s, WEB) for s in group]
        pss = [t["pss"] for t in trees if t["pss"] is not None]
        if pss:
            tail = pss[len(pss) // 2:]
            row[name + "_pss_mib"] = round(statistics.median(tail) / 1024, 1)
            row[name + "_pss_peak_mib"] = round(max(pss) / 1024, 1)
            web_tail = [t["pss"] for t in web[len(web) // 2:] if t["pss"] is not None]
            if web_tail:
                row[name + "_web_pss_mib"] = round(statistics.median(web_tail) / 1024, 1)
        row[name + "_swappss_mib"] = round(statistics.median([t["swap"] for t in trees[len(trees) // 2:]]) / 1024, 1)
        avail = [s["memory"].get("MemAvailable") for s in group if s["memory"].get("MemAvailable")]
        if avail:
            row[name + "_avail_min_mib"] = round(min(avail) / 1024, 1)
        if len(group) >= 2:
            half = group[len(group) // 2:] if len(group) >= 4 else group
            row[name + "_cpu_pct"] = round(cpu(half[0], half[-1]) or 0, 1)
            row[name + "_web_cpu_pct"] = round(cpu(half[0], half[-1], WEB) or 0, 1)
            row[name + "_lite_cpu_pct"] = round(_self_cpu(half[0], half[-1]), 2)
        lite_pss = [r.get("Pss") for s in group for r in s["processes"] if r["role"] == "steam-lite" and r.get("Pss")]
        if lite_pss:
            row["lite_pss_mib"] = round(max(lite_pss) / 1024, 1)
        psi = [s["memory"].get("psi_some_avg10") for s in group if s["memory"].get("psi_some_avg10") is not None]
        if psi:
            row[name + "_psi_some_max"] = max(psi)
    pacing = events.get("pacing") or {}
    row["fps"] = pacing.get("fpsOnScreen")
    hist = {int(k): v for k, v in (pacing.get("presentToPresentMs") or {}).items()}
    if hist:
        ordered = sorted(hist.items())
        n = sum(hist.values())
        for pct in (95, 99):
            seen = 0
            for ms, count in ordered:
                seen += count
                if seen >= n * pct / 100:
                    row["frame_p%d_ms" % pct] = ms
                    break
    opens = [o for o in events.get("overlay_open_s") or [] if o is not None]
    row["overlay_open_median_s"] = round(statistics.median(opens), 2) if opens else None
    row["overlay_failures"] = sum(1 for o in events.get("overlay_open_s") or [] if o is None)
    return row


def _self_cpu(a: dict, b: dict) -> float:
    dt = b["mono"] - a["mono"]
    ta = {r["pid"]: r.get("ticks") or 0 for r in a["processes"] if r["role"] == "steam-lite"}
    tb = {r["pid"]: r.get("ticks") or 0 for r in b["processes"] if r["role"] == "steam-lite"}
    used = sum(tb[p] - ta[p] for p in tb if p in ta)
    return 100.0 * used / b["clk_tck"] / dt if dt > 0 else 0.0


def report(root: Path) -> list[dict]:
    rows = []
    for events_path in sorted(root.glob("**/events.json")):
        try:
            samples, events = load(events_path.parent)
        except (OSError, ValueError):
            continue
        if not samples or events.get("kind") in ("oom", "flags") or "profile" not in events:
            continue
        row = summarize(samples, events)
        row["run"] = str(events_path.parent.relative_to(root))
        rows.append(row)
    if not rows:
        return rows
    keys = ["run", "profile"] + sorted({k for r in rows for k in r} - {"run", "profile"})
    with open(root / "runs.csv", "w", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=keys)
        writer.writeheader()
        writer.writerows(rows)
    return rows


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", default=os.environ.get("ADB_SERIAL"))
    sub = parser.add_subparsers(dest="command", required=True)
    p_info = sub.add_parser("info")
    p_info.add_argument("--out", type=Path, required=True)
    p_cycle = sub.add_parser("cycle")
    p_cycle.add_argument("--app", required=True)
    p_cycle.add_argument("--arms", default="stock,softcomp", help="comma-separated names from ARMS")
    p_cycle.add_argument("--repeat", type=int, default=3)
    p_cycle.add_argument("--out", type=Path, required=True)
    p_cycle.add_argument("--idle", type=float, default=40)
    p_cycle.add_argument("--game", type=float, default=60)
    p_cycle.add_argument("--pacing", type=float, default=20)
    p_cycle.add_argument("--overlay", type=float, default=1)
    p_cycle.add_argument("--overlay-rounds", type=int, default=3)
    p_cycle.add_argument("--back", type=float, default=40)
    p_cycle.add_argument("--interval", type=float, default=2)
    p_cycle.add_argument("--launch-timeout", type=float, default=180)
    p_cycle.add_argument("--cdp", action="store_true")
    p_cycle.add_argument("--seed", type=int, default=1)
    p_ablate = sub.add_parser("ablate")
    p_ablate.add_argument("--actions", default="gc,moderate,critical")
    p_ablate.add_argument("--waits", default="3,20,60")
    p_ablate.add_argument("--out", type=Path, required=True)
    p_ablate.add_argument("--label", required=True)
    p_oom = sub.add_parser("oom")
    p_oom.add_argument("--app", required=True)
    p_oom.add_argument("--arms", default="stock,softcomp", help="comma-separated names from ARMS")
    p_oom.add_argument("--repeat", type=int, default=3)
    p_oom.add_argument("--out", type=Path, required=True)
    p_oom.add_argument("--idle", type=float, default=20)
    p_oom.add_argument("--title", type=float, default=25)
    p_oom.add_argument("--load-steps", default="pad:a@2000;key:ENTER;sleep:6;tap:600,363")
    p_oom.add_argument("--load-attempts", type=int, default=4)
    p_oom.add_argument("--load-check", type=float, default=30)
    p_oom.add_argument("--watch", type=float, default=90)
    p_oom.add_argument("--interval", type=float, default=1)
    p_oom.add_argument("--seed", type=int, default=1)
    p_flags = sub.add_parser("flags")
    p_flags.add_argument("--variants", default="default,v8-small,gpu-budget")
    p_flags.add_argument("--repeat", type=int, default=2)
    p_flags.add_argument("--settle", type=float, default=60)
    p_flags.add_argument("--samples", type=int, default=5)
    p_flags.add_argument("--interval", type=float, default=5)
    p_flags.add_argument("--out", type=Path, required=True)
    p_steady = sub.add_parser("steady")
    p_steady.add_argument("--app", required=True)
    p_steady.add_argument("--arms", default="stock,softcomp,stock,softcomp,stock,softcomp",
                          help="one fresh session per entry, in this order (names from ARMS)")
    p_steady.add_argument("--out", type=Path, required=True)
    p_library = sub.add_parser("library")
    p_library.add_argument("--order", default="1,0,1,0", help="DD_STEAM_SOFT_COMPOSITING per fresh session")
    p_library.add_argument("--out", type=Path, required=True)
    p_smooth = sub.add_parser("smooth")
    p_smooth.add_argument("--arms", default="stock,softcomp,stock,softcomp,stock,softcomp",
                          help="one fresh session per entry, in this order (names from ARMS)")
    p_smooth.add_argument("--rounds", type=int, default=2, help="shipped/no-backdrop pairs per session")
    p_smooth.add_argument("--out", type=Path, required=True)
    p_report = sub.add_parser("report")
    p_report.add_argument("root", type=Path)
    args = parser.parse_args(argv)
    unknown = [n for n in (getattr(args, "arms", None) or "").split(",") if n and n not in ARMS]
    if unknown:
        parser.error("unknown arms: %s (known: %s)" % (", ".join(unknown), ", ".join(ARMS)))
    dev = Device(args.serial)
    failed = 0
    if args.command == "info":
        print(json.dumps(info(dev, args.out), indent=1))
        return 0
    if args.command == "steady":
        counts: dict[str, int] = {}
        for arm in args.arms.split(","):
            counts[arm] = counts.get(arm, 0) + 1
            name = "%s-%d" % (arm, counts[arm])
            print("== steady", name, flush=True)
            try:
                steady(dev, arm, args.app, args.out / name)
            except Exception as error:
                failed += 1
                print("run failed: %r" % error, flush=True)
        return 1 if failed else 0
    if args.command == "smooth":
        counts = {}
        for arm in args.arms.split(","):
            counts[arm] = counts.get(arm, 0) + 1
            name = "%s-%d" % (arm, counts[arm])
            print("== smooth", name, flush=True)
            try:
                smooth(dev, arm, args.out / name, args.rounds)
            except Exception as error:
                failed += 1
                print("run failed: %r" % error, flush=True)
        return 1 if failed else 0
    if args.command == "library":
        counts = {}
        for soft in args.order.split(","):
            counts[soft] = counts.get(soft, 0) + 1
            name = "%s-%d" % ("softcomp" if soft == "1" else "default", counts[soft])
            print("== library", name, flush=True)
            try:
                library(dev, soft, args.out / name)
            except Exception as error:
                failed += 1
                print("run failed: %r" % error, flush=True)
        return 1 if failed else 0
    if args.command == "flags":
        try:
            for i in range(args.repeat):
                for variant in args.variants.split(","):
                    print("== %s run %d" % (variant, i + 1), flush=True)
                    try:
                        print(json.dumps(flags_run(dev, variant, args.out / ("flags-%s-r%d" % (variant, i + 1)), args)),
                              flush=True)
                    except Exception as error:
                        failed += 1
                        print("run failed: %r" % error, flush=True)
        finally:
            set_variant(dev, "default")
        return 1 if failed else 0
    if args.command in ("cycle", "oom"):
        names = args.arms.split(",")
        order = []
        rng = random.Random(args.seed)
        for i in range(args.repeat):
            block = list(names)
            rng.shuffle(block)
            order += [(i, n) for n in block]
        for i, name in order:
            run_dir = args.out / ("%s-%s-r%d" % (args.app, name, i + 1))
            print("== %s run %d" % (name, i + 1), flush=True)
            try:
                if args.command == "oom":
                    run_dir = args.out / ("oom-%s-%s-r%d" % (args.app, name, i + 1))
                    events = oom_trial(dev, args.app, name, run_dir, args)
                    print(json.dumps({k: events.get(k) for k in ("arm", "profile", "loaded", "survived",
                                                                 "died_after_s", "exit", "launch_s")}), flush=True)
                else:
                    cycle(dev, args.app, name, run_dir, args)
                    print(json.dumps(summarize(*load(run_dir))), flush=True)
            except Exception as error:
                failed += 1
                print("run failed: %r" % error, flush=True)
        return 1 if failed else 0
    if args.command == "ablate":
        rows = ablate(dev, args.actions.split(","), [float(w) for w in args.waits.split(",")], args.out, args.label)
        for row in rows:
            print(json.dumps(row))
        return 0
    rows = report(args.root)
    print(json.dumps(rows, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
