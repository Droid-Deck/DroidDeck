"""Snapshot a process's KGSL allocations via debugfs and summarize them by size."""
import collections
import json
import os
import subprocess
import sys

SERIAL = os.environ.get("ADB_SERIAL", "")


def snap(pid):
    out = subprocess.run(["adb", "-s", SERIAL, "shell", "su -c 'cat /sys/kernel/debug/kgsl/proc/%s/mem'" % pid],
                         capture_output=True, text=True).stdout
    rows = {}
    for line in out.splitlines()[1:]:
        f = line.split()
        if len(f) >= 8 and f[3].isdigit():
            rows[int(f[3])] = {"size": int(f[2]), "flags": f[4], "type": f[5], "usage": f[6]}
    return rows


def summary(rows):
    by = collections.Counter()
    count = collections.Counter()
    for r in rows.values():
        by[r["size"]] += r["size"]
        count[r["size"]] += 1
    total = sum(by.values())
    top = [(s, count[s], round(b / 1048576, 1)) for s, b in by.most_common(12)]
    return {"n": len(rows), "mib": round(total / 1048576, 1), "top_sizes": top}


if __name__ == "__main__":
    pid, out = sys.argv[1], sys.argv[2]
    rows = snap(pid)
    json.dump(rows, open(out, "w"))
    print(json.dumps(summary(rows)))
