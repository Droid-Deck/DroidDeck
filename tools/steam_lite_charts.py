#!/usr/bin/env python3
"""Charts for docs/development/steam-lite-runtime.md from tools/steam_lite_bench.py output.

    tools/steam_lite_charts.py RESULTS_DIR OUT_DIR

RESULTS_DIR holds one folder per device (device.json, cycle runs with samples.jsonl and events.json,
ablation *.jsonl). Every chart shows each run as a point beside the median bar; axes start at zero.
"""

from __future__ import annotations

import json
import statistics
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

sys.path.insert(0, str(Path(__file__).resolve().parent))
import steam_lite_bench as bench  # noqa: E402

NOT_CLIENT = bench.NOT_CLIENT

ORDER = ["stock", "1", "2", "3", "l1", "softcomp", "softcomp+l1", "gc", "moderate", "critical", "pause", "freeze",
         "gc+critical"]
NAMES = {"stock": "stock", "1": "L1 conservative", "2": "L2 balanced", "3": "L3 aggressive", "l1": "L1",
         "softcomp": "soft compositing", "softcomp+l1": "soft compositing + L1"}


def runs(device_dir: Path) -> list[dict]:
    rows = []
    for events in sorted(device_dir.glob("*/events.json")):
        if json.loads(events.read_text()).get("kind") in ("oom", "flags"):
            continue
        try:
            samples, ev = bench.load(events.parent)
        except (OSError, ValueError):
            continue
        if samples:
            row = bench.summarize(samples, ev)
            row["run"] = events.parent.name
            row["app"] = ev.get("app")
            rows.append(row)
    return rows


def grouped(ax, rows, keys, labels, unit, order=None):
    profiles = [p for p in (order or ORDER) if any(r["profile"] == p for r in rows)]
    width = 0.8 / len(keys)
    for k, (key, label) in enumerate(zip(keys, labels)):
        xs, med = [], []
        for i, p in enumerate(profiles):
            values = [r[key] for r in rows if r["profile"] == p and r.get(key) is not None]
            x = i + (k - (len(keys) - 1) / 2) * width
            if values:
                med.append(statistics.median(values))
                xs.append(x)
                ax.scatter([x] * len(values), values, s=12, color="black", zorder=3)
        ax.bar(xs, med, width * 0.9, label=label, zorder=2)
    ax.set_xticks(range(len(profiles)))
    ax.set_xticklabels([NAMES.get(p, p) for p in profiles], fontsize=8)
    ax.set_ylim(bottom=0)
    ax.set_ylabel(unit)
    ax.grid(axis="y", alpha=0.3, zorder=0)
    ax.legend(fontsize=7)


def chart_cycles(device: str, rows: list[dict], out: Path) -> list[Path]:
    made = []
    for app in sorted({r["app"] for r in rows}):
        mine = [r for r in rows if r["app"] == app]
        n = len(mine)
        fig, axes = plt.subplots(2, 3, figsize=(15, 8))
        fig.suptitle("%s, app %s: stock vs profiles (%d runs; bars = median, dots = runs)" % (device, app, n))
        grouped(axes[0][0], mine, ["idle_pss_mib", "game_pss_mib", "back_pss_mib"],
                ["library idle", "game in front", "back in library"], "client tree PSS (MiB)")
        axes[0][0].set_title("1. Steam process-tree PSS", fontsize=9)
        grouped(axes[0][1], mine, ["launch_pss_peak_mib", "game_swappss_mib"], ["peak during launch", "SwapPss in game"],
                "MiB")
        axes[0][1].set_title("2. launch peak PSS and SwapPss", fontsize=9)
        grouped(axes[0][2], mine, ["idle_web_cpu_pct", "game_web_cpu_pct", "game_lite_cpu_pct"],
                ["web helper, library idle", "web helper, game in front", "supervisor, game in front"],
                "% of one core")
        axes[0][2].set_title("3. idle and hidden CPU", fontsize=9)
        grouped(axes[1][0], mine, ["fps"], ["on-screen FPS"], "FPS")
        axes[1][0].set_title("4a. game FPS (SurfaceFlinger)", fontsize=9)
        grouped(axes[1][1], mine, ["frame_p95_ms", "frame_p99_ms"], ["p95", "p99"], "present-to-present (ms)")
        axes[1][1].set_title("4b. frame time", fontsize=9)
        grouped(axes[1][2], mine, ["launch_s", "overlay_open_median_s"], ["game launch", "overlay open (median)"],
                "seconds")
        axes[1][2].set_title("5. launch and overlay latency", fontsize=9)
        fig.tight_layout()
        path = out / ("%s-%s-cycles.svg" % (device, app))
        fig.savefig(path)
        plt.close(fig)
        made.append(path)
    return made


