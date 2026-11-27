"""Fill the web helper's zink cache by scrolling the grid, launch a game, watch KGSL while it runs."""
import importlib.util
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import kgsl_snap  # noqa: E402

spec = importlib.util.spec_from_file_location("b", str(__import__("pathlib").Path(__file__).resolve().parents[6] / "tools/steam_lite_bench.py"))
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)
dev = b.Device(kgsl_snap.SERIAL)
pid, app = sys.argv[1], sys.argv[2]
overlay = len(sys.argv) > 3 and sys.argv[3] == "overlay"


def kg(label):
    s = kgsl_snap.summary(kgsl_snap.snap(pid))
    avail = dev.sh("grep MemAvailable /proc/meminfo").split()
    print(time.strftime("%H:%M:%S"), label, "kgsl", s["mib"], "2MiB blocks", next((c for z, c, _ in s["top_sizes"] if z == 2097152), 0),
          "avail", int(avail[1]) // 1024 if len(avail) > 1 else None, flush=True)
    return s["mib"]


b.library_tab(dev)
time.sleep(5)
kg("grid")
b.ui_record(dev, 12, speed=1500)
kg("after scroll")
t0 = time.monotonic()
ok = dev.ctl("launch", app, "--wait", "--timeout", "120", timeout=140).get("ok")
kg("game launched (%s, %.0f s)" % (ok, time.monotonic() - t0))
for i in range(6):
    time.sleep(10)
    kg("game +%ds" % (10 * (i + 1)))
if overlay:
    dev.ctl("input", "gamepad", "guide", "--hold", "150", timeout=30)
    time.sleep(3)
    kg("overlay open")
    dev.ctl("input", "gamepad", "guide", "--hold", "150", timeout=30)
    time.sleep(5)
    kg("overlay closed")
dev.ctl("quit", app, "--timeout", "30", timeout=40)
