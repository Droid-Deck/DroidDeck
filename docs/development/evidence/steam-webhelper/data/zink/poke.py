"""Make the web helper raster one new tile in the Big Picture window, so zink reclaims freed slab entries.

    poke.py [add|shot|remove|all] [WIDTH HEIGHT FORMAT]
"""
import json
import os
import runpy
import sys
import time

m = runpy.run_path("/usr/local/bin/droiddeck-steam-lite")
agent = m["load_agent"]()
client = m["Client"](agent, os.path.join(os.environ.get("DD_LAUNCH_DIR", ""), "agent"))
target = next(t for t in client.targets() if t.get("title") == "Steam Big Picture Mode")
step = sys.argv[1] if len(sys.argv) > 1 else "all"
width, height = (int(sys.argv[2]), int(sys.argv[3])) if len(sys.argv) > 3 else (963, 542)
fmt = sys.argv[4] if len(sys.argv) > 4 else "png"
ADD = ('(()=>{let d=document.getElementById("dd-reclaim"); if(!d){d=document.createElement("div"); d.id="dd-reclaim";'
       'd.style.cssText="position:fixed;left:0;top:0;width:256px;height:256px;z-index:99999;opacity:0.01;pointer-events:none";'
       'document.body.appendChild(d);} d.style.background="rgb("+(Math.random()*255|0)+",0,0)"; return 1})()')
t0 = time.monotonic()
out = {}
if step in ("add", "all"):
    client.call(target, "Runtime.evaluate", {"expression": ADD})
    if step == "all":
        time.sleep(1)
if step in ("shot", "all"):
    shot = client.call(target, "Page.captureScreenshot", {"format": fmt, "clip": {"x": 0, "y": 0, "width": width,
                                                                                  "height": height, "scale": 1}})
    out["shot"] = bool(shot.get("data"))
if step in ("remove", "all"):
    client.call(target, "Runtime.evaluate", {"expression": 'document.getElementById("dd-reclaim")?.remove(); 1'})
out["ms"] = round((time.monotonic() - t0) * 1000)
print(json.dumps(out))
