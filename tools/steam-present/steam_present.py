#!/usr/bin/env python3
"""Measure the Steam menu frame cadence from the host using only unprivileged
`adb shell dumpsys SurfaceFlinger` output.  See README.md.

Sources (run over one window):
  latency   `--latency <exact layer>` polled and de-duplicated (exact timestamps)
  timestats `--timestats -dump` per-layer present2present histogram (1 ms bins);
            used automatically when --latency yields too few frames (BLAST
            surfaces often return only the refresh-period line).
"""
import argparse
import json
import re
import shlex
import subprocess
import sys
import time

INT64_MAX = 9223372036854775807
BUCKETS = [6.7, 8.33, 11.1, 16.7, 33.0]  # labels; 33 also takes everything slower
# upper edges (ms) that assign an interval to the bucket with the same index
EDGES = [7.5, 9.7, 13.9, 25.0]
IDLE_MS = 100.0  # gaps longer than this are idle (BLAST reports on change), not drops
MIN_FRAMES = 10
DEFAULT_LAYER = r"SessionActivity"


def bucket_index(ms):
    for i, e in enumerate(EDGES):
        if ms <= e:
            return i
    return len(EDGES)


def parse_latency(text):
    """-> (refresh_period_ns, [actualPresent_ns]) ; drops 0 / INT64_MAX / junk."""
    lines = [l.strip() for l in text.replace("\r", "").split("\n") if l.strip()]
    if not lines or not lines[0].isdigit():
        return None, []
    period = int(lines[0])
    out = []
    for l in lines[1:]:
        cols = l.split()
        if len(cols) < 3 or not all(c.lstrip("-").isdigit() for c in cols[:3]):
            continue
        t = int(cols[1])
        if t <= 0 or t >= INT64_MAX:
            continue
        out.append(t)
    return period, out


def find_layers(list_text, pattern=DEFAULT_LAYER):
    """Candidate layers, best first: BLAST SurfaceView, other match, game layer."""
    rx = re.compile(pattern)
    names = [l.strip() for l in list_text.replace("\r", "").split("\n") if l.strip()]
    names = [n for n in names if not n.startswith(("Background for", "Bounds for"))]
    m = [n for n in names if rx.search(n)]

    def rank(n):
        return (0 if "SurfaceView" in n and "BLAST" in n else
                1 if "SurfaceView" in n else 2 if "BLAST" not in n else 3)
    game = [n for n in names if "droiddeck_wayland_game" in n and n not in m]
    return sorted(m, key=rank) + game


def intervals_from_ts(ts_ns):
    ts = sorted(set(ts_ns))
    return [(b - a) / 1e6 for a, b in zip(ts, ts[1:])]


def parse_timestats(text, pattern=DEFAULT_LAYER):
    """-> (frames, {ms_bin: count}) summed over layers matching pattern."""
    rx = re.compile(pattern + r"|droiddeck_wayland_game")
    frames, hist = 0, {}
    for part in re.split(r"^displayRefreshRate = ", text.replace("\r", ""), flags=re.M)[1:]:
        layer = re.search(r"^layerName = (.*)$", part, re.M)
        tf = re.search(r"^totalFrames = (\d+)", part, re.M)
        h = re.search(r"^present2present histogram is as below:\n(.*)", part, re.M)
        if not (layer and tf and h and rx.search(layer.group(1))):
            continue
        frames += int(tf.group(1))
        for ms, n in re.findall(r"(\d+)ms=(\d+)", h.group(1)):
            if int(n):
                hist[int(ms)] = hist.get(int(ms), 0) + int(n)
    return frames, hist


def pct(sorted_vals, p):
    if not sorted_vals:
        return None
    k = (len(sorted_vals) - 1) * p / 100.0
    lo = int(k)
    hi = min(lo + 1, len(sorted_vals) - 1)
    return sorted_vals[lo] + (sorted_vals[hi] - sorted_vals[lo]) * (k - lo)


