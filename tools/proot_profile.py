"""Read cumulative PRoot handler counters from a session log without double counting snapshots."""
from __future__ import annotations

import json
from collections import deque
from pathlib import Path

PREFIX = "proot-profile "


def report(path: Path, sort: str = "wall", limit: int = 20) -> dict:
    latest = {}
    starts = set()
    samples = deque(maxlen=128)
    malformed = 0
    with path.open(errors="replace") as stream:
        for line in stream:
            if PREFIX not in line:
                continue
            try:
                row = json.loads(line.split(PREFIX, 1)[1])
                identity = (int(row["tracer"]), int(row["id"]))
                if row["type"] == "start":
                    starts.add(identity)
                elif row["type"] in {"total", "slow"}:
                    if row["stage"] not in {"enter", "exit"} or not isinstance(row["syscall"], str):
                        raise ValueError("invalid syscall/stage")
                    fields = ("calls", "wall_ns", "cpu_ns", "max_ns") if row["type"] == "total" else ("wall_ns", "cpu_ns", "pid")
                    if any(type(row[key]) is not int or row[key] < 0 for key in fields):
                        raise ValueError("invalid counter")
                    if row["type"] == "total":
                        latest[identity + (row["syscall"], row["stage"])] = row
                    else:
                        samples.append(row)
            except (ValueError, TypeError, KeyError):
                malformed += 1
    groups = {}
    for (_, _, syscall, stage), row in latest.items():
        total = groups.setdefault((syscall, stage), dict(syscall=syscall, stage=stage, calls=0, wall_ns=0, cpu_ns=0, max_ns=0))
        for key in ("calls", "wall_ns", "cpu_ns"):
            total[key] += row[key]
        total["max_ns"] = max(total["max_ns"], row["max_ns"])
    for row in groups.values():
        row["wallMs"] = round(row["wall_ns"] / 1_000_000, 3)
        row["cpuMs"] = round(row["cpu_ns"] / 1_000_000, 3)
        row["meanUs"] = round(row["wall_ns"] / max(row["calls"], 1) / 1000, 3)
        row["maxUs"] = round(row["max_ns"] / 1000, 3)
    rows = sorted(groups.values(), key=lambda row: row[{"wall": "wall_ns", "cpu": "cpu_ns", "calls": "calls"}[sort]], reverse=True)
    return dict(ok=True, command="proot-profile", scope="syscall-handler",
                note="Handler wall time includes tracer descheduling and filesystem waits; it excludes tracee kernel time and wake-up/queue delay before the handler. Counts are handler visits, including chained calls, not unique syscalls.",
                path=str(path), tracers=len(starts | {key[:2] for key in latest}),
                handlerCalls=sum(row["calls"] for row in rows),
                handlerWallNs=sum(row["wall_ns"] for row in rows),
                handlerCpuNs=sum(row["cpu_ns"] for row in rows),
                malformedRecords=malformed, totals=rows[:limit], slowSamples=list(samples),
                hasCounters=bool(latest))