def chart_ablation(device: str, device_dir: Path, out: Path) -> list[Path]:
    made = []
    for path in sorted(device_dir.glob("ablate-*.jsonl")):
        items = [json.loads(x) for x in path.read_text().splitlines() if x.strip()]
        rows, label = [], None
        for item in items:
            if "processes" in item:
                total = sum(r.get("Pss") or 0 for r in item["processes"] if r["role"] not in NOT_CLIENT)
                renderer = sum(r.get("Pss") or 0 for r in item["processes"] if r["role"] == "renderer")
                rows.append((label, total / 1024, renderer / 1024, sum((item.get("gpu") or {}).values()) / 1048576))
            elif "label" in item and "result" not in item:
                label = item["label"]
        actions, deltas = [], {}
        for i, (lab, total, renderer, gpu) in enumerate(rows):
            if lab and lab.startswith("before "):
                action = lab[7:]
                after = [r for r in rows[i + 1:] if r[0] and r[0].startswith("after %s +" % action)]
                if after:
                    last = after[-1]
                    actions.append(action)
                    deltas.setdefault(action, []).append((last[1] - total, last[2] - renderer, last[3] - gpu))
        if not actions:
            continue
        uniq = list(dict.fromkeys(actions))
        fig, ax = plt.subplots(figsize=(8, 4))
        width = 0.27
        for k, (idx, name) in enumerate(((0, "client tree PSS"), (1, "renderer PSS"), (2, "KGSL GPU"))):
            meds = [statistics.median([d[idx] for d in deltas[a]]) for a in uniq]
            xs = [i + (k - 1) * width for i in range(len(uniq))]
            ax.bar(xs, meds, width * 0.9, label=name)
            for x, a in zip(xs, uniq):
                ax.scatter([x] * len(deltas[a]), [d[idx] for d in deltas[a]], s=10, color="black", zorder=3)
        ax.axhline(0, color="black", linewidth=0.8)
        ax.set_xticks(range(len(uniq)))
        ax.set_xticklabels(uniq)
        ax.set_ylabel("change after the last wait (MiB; negative = freed)")
        ax.set_title("6. %s %s: each intervention alone" % (device, path.stem[7:]), fontsize=9)
        ax.legend(fontsize=7)
        ax.grid(axis="y", alpha=0.3)
        fig.tight_layout()
        target = out / ("%s-%s.svg" % (device, path.stem))
        fig.savefig(target)
        plt.close(fig)
        made.append(target)
    return made


COLORS = {"stock": "#1f77b4", "1": "#ff7f0e", "2": "#2ca02c", "3": "#d62728", "softcomp": "#9467bd",
          "softcomp+1": "#8c564b", "pageout": "#e377c2", "zramfix": "#17becf"}


def chart_oom(device: str, device_dir: Path, out: Path) -> list[Path]:
    trials = []
    for events_path in sorted(device_dir.glob("oom-*/events.json")):
        events = json.loads(events_path.read_text())
        events["profile"] = events.get("arm") or events["profile"]
        try:
            samples, _ = bench.load(events_path.parent)
        except (OSError, ValueError):
            samples = []
        trials.append((events, samples))
    if not trials:
        return []
    by_app = {}
    for events, samples in trials:
        by_app.setdefault(events["app"], []).append((events, samples))
    made = []
    for app, group in by_app.items():
        fig, (ax, bx) = plt.subplots(1, 2, figsize=(14, 5), gridspec_kw={"width_ratios": [3, 1]})
        for events, samples in group:
            load = events.get("load_t")
            if load is None or not samples or not events.get("loaded"):
                continue
            xs = [s["t"] - load for s in samples]
            ys = [(s["memory"].get("MemAvailable") or 0) / 1024 for s in samples]
            color = COLORS.get(events["profile"], "gray")
            ax.plot(xs, ys, color=color, alpha=0.8, linewidth=1,
                    label=NAMES.get(events["profile"], events["profile"]))
            if not events.get("survived"):
                ax.scatter([xs[-1]], [ys[-1]], marker="x", s=60, color=color, zorder=3)
        handles, labels = ax.get_legend_handles_labels()
        unique = dict(zip(labels, handles))
        ax.legend(unique.values(), unique.keys(), fontsize=7)
        ax.axvline(0, color="black", linewidth=0.8)
        ax.set_ylim(bottom=0)
        ax.set_xlim(-60, 45)
        ax.set_xlabel("seconds from pressing Continue (x = last sample before the session was killed)")
        ax.set_ylabel("MemAvailable (MiB)")
        ax.set_title("%s, app %s: memory through a save load" % (device, app), fontsize=9)
        profiles = list(dict.fromkeys([p for p in ORDER if any(e["profile"] == p for e, _ in group)] +
                                      [e["profile"] for e, _ in group]))
        valid = [(e, x) for e, x in group if e.get("loaded")]
        survived = [sum(1 for e, _ in valid if e["profile"] == p and e.get("survived")) for p in profiles]
        totals = [sum(1 for e, _ in valid if e["profile"] == p) for p in profiles]
        bx.bar(range(len(profiles)), totals, color="#dddddd", label="trials that loaded the save")
        bx.bar(range(len(profiles)), survived, color="#2ca02c", label="survived 90 s")
        bx.set_xticks(range(len(profiles)))
        bx.set_xticklabels([NAMES.get(p, p) for p in profiles], fontsize=7)
        bx.set_ylabel("trials")
        bx.legend(fontsize=7)
        bx.set_title("session survival", fontsize=9)
        fig.tight_layout()
        target = out / ("%s-%s-oom.svg" % (device, app))
        fig.savefig(target)
        plt.close(fig)
        made.append(target)
    return made