def verdict(active, frames):
    if frames < 2 or len(active) < 1:
        return "no-frames"
    s = sorted(active)
    p50 = pct(s, 50)
    if sum(1 for v in s if v > 1.5 * p50) / len(s) > 0.10:
        return "dropped-frames"
    if p50 <= EDGES[1]:
        return "120-paced"
    if p50 <= EDGES[2]:
        return "90-capped"
    if p50 <= 20.0:
        return "60-capped"
    return "dropped-frames"


def summarize(intervals_ms, frames, window_s, source):
    """intervals_ms: list of floats (timestats bins pre-expanded to bin midpoints)."""
    active = [v for v in intervals_ms if v <= IDLE_MS]
    idle = len(intervals_ms) - len(active)
    s = sorted(active)
    hist = [0] * len(BUCKETS)
    for v in s:
        hist[bucket_index(v)] += 1
    mean = sum(s) / len(s) if s else None
    r = lambda x: None if x is None else round(x, 2)
    return {
        "source": source,
        "frames": frames,
        "intervals": len(s),
        "idleGaps": idle,
        "meanMs": r(mean), "p50Ms": r(pct(s, 50)), "p95Ms": r(pct(s, 95)), "p99Ms": r(pct(s, 99)),
        "fps": r(1000.0 / mean) if mean else None,
        "fpsWall": r(frames / window_s) if window_s else None,
        "windowS": r(window_s),
        "histogram": {f"{b:g}ms": n for b, n in zip(BUCKETS, hist)},
        "verdict": verdict(s, frames),
    }


def expand_bins(hist):
    out = []
    for ms, n in hist.items():
        out.extend([ms + 0.5] * n)  # 1 ms bin [k, k+1) -> midpoint
    return out


def measure(adb, seconds, pattern=DEFAULT_LAYER, poll=0.4, clock=time.monotonic, sleep=time.sleep):
    """adb(args_list)->str runs `adb shell <args>`. Returns result dict."""
    sf = ["dumpsys", "SurfaceFlinger"]
    adb(sf + ["--timestats", "-clear", "-enable"])
    t0 = clock()
    seen, layer, layers_seen, period = set(), None, [], None
    while clock() - t0 < seconds:
        names = find_layers(adb(sf + ["--list"]), pattern)
        # layer names change on session restart: always re-resolve, keep the best
        layer = None
        for cand in names:
            p, ts = parse_latency(adb(sf + ["--latency", cand]))
            if cand not in layers_seen:
                layers_seen.append(cand)
            period = p or period
            if ts:
                layer = cand
                seen.update(ts)
                break
        sleep(poll)
    elapsed = clock() - t0
    dump = adb(sf + ["--timestats", "-dump"])
    adb(sf + ["--timestats", "-disable"])
    ts_frames, ts_hist = parse_timestats(dump, pattern)
    if len(seen) >= MIN_FRAMES:
        res = summarize(intervals_from_ts(seen), len(seen), elapsed, "latency")
    elif ts_frames:
        res = summarize(expand_bins(ts_hist), ts_frames, elapsed, "timestats")
    else:
        res = summarize([], 0, elapsed, "none")
    res["layers"] = layers_seen
    res["refreshPeriodNs"] = period
    return res


def make_adb(serial):
    base = ["adb"] + (["-s", serial] if serial else [])

    def run(args):
        cmd = " ".join(shlex.quote(a) for a in args)
        p = subprocess.run(base + ["shell", cmd], capture_output=True, timeout=120)
        return p.stdout.decode(errors="replace")
    return run


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("seconds", nargs="?", type=float, default=10.0)
    ap.add_argument("-s", "--serial")
    ap.add_argument("--layer", default=DEFAULT_LAYER, help="regex matched against `--list` names")
    a = ap.parse_args(argv)
    res = measure(make_adb(a.serial), a.seconds, a.layer)
    print(json.dumps(res, indent=1))
    return 0 if res["verdict"] != "no-frames" else 1


if __name__ == "__main__":
    sys.exit(main())
