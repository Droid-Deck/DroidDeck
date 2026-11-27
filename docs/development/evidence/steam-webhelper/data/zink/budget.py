"""Fresh session per web helper flag set: KGSL and frame cadence through a grid scroll."""
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
VARIANTS = {
    "default": "",
    "gpu-mem-256": " --force-gpu-mem-available-mb=256",
    "gpu-mem-128": " --force-gpu-mem-available-mb=128",
    "discardable-64": " --force-gpu-mem-discardable-limit-mb=64",
    "angle-vulkan": " --use-gl=angle --use-angle=vulkan",
    "angle-vulkan-features": " --use-gl=angle --use-angle=vulkan +Vulkan +VulkanFromANGLE +DefaultANGLEVulkan",
}


def kg(pid):
    s = kgsl_snap.summary(kgsl_snap.snap(pid))
    return {"mib": s["mib"], "n": s["n"], "two_mib": next((c for size, c, _ in s["top_sizes"] if size == 2097152), 0)}


def run(name, out):
    env = {"DD_WEBHELPER_ARGS": b.FLAG_BASE + VARIANTS[name], "DD_STEAM_SOFT_COMPOSITING": "0", "DD_STEAM_CEF_GPU": "1"}
    if not b.fresh_session(dev, env):
        raise RuntimeError("no session")
    dev.ctl("cdp", "eval", "SteamClient.Settings.SetSetting(%s)" % json.dumps(b.NO_SCREENSAVER), timeout=60)
    time.sleep(45)
    pid = dev.sh("pgrep -f 'steamwebhelper -nocrash' | head -1").strip()
    cmd = dev.sh("tr '\\0' ' ' < /proc/%s/cmdline" % pid)
    script = open(__file__.replace("budget.py", "gpuinfo.py")).read()
    gl = (dev.ctl("guest", "--timeout", "60", "--stdin", script, "--", "python3", "-", timeout=60)
          .get("stdout") or {}).get("text", "")
    try:
        renderer = json.loads(gl)["devices"][0]["deviceString"]
    except (ValueError, KeyError, IndexError, TypeError):
        renderer = gl[-300:]
    r = {"variant": name, "renderer": renderer, "flags_seen": [f for f in VARIANTS[name].split() if f in cmd], "home": kg(pid)}
    b.library_tab(dev)
    time.sleep(8)
    r["grid"] = kg(pid)
    rec = b.ui_record(dev, 12, speed=1500)
    rec.pop("raw")
    r["scroll"] = rec
    r["after_scroll"] = kg(pid)
    presses = ["down"] * 20 + ["up"] * 20
    rec = b.ui_record(dev, 14, during=lambda: dev.ctl("input", "gamepad", *presses, "--hold", "60", "--gap", "250",
                                                       timeout=60))
    rec.pop("raw")
    r["pad"] = rec
    r["after_pad"] = kg(pid)
    time.sleep(20)
    r["idle_20s"] = kg(pid)
    print(json.dumps({"variant": name, "renderer": r["renderer"], "flags": r["flags_seen"], "home": r["home"]["mib"], "grid": r["grid"]["mib"],
                      "after_scroll": r["after_scroll"]["mib"], "after_pad": r["after_pad"]["mib"],
                      "idle": r["idle_20s"]["mib"], "scroll_fps": r["scroll"]["fps"], "scroll_p95": r["scroll"]["p95"],
                      "pad_fps": r["pad"]["fps"], "pad_p95": r["pad"]["p95"]}), flush=True)
    json.dump(r, open(out + "/budget-%s-%d.json" % (name, int(time.time())), "w"), indent=1)


if __name__ == "__main__":
    import os
    out = sys.argv[2]
    os.makedirs(out, exist_ok=True)
    try:
        for name in sys.argv[1].split(","):
            try:
                run(name, out)
            except Exception as error:
                print(name, "failed", repr(error), flush=True)
    finally:
        b.set_variant(dev, "default")
