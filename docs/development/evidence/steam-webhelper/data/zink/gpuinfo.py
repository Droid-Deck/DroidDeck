import json
import runpy

m = runpy.run_path("/usr/local/bin/droiddeck-steam-lite")
agent = m["load_agent"]()
import os
client = m["Client"](agent, os.path.join(os.environ.get("DD_LAUNCH_DIR", ""), "agent"))
browser = client.browser()
info = client.call(browser, "SystemInfo.getInfo")
gpu = info.get("gpu", {})
out = {"devices": gpu.get("devices"), "aux": {k: v for k, v in (gpu.get("auxAttributes") or {}).items()
                                               if k not in ("glExtensions", "glWsExtensions")},
       "features": gpu.get("featureStatus"), "workarounds": gpu.get("driverBugWorkarounds")}
print(json.dumps(out, indent=1)[:6000])
