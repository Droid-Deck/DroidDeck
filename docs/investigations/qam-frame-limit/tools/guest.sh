#!/bin/bash
# Run a shell command in the running DroidDeck guest and print its output.
# Needs Setup > Diagnostics > Debugging tools on. GT sets the timeout (default 60 s).
: "${ADB_SERIAL:?set ADB_SERIAL}"
CTL="$(git rev-parse --show-toplevel)/tools/droiddeckctl"
python3 "$CTL" guest --timeout "${GT:-60}" -- sh -c "$1" 2>/dev/null | python3 -c 'import json,sys
t=sys.stdin.read(); t=t[t.index("{"):]; d=json.loads(t)
print(d.get("stdout",{}).get("text",""),end=""); print(d.get("stderr",{}).get("text",""),end="",file=sys.stderr)
if not d.get("ok"): print(json.dumps(d)[:500])'