def chart_flags(device: str, device_dir: Path, out: Path) -> list[Path]:
    rows = []
    for events_path in sorted(device_dir.glob("**/flags-*/events.json")):
        events = json.loads(events_path.read_text())
        lines = (events_path.parent / "samples.jsonl").read_text().splitlines()
        for line in lines:
            item = json.loads(line)
            roles = {}
            for r in item["processes"]:
                if r["role"] not in NOT_CLIENT:
                    roles[r["role"]] = roles.get(r["role"], 0) + (r.get("Pss") or 0)
            rows.append({"profile": events["variant"], "total": sum(roles.values()) / 1024,
                         "renderer": roles.get("renderer", 0) / 1024, "browser": roles.get("browser", 0) / 1024,
                         "gpu": sum((item.get("gpu") or {}).values()) / 1048576,
                         "run": events_path.parent.name})
    if not rows:
        return []
    per_run = {}
    for r in rows:
        per_run.setdefault(r["run"], []).append(r)
    medians = [{"profile": rs[0]["profile"], **{k: statistics.median(x[k] for x in rs)
                                                  for k in ("total", "renderer", "browser", "gpu")}}
               for rs in per_run.values()]
    variants = list(dict.fromkeys(r["profile"] for r in medians))
    fig, ax = plt.subplots(figsize=(10, 4.5))
    grouped(ax, medians, ["total", "renderer", "browser", "gpu"],
            ["client tree PSS", "renderer PSS", "browser PSS", "KGSL GPU (not in PSS)"], "MiB", order=variants)
    ax.set_title("7. %s: library idle, web helper flag variants (bars = median of runs, dots = runs)" % device,
                 fontsize=9)
    fig.tight_layout()
    target = out / ("%s-flags.svg" % device)
    fig.savefig(target)
    plt.close(fig)
    return [target]


def steady_runs(device_dir: Path) -> list[dict]:
    rows = []
    for path in sorted(device_dir.glob("steady/*/run.json")):
        run = json.loads(path.read_text())
        game = run.get("game") or {}
        opens = [o for o in run.get("overlay_open_s") or [] if o is not None]
        rows.append({"profile": "stock" if run["level"] == "stock" else run["level"], "run": path.parent.name,
                     "resident": game.get("pss"), "swapped": game.get("swappss"),
                     "footprint": (game.get("pss") or 0) + (game.get("swappss") or 0) if game else None,
                     "renderer": game.get("renderer"), "gpu": game.get("gpu"), "avail": game.get("avail"),
                     "idle_gpu": (run.get("idle") or {}).get("gpu"), "cpu": run.get("web_cpu_pct"),
                     "overlay": statistics.median(opens) if opens else None,
                     "passes": [h.get("outcome") for h in (run.get("status") or {}).get("history", [])]})
    return rows


def chart_steady(device: str, device_dir: Path, out: Path) -> list[Path]:
    rows = steady_runs(device_dir)
    if not rows:
        return []
    fig, axes = plt.subplots(1, 3, figsize=(15, 4.5))
    grouped(axes[0], rows, ["resident", "footprint", "renderer"],
            ["resident PSS", "resident + SwapPss", "renderer PSS"], "MiB")
    axes[0].set_title("Steam tree with the game in front (+80 s)", fontsize=9)
    grouped(axes[1], rows, ["gpu", "avail"], ["KGSL GPU (not in PSS)", "MemAvailable"], "MiB")
    axes[1].set_title("GPU memory and MemAvailable in game", fontsize=9)
    grouped(axes[2], rows, ["cpu", "overlay"], ["web helper CPU (% of one core)", "overlay open (s, tool-bound)"], "")
    axes[2].set_title("hidden UI CPU and overlay latency", fontsize=9)
    fig.suptitle("%s: Bully in front, one fresh session per run (bars = median, dots = runs)" % device, fontsize=10)
    fig.tight_layout()
    target = out / ("%s-steady.svg" % device)
    fig.savefig(target)
    plt.close(fig)
    return [target]


