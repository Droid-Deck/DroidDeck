"""A/B: browse the library grid, launch a game, and either poke the hidden UI once or not; KGSL and MemAvailable."""
import importlib.util
import json
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import kgsl_snap  # noqa: E402

spec = importlib.util.spec_from_file_location("b", str(__import__("pathlib").Path(__file__).resolve().parents[6] / "tools/steam_lite_bench.py"))
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)
dev = b.Device(kgsl_snap.SERIAL)
POKE = open(os.path.join(HERE, "poke.py")).read()
FIND = '''import os
for p in os.listdir("/proc"):
    if p.isdigit():
        try: a = open("/proc/%s/cmdline" % p, "rb").read().split(b"\\0")
        except OSError: continue
        if a and a[0].endswith(b"steamwebhelper") and not any(x.startswith(b"--type=") for x in a): print(p)'''


def measure(pid, label, run):
    s = kgsl_snap.summary(kgsl_snap.snap(pid))
    avail = dev.sh("grep MemAvailable /proc/meminfo").split()
    row = {"label": label, "t": round(time.time(), 1), "kgsl_mib": s["mib"],
           "avail_mib": int(avail[1]) // 1024 if len(avail) > 1 else None}
    run["samples"].append(row)
    print(time.strftime("%H:%M:%S"), run["arm"], label, row["kgsl_mib"], row["avail_mib"], flush=True)


def trial(arm, app, out):
    run = {"arm": arm, "app": app, "samples": []}
    if not b.fresh_session(dev, {"DD_STEAM_SOFT_COMPOSITING": "0", "DD_STEAM_CEF_GPU": "1",
                                 "DD_WEBHELPER_ARGS": b.FLAG_BASE}):
        raise RuntimeError("no session")
    dev.ctl("cdp", "eval", "SteamClient.User.GoOffline(); 1", timeout=60)
    time.sleep(40)
    pid = (dev.ctl("guest", "--", "python3", "-c", FIND, timeout=60).get("stdout") or {}).get("text", "").strip()
    run["pid"] = pid
    measure(pid, "home", run)
    b.library_tab(dev)
    time.sleep(5)
    b.ui_record(dev, 12, speed=1500)
    measure(pid, "after browsing", run)
    if not dev.ctl("launch", app, "--wait", "--timeout", "120", timeout=140).get("ok"):
        raise RuntimeError("launch failed")
    time.sleep(5)
    measure(pid, "game +5s", run)
    if arm == "poke":
        reply = dev.ctl("guest", "--timeout", "60", "--stdin", POKE, "--", "python3", "-", timeout=60)
        run["poke"] = (reply.get("stdout") or {}).get("text", "").strip()
    time.sleep(10)
    measure(pid, "game +15s", run)
    run["pacing"] = b.pacing_summary(dev.ctl("pacing", "--seconds", "15", timeout=60))
    time.sleep(15)
    measure(pid, "game +45s", run)
    json.dump(run, open(os.path.join(out, "%s-%d.json" % (arm, int(time.time()))), "w"), indent=1)
    dev.ctl("quit", app, "--timeout", "30", timeout=40)
    time.sleep(5)
    # Setup's "Game debug logs" is on on this device: Proton writes ~3 GB per run of this game.
    dev.adb("shell", "su -c 'rm -f /data/data/com.droiddeck.launcher/files/logs/*/steam-%s.log'" % app, timeout=60)
    return run


if __name__ == "__main__":
    arms, app, out = sys.argv[1].split(","), sys.argv[2], sys.argv[3]
    os.makedirs(out, exist_ok=True)
    for arm in arms:
        try:
            r = trial(arm, app, out)
            print(json.dumps({"arm": arm, "poke": r.get("poke"), "fps": r["pacing"].get("fps"), "p95": r["pacing"].get("p95")}), flush=True)
        except Exception as error:
            print(arm, "failed", repr(error), flush=True)
