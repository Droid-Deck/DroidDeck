"""Scroll the grid once per CSS variant in the running session (no session restart)."""
import importlib.util
import json
import os
import sys
import time

spec = importlib.util.spec_from_file_location("b", str(__import__("pathlib").Path(__file__).resolve().parents[6] / "tools/steam_lite_bench.py"))
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)
dev = b.Device(os.environ.get("ADB_SERIAL"))
VARIANTS = {
    "shipped": "",
    "no-pseudo-blur": "*::before,*::after{backdrop-filter:none!important}",
    "no-element-filters": "*{backdrop-filter:none!important;filter:none!important}",
    "no-filters": "*,*::before,*::after{backdrop-filter:none!important;filter:none!important}",
    "pseudo-blur-8px": "*::before,*::after{backdrop-filter:blur(8px)!important}",
    "pseudo-blur-20px": "*::before,*::after{backdrop-filter:blur(20px)!important}",
    "all-blur-8px": "*,*::before,*::after{backdrop-filter:blur(8px)!important}",
    "small-blurs": "*,*::before,*::after{backdrop-filter:blur(8px)!important}*{filter:none!important}",
    "no-backdrop": "*,*::before,*::after{backdrop-filter:none!important}",
}
TAG = r"""(()=>{let n=0; for (const e of document.querySelectorAll("*")) {
  const s=getComputedStyle(e); if (s.backdropFilter!=="none" && !e.dataset.ddbf) { e.dataset.ddbf=s.backdropFilter; n++; }
  for (const ps of ["::before","::after"]) { const p=getComputedStyle(e,ps); if (p.backdropFilter!=="none") { e.dataset["ddbf"+ps.slice(2)]="1"; n++; } } }
  return String(n)})()"""
for r in (4, 8, 16, 32):
    VARIANTS["radius-%d" % r] = ("[data-ddbf]{backdrop-filter:blur(%dpx)!important}"
                                 "[data-ddbfbefore]::before,[data-ddbfafter]::after{backdrop-filter:blur(%dpx)!important}" % (r, r))
VARIANTS["hide-invisible"] = "[data-ddbfhide]{display:none!important}"
VARIANTS["hide-invisible+no-bars"] = ("[data-ddbfhide]{display:none!important}"
                                      "*::before,*::after{backdrop-filter:none!important}"
                                      "[data-ddbf='blur(100px)']{backdrop-filter:none!important}")
HIDE = r"""(()=>{let n=0; for (const e of document.querySelectorAll("*")) { const s=getComputedStyle(e);
  if (s.backdropFilter!=="none") { let p=e, op=1; while (p) { op*=parseFloat(getComputedStyle(p).opacity); p=p.parentElement; }
    if (op===0) { e.dataset.ddbfhide="1"; n++; } } } return String(n)})()"""
names = sys.argv[1].split(",")
print("invisible", dev.ctl("cdp", "eval", "--target", b.UI_WINDOW, HIDE).get("value"))
print("tagged", dev.ctl("cdp", "eval", "--target", b.UI_WINDOW, TAG).get("value"))
reps = int(sys.argv[2]) if len(sys.argv) > 2 else 1
b.library_tab(dev)
out = []
for r in range(reps):
    for name in names:
        b.ui_style(dev, VARIANTS[name])
        time.sleep(2)
        rec = b.ui_record(dev, 12, speed=1500)
        rec.pop("raw")
        screens = [t.get("title") for t in dev.ctl("cdp", "targets").get("targets", [])]
        rec["screensaver"] = any("Screensaver" in str(t) for t in screens)
        print(name, json.dumps({k: rec[k] for k in ("fps", "p50", "p95", "over_50ms", "loaf_ms", "screensaver")}), flush=True)
        out.append({"variant": name, **rec})
b.ui_style(dev, "")
print(json.dumps(out))
