#!/usr/bin/env python3
"""Charts for docs/development/steam-webhelper-gpu-memory.md from its raw data.

    tools/steam_webhelper_charts.py docs/development/evidence/steam-webhelper/data docs/development/evidence/steam-webhelper
"""

from __future__ import annotations

import json
import statistics
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

SERIES = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100"]
INK, MUTED, GRID = "#0b0b0b", "#52514e", "#e4e3df"
CONDITIONS = [("stock", "shipped", "GPU compositing"), ("stock", "no-backdrop", "GPU, no backdrop blur"),
              ("softcomp", "shipped", "software compositing"), ("softcomp", "no-backdrop", "software, no backdrop blur")]
SCENARIOS = [("scroll", "library grid, scripted scroll"), ("pad", "library grid, d-pad"), ("menu", "main menu open/close")]


def style(ax, ylabel):
    ax.set_ylabel(ylabel, color=MUTED, fontsize=8)
    ax.tick_params(colors=MUTED, labelsize=7, length=0)
    ax.grid(axis="y", color=GRID, linewidth=0.6)
    ax.set_axisbelow(True)
    for side in ("top", "right", "left"):
        ax.spines[side].set_visible(False)
    ax.spines["bottom"].set_color(GRID)


def smooth_rows(data: Path) -> list[dict]:
    rows = []
    for path in sorted((data / "smooth").glob("*/run.json")):
        run = json.loads(path.read_text())
        for entry in run["rounds"]:
            for key, _ in SCENARIOS:
                rows.append({"arm": run["arm"], "style": entry["style"], "scenario": key, "session": path.parent.name,
                             "fps": entry[key]["fps"], "p95": entry[key]["p95"], "raw": entry[key].get("raw")})
    return rows


def chart_smooth(data: Path, out: Path) -> Path:
    rows = smooth_rows(data)
    fig, axes = plt.subplots(1, 3, figsize=(12, 3.6), sharey=True)
    for ax, (key, title) in zip(axes, SCENARIOS):
        for i, (arm, styl, label) in enumerate(CONDITIONS):
            values = [r["fps"] for r in rows if (r["arm"], r["style"], r["scenario"]) == (arm, styl, key)]
            if not values:
                continue
            ax.bar(i, statistics.median(values), width=0.7, color=SERIES[i], edgecolor="white", linewidth=2,
                   label=label if key == "scroll" else None)
            ax.scatter([i] * len(values), values, s=14, color=INK, zorder=3)
            ax.text(i, max(values) + 2.5, "%.0f" % statistics.median(values), ha="center",
                    fontsize=7, color=INK)
        ax.set_title(title, fontsize=9, color=INK)
        ax.set_xticks([])
        style(ax, "frames per second (Steam's own)" if key == "scroll" else "")
    fig.legend(loc="lower center", ncol=4, frameon=False, fontsize=8)
    fig.suptitle("Steam Big Picture frame rate on the Duo, 3 fresh sessions per arm (bars = median, dots = sessions)",
                 fontsize=10, color=INK)
    fig.tight_layout(rect=(0, 0.08, 1, 0.94))
    path = out / "smoothness.svg"
    fig.savefig(path)
    plt.close(fig)
    return path


def chart_trace(data: Path, out: Path) -> Path:
    rows = [r for r in smooth_rows(data) if r["scenario"] == "scroll" and r["style"] == "shipped" and r["raw"]]
    fig, axes = plt.subplots(2, 1, figsize=(11, 4.6), sharex=True, sharey=True)
    for ax, (arm, label, color) in zip(axes, (("stock", "GPU compositing", SERIES[0]),
                                              ("softcomp", "software compositing", SERIES[2]))):
        run = next(r for r in rows if r["arm"] == arm)
        frames = run["raw"]
        ax.plot([f / 1000 for f in frames[1:]], [b - a for a, b in zip(frames, frames[1:])], color=color,
                linewidth=1.2)
        ax.set_title("%s (%s): %.1f fps" % (label, run["session"], run["fps"]), fontsize=9, color=INK, loc="left")
        style(ax, "frame interval (ms)")
        ax.set_ylim(0, 400)
    axes[1].set_xlabel("seconds into a 12 s scroll of the 909-game grid", color=MUTED, fontsize=8)
    fig.tight_layout()
    path = out / "scroll-frame-intervals.svg"
    fig.savefig(path)
    plt.close(fig)
    return path


def chart_reclaim(data: Path, out: Path) -> Path | None:
    groups = [("reclaim-control-90s", "none", "Mesa as shipped", SERIES[1]),
              ("reclaim-patched", "none", "Mesa with patch 0006", SERIES[0])]
    labels = ["home", "after browsing", "game +5s", "game +15s", "game +45s", "game +90s"]
    fig, ax = plt.subplots(figsize=(8, 3.8))
    drawn = False
    for folder, arm, label, color in groups:
        for i, path in enumerate(sorted((data / folder).glob("%s-*.json" % arm))):
            run = json.loads(path.read_text())
            by = {s["label"]: s["kgsl_mib"] for s in run["samples"]}
            ax.plot(range(len(labels)), [by.get(lbl) for lbl in labels], color=color, linewidth=2, marker="o",
                    markersize=5, label=label if i == 0 else None)
            drawn = True
    if not drawn:
        plt.close(fig)
        return None
    ax.set_xticks(range(len(labels)))
    ax.set_xticklabels(labels, fontsize=8, color=MUTED)
    ax.set_ylim(0, None)
    style(ax, "web helper KGSL (MiB)")
    ax.legend(frameon=False, fontsize=8)
    ax.set_title("Steam's UI behind Sonic Mania: browse the library, launch, wait", fontsize=9, color=INK)
    fig.tight_layout()
    path = out / "webhelper-kgsl-in-game.svg"
    fig.savefig(path)
    plt.close(fig)
    return path


def chart_arcade(data: Path, out: Path) -> list[Path]:
    paths = []
    for name in ("default", "aa-off"):
        source = data / "arcade-paradise" / name / "kgsl.jsonl"
        if not source.exists():
            continue
        rows = [json.loads(line) for line in source.read_text().splitlines() if line.strip()]
        live = [r for r in rows if not r.get("gone")]
        fig, axes = plt.subplots(2, 1, figsize=(8, 4.6), sharex=True)
        t = [r["t"] for r in live]
        axes[0].plot(t, [r["kgsl_mib"] for r in live], color=SERIES[0], linewidth=2)
        style(axes[0], "game KGSL (MiB)")
        axes[0].set_ylim(0, None)
        axes[1].plot(t, [r["MemAvailable"] for r in live], color=SERIES[1], linewidth=2)
        style(axes[1], "MemAvailable (MiB)")
        axes[1].set_ylim(0, None)
        axes[1].set_xlabel("seconds from pressing Continue; the session was killed at the end of the line",
                           color=MUTED, fontsize=8)
        axes[0].set_title("Arcade Paradise loading a save on the Duo (anti-aliasing %s)"
                          % ("8x" if name == "default" else "off"), fontsize=9, color=INK)
        fig.tight_layout()
        path = out / ("arcade-paradise-load-%s.svg" % name)
        fig.savefig(path)
        plt.close(fig)
        paths.append(path)
    return paths


def main(argv: list[str]) -> int:
    data, out = Path(argv[1]), Path(argv[2])
    out.mkdir(parents=True, exist_ok=True)
    made = [chart_smooth(data, out), chart_trace(data, out), chart_reclaim(data, out)] + chart_arcade(data, out)
    for path in made:
        if path:
            print(path)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