def library_runs(device_dir: Path) -> list[dict]:
    rows = []
    for path in sorted(device_dir.glob("ui/*/run.json")):
        run = json.loads(path.read_text())
        rows.append({"profile": "soft compositing" if run["soft"] == "1" else "GPU compositing",
                     "cpu": run.get("idle_cpu"), "pss": run.get("idle_pss"), "gpu": run.get("idle_gpu"),
                     "run": path.parent.name})
    return rows


def chart_burst(device: str, device_dir: Path, out: Path) -> list[Path]:
    made = []
    for path in sorted(device_dir.glob("burst-*.jsonl")):
        rows = [json.loads(x) for x in path.read_text().splitlines() if x.strip()]
        if not rows:
            continue
        t0 = rows[0]["t"]
        xs = [r["t"] - t0 for r in rows]
        game = []
        steam = []
        for r in rows:
            game.append(max((p[4] for p in r["top"] if p[1].lower().startswith("arcade")), default=0))
            steam.append(sum(p[4] for p in r["top"] if p[1].startswith("steamweb")))
        fig, ax = plt.subplots(figsize=(10, 4.5))
        ax.plot(xs, game, label="game KGSL (GPU memory)")
        ax.plot(xs, steam, label="Steam web helper KGSL")
        ax.plot(xs, [r["mem"]["Unevictable"] for r in rows], label="Unevictable (system)")
        ax.plot(xs, [r["mem"]["MemAvailable"] for r in rows], label="MemAvailable")
        ax.scatter([xs[-1]], [rows[-1]["mem"]["MemAvailable"]], marker="x", s=70, color="black", zorder=3,
                   label="last sample before lmkd killed the session")
        ax.set_ylim(bottom=0)
        ax.set_xlabel("seconds from pressing Continue")
        ax.set_ylabel("MiB")
        ax.grid(alpha=0.3)
        ax.legend(fontsize=7)
        ax.set_title("%s %s: what fills memory while the save loads (0.5 s samples)" % (device, path.stem[6:]),
                     fontsize=9)
        fig.tight_layout()
        target = out / ("%s-%s.svg" % (device, path.stem))
        fig.savefig(target)
        plt.close(fig)
        made.append(target)
    return made


def more_tables(device: str, device_dir: Path) -> str:
    out = []
    rows = steady_runs(device_dir)
    if rows:
        out.append("**%s: Bully in front, steady state** (one fresh session per run; game +80 s; MiB unless noted)\n"
                   % device)
        out.append("| Run | Resident PSS | SwapPss | Renderer | KGSL | MemAvailable | Web helper CPU % | "
                   "Overlay open s (median, tool-bound) | Supervisor pass |")
        out.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- |")
        for r in rows:
            passes = "; ".join(", ".join(sorted(k for k in o if k != "ms")) for o in r["passes"]) or "none (stock)"
            out.append("| %s | %s | %s | %s | %s | %s | %s | %s | %s |" % (
                r["run"], r["resident"], r["swapped"], r["renderer"], r["gpu"], r["avail"], r["cpu"],
                r["overlay"], passes))
        out.append("")
    rows = library_runs(device_dir)
    if rows:
        out.append("**%s: settled library (+90 to +110 s after start)**\n" % device)
        out.append("| Run | Compositing | Web helper CPU % | Client tree PSS (MiB) | KGSL (MiB) |")
        out.append("| --- | --- | --- | --- | --- |")
        for r in rows:
            out.append("| %s | %s | %s | %s | %s |" % (r["run"], r["profile"], r["cpu"], r["pss"], r["gpu"]))
        out.append("")
    return "\n".join(out)


def fmt(values: list[float]) -> str:
    if not values:
        return "n/a"
    return "%.0f (%.0f-%.0f)" % (statistics.median(values), min(values), max(values))


