"""Chrome memory-infra dump from the web helper's browser target: allocator sizes per process (MiB)."""
import json
import os
import runpy
import select
import sys
import time

m = runpy.run_path("/usr/local/bin/droiddeck-steam-lite")
agent = m["load_agent"]()
agent.RESPONSE_CAP = 256 << 20
client = m["Client"](agent, os.path.join(os.environ.get("DD_LAUNCH_DIR", ""), "agent"))
url = client.browser()["webSocketDebuggerUrl"]
ws = agent.WebSocket(url, 10)
ws.s.settimeout(30)
seq = [0]


def send(method, params=None):
    seq[0] += 1
    ws.send(json.dumps({"id": seq[0], "method": method, "params": params or {}}))
    return seq[0]


def pump(until, limit=60):
    events = []
    deadline = time.monotonic() + limit
    while time.monotonic() < deadline:
        if not ws.buffer and not select.select([ws.s], [], [], 1)[0]:
            continue
        msg = json.loads(ws.recv())
        events.append(msg)
        if until(msg):
            return events
    raise SystemExit("timed out")


send("Tracing.start", {"traceConfig": {"includedCategories": ["disabled-by-default-memory-infra"],
                                       "memoryDumpConfig": {"triggers": []}}, "transferMode": "ReportEvents"})
pump(lambda msg: msg.get("id") == seq[0])
dump_id = send("Tracing.requestMemoryDump", {"levelOfDetail": "detailed"})
pump(lambda msg: msg.get("id") == dump_id, 60)
send("Tracing.end")
events = pump(lambda msg: msg.get("method") == "Tracing.tracingComplete", 120)
trace = [e for msg in events if msg.get("method") == "Tracing.dataCollected" for e in msg["params"]["value"]]
names = {}
for e in trace:
    if e.get("ph") == "M" and e.get("name") == "process_name":
        names[e["pid"]] = e["args"].get("name")
depth = int(sys.argv[1]) if len(sys.argv) > 1 else 2
out = {}
for e in trace:
    if e.get("ph") != "v":
        continue
    dumps = ((e.get("args") or {}).get("dumps") or {}).get("allocators") or {}
    proc = "%s:%s" % (e["pid"], names.get(e["pid"], "?"))
    rows = {}
    for path, d in dumps.items():
        if path.count("/") >= depth or path.startswith("global/"):
            continue
        size = (d.get("attrs") or {}).get("size")
        if size:
            rows[path] = round(int(size["value"], 16) / 1048576, 1)
    out[proc] = dict(sorted(((k, v) for k, v in rows.items() if v >= 1), key=lambda kv: -kv[1])[:40])
print(json.dumps(out, indent=1))
