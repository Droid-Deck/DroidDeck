#!/usr/bin/env python3
"""Render the DroidDeck boot animation to .webm (Steam) or .mp4 (H.264).

Usage:  python3 render.py [WIDTH] [HEIGHT] [FPS] [OUT] [light|dark]
        python3 render.py 1280 800 60 droiddeck-boot.webm
        python3 render.py 1920 1080 60 droiddeck-boot.mp4
        python3 render.py 1280 800 60 droiddeck-boot-dark.webm dark

Needs: pip install playwright && playwright install chromium, plus ffmpeg with libvpx-vp9.
The animation is drawn by droiddeck-boot.html (same folder); each frame gets real
motion blur (180-degree shutter, up to 48 sub-frames when things move fast).
"""
import base64, os, shutil, subprocess, sys, tempfile, time
from pathlib import Path
from playwright.sync_api import sync_playwright

W = int(sys.argv[1]) if len(sys.argv) > 1 else 1280
H = int(sys.argv[2]) if len(sys.argv) > 2 else 800
FPS = int(sys.argv[3]) if len(sys.argv) > 3 else 60
OUT = sys.argv[4] if len(sys.argv) > 4 else f"droiddeck-boot-{W}x{H}.webm"
THEME = sys.argv[5] if len(sys.argv) > 5 else "light"                  # light | dark
ONLY = [int(x) for x in os.environ.get("FRAMES", "").split(",") if x]   # debug: render some frames only

html = Path(__file__).with_name("droiddeck-boot.html").resolve()
tmp = Path(os.environ["FRAMEDIR"]) if os.environ.get("FRAMEDIR") else Path(tempfile.mkdtemp(prefix="ddframes_"))
tmp.mkdir(parents=True, exist_ok=True)   # FRAMEDIR=... makes a long render resumable
t0 = time.time()
with sync_playwright() as p:
    browser = p.chromium.launch()
    page = browser.new_page(viewport={"width": W, "height": H})
    page.goto(f"{html.as_uri()}?render=1&w={W}&h={H}&theme={THEME}")
    page.wait_for_function("typeof DD !== 'undefined'", timeout=30000)
    n = page.evaluate(f"DD.frameCount({FPS})")
    frames = ONLY or range(n)
    for i in frames:
        if (tmp / f"f{i:05d}.png").exists() and not ONLY:
            continue
        r = page.evaluate(f"DD.renderFrame({i}, {FPS})")
        (tmp / f"f{i:05d}.png").write_bytes(base64.b64decode(r["url"].split(",", 1)[1]))
        if i % 30 == 0 or ONLY:
            print(f"frame {i+1}/{n}  blur samples {r['samples']:>2}  {time.time()-t0:5.1f}s", flush=True)
    browser.close()

if ONLY:
    print("frames left in", tmp); sys.exit(0)

COLOR = ["-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709", "-color_range", "tv"]
SOUND = Path(__file__).with_name("droiddeck-boot-sound.wav")   # made by sound.py; muxed in if present
cmd = ["ffmpeg", "-y", "-loglevel", "error", "-framerate", str(FPS), "-i", str(tmp / "f%05d.png")]
if SOUND.exists():
    cmd += ["-i", str(SOUND), "-map", "0:v", "-map", "1:a"]
    cmd += ["-c:a", "aac", "-b:a", "192k"] if OUT.lower().endswith(".mp4") else ["-c:a", "libopus", "-b:a", "160k"]
cmd += ["-vf", "scale=out_color_matrix=bt709:out_range=tv:flags=accurate_rnd+full_chroma_int,format=yuv420p"]
if OUT.lower().endswith(".mp4"):   # H.264 for sharing, previews and anything that won't play webm
    cmd += ["-c:v", "libx264", "-preset", "slow", "-crf", "15", "-profile:v", "high", "-tune", "animation",
            "-g", str(FPS * 2), "-movflags", "+faststart"]
else:                              # VP9 webm for Steam's startup movie
    cmd += ["-c:v", "libvpx-vp9", "-b:v", "0", "-crf", "18", "-deadline", "good", "-cpu-used", "2",
            "-row-mt", "1", "-g", str(FPS * 2)]
cmd += COLOR + ([] if SOUND.exists() else ["-an"]) + [OUT]
subprocess.run(cmd, check=True)
if not os.environ.get("FRAMEDIR"):
    shutil.rmtree(tmp)
print(f"wrote {OUT} ({os.path.getsize(OUT)/1e6:.2f} MB) in {time.time()-t0:.0f}s")