def tables(device: str, device_dir: Path) -> str:
    """Markdown tables of every measurement in device_dir, medians with the full range."""
    out = []
    flags = {}
    for events_path in sorted(device_dir.glob("**/flags-*/events.json")):
        events = json.loads(events_path.read_text())
        rows = [json.loads(x) for x in (events_path.parent / "samples.jsonl").read_text().splitlines() if x.strip()]
        if not rows:
            continue
        def med(key):
            vals = []
            for item in rows:
                if key == "gpu":
                    vals.append(sum((item.get("gpu") or {}).values()) / 1048576)
                else:
                    vals.append(sum(r.get("Pss") or 0 for r in item["processes"] if r["role"] not in NOT_CLIENT
                                    and (key == "total" or r["role"] == key)) / 1024)
            return statistics.median(vals)
        flags.setdefault(events["variant"], []).append({k: med(k) for k in ("total", "renderer", "browser", "gpu")})
    if flags:
        out.append("**%s: library idle per web helper variant** (one fresh session per run, median of its samples; "
                   "cells are median (min-max) over runs, MiB)\n" % device)
        out.append("| Variant | Runs | Client tree PSS | Renderer PSS | Browser PSS | KGSL GPU (not in PSS) |")
        out.append("| --- | --- | --- | --- | --- | --- |")
        for variant, runs_ in flags.items():
            out.append("| %s | %d | %s | %s | %s | %s |" % (variant, len(runs_), *[fmt([r[k] for r in runs_])
                                                                          for k in ("total", "renderer", "browser", "gpu")]))
        out.append("")
    trials = {}
    for events_path in sorted(device_dir.glob("oom-*/events.json")):
        events = json.loads(events_path.read_text())
        trials.setdefault(events.get("arm") or events["profile"], []).append(events)
    if trials:
        out.append("**%s: save load in app %s** (fresh session per trial; a trial counts once the load started)\n"
                   % (device, next(iter(trials.values()))[0]["app"]))
        out.append("| Arm | Trials loaded | Survived 90 s | Killed (lmkd) | Seconds from Continue to kill |")
        out.append("| --- | --- | --- | --- | --- |")
        for arm, group in trials.items():
            valid = [e for e in group if e.get("loaded")]
            killed = [e for e in valid if not e.get("survived")]
            out.append("| %s | %d of %d | %d | %d | %s |" % (
                arm, len(valid), len(group), sum(1 for e in valid if e.get("survived")), len(killed),
                fmt([e["died_after_s"] for e in killed if e.get("died_after_s") is not None])))
        out.append("")
    for path in sorted(device_dir.glob("ablate-*.jsonl")):
        items = [json.loads(x) for x in path.read_text().splitlines() if x.strip()]
        label, before, rows = None, {}, []
        for item in items:
            if "processes" in item:
                total = sum(r.get("Pss") or 0 for r in item["processes"] if r["role"] not in NOT_CLIENT) / 1024
                renderer = sum(r.get("Pss") or 0 for r in item["processes"] if r["role"] == "renderer") / 1024
                gpu = sum((item.get("gpu") or {}).values()) / 1048576
                if label and label.startswith("before "):
                    before[label[7:]] = (total, renderer, gpu)
                elif label and label.startswith("after "):
                    action, wait = label[6:].rsplit(" +", 1)
                    if action in before:
                        b = before[action]
                        rows.append((action, wait, total - b[0], renderer - b[1], gpu - b[2]))
            elif "label" in item and "result" not in item:
                label = item["label"]
        if rows:
            out.append("**%s %s: one intervention at a time** (change from the sample just before it, MiB; "
                       "negative = freed)\n" % (device, path.stem[7:]))
            out.append("| Action | After | Client tree PSS | Renderer PSS | KGSL GPU |")
            out.append("| --- | --- | --- | --- | --- |")
            for action, wait, t, r, g in rows:
                out.append("| %s | %s | %+.1f | %+.1f | %+.1f |" % (action, wait, t, r, g))
            out.append("")
    return "\n".join(out)


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    root, out = Path(argv[0]), Path(argv[1])
    out.mkdir(parents=True, exist_ok=True)
    for device_dir in sorted(p for p in root.iterdir() if p.is_dir()):
        rows = runs(device_dir)
        made = chart_cycles(device_dir.name, rows, out) if rows else []
        made += chart_ablation(device_dir.name, device_dir, out)
        made += chart_oom(device_dir.name, device_dir, out)
        made += chart_flags(device_dir.name, device_dir, out)
        made += chart_steady(device_dir.name, device_dir, out)
        made += chart_burst(device_dir.name, device_dir, out)
        for path in made:
            print(path)
        text = "\n".join(t for t in (tables(device_dir.name, device_dir), more_tables(device_dir.name, device_dir)) if t)
        if text:
            (out / ("%s-tables.md" % device_dir.name)).write_text(text + "\n")
            print(out / ("%s-tables.md" % device_dir.name))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
