#!/usr/bin/env python3
"""Put Forza Horizon 4 into Horizon Life and report whether the room came up.

This is the one entry point for online verification. It replaces the pile of
one-off drivers that each knew a different fragment of the sequence, which is how
runs kept "losing" the menu: one driver pressed start four times, another assumed
the title screen, a third pressed A on whatever the cursor happened to sit on and
opened the exit confirmation instead.

The sequence, which is the only one that has worked:

    1. wake, pin the display awake, and make sure a session with a real window is up
    2. title screen  -> A
    3. welcome panel -> A
    4. back out of the garage (B twice) and let the world load
    5. start until the pause list is on screen
    6. right, then A: that is CONNECT TO HORIZON LIFE, and it drops back into the game
    7. wait, then start again, and read what PLAYER LIST says

Step 6 is the part that was missing for so long. The pause list opens on the
Horizon Life tab, so one right press lands on CONNECT TO HORIZON LIFE and A starts
the join. No amount of pressing A where the cursor already was would do it.

The run then answers two independent questions and prints both:

  * did the room come up -- read off the pause list, which says NOT AVAILABLE while
    the title is not in a session and lists players once it is;
  * what did the shim tell peers -- read off the uwp-host trace, which records the
    addresses it actually published, plus whether the RTA websocket and the
    name lookups went through.

Either answer alone is misleading. A room that says "not available" while the
published address is a stranger's is a different bug from one that says "not
available" with the right address out.

Runs inside WSL, next to the repo and droiddeckctl. The OCR step calls the Windows
OCR engine through powershell.exe, because that is the only OCR on this machine;
everything else is droiddeckctl and adb.

    tools/xbox/fh4-online-run.py                     # launch, join, report, tidy up
    tools/xbox/fh4-online-run.py --no-launch          # already in game: just join
    tools/xbox/fh4-online-run.py --keep               # leave the session running
    tools/xbox/fh4-online-run.py --out /tmp/fh4-run   # where the screenshots go
    tools/xbox/fh4-online-run.py --expect-dll build/uwp-host.dll

Exit status: 0 when the room came up and the published address is this device's,
1 when it did not, 2 when the run could not be carried out at all.
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
CTL = HERE.parent / "droiddeckctl"
OCR_PS1 = HERE / "fh4-ocr.ps1"

# The library id of Forza Horizon 4 (Xbox source), not a URL: droiddeckctl takes
# it to pick the right title out of a library that also holds Forza Horizon 6.
GAME = "13788888191970836480"
LAUNCHER = "com.droiddeck.launcher"

# Labels printed by the game. These are screen text, never network endpoints.
TITLE = ("START GAM", "START GEM", "ACCESSIBILITY")
PAUSE = ("HORIZON LIFE", "WORLD MAP", "PLAY ONLINE", "CONNECT TO HORIZON LIFE")
# Enough to know the pause list is on screen after a join. CONNECT TO HORIZON LIFE
# is deliberately not among them: once the room is up that item is gone.
LIST_MARKS = ("HORIZON LIFE", "WORLD MAP", "PLAY ONLINE", "PLAYER LIST")
NOT_IN_SESSION = ("NOT AVAILABLE", "CONNECT TO HORIZON LIFE")

# Trace markers written by the shim and the compatible sockets layer.
TRACE_IPV4 = re.compile(r"xbl_devaddr sda ipv4=(\d+\.\d+\.\d+\.\d+)")
TRACE_IPV6 = re.compile(r"xbl_devaddr sda ipv6=([0-9a-f:]{8,})")
TRACE_NET = re.compile(r"msg_ws connected ok|msg_ws ConnectAsync")
TRACE_STATUS = re.compile(r"-> HTTP (\d{3})")
TRACE_NAMEFAIL = re.compile(r"err=12007")
# The host of whichever URL the failing line mentions, without hardcoding any.
TRACE_HOST = re.compile(r"https?://([^/\s\"]+)")

# Where the shim writes its trace inside the guest, and where the launcher keeps
# the shim it loads. Both are launcher-internal paths, not endpoints.
TRACE_IN_GUEST = ("/data/user/0/com.droiddeck.launcher/files/linuxfs/root/.local/share/Steam/"
                  "steamapps/compatdata/3210475713/pfx/drive_c/uwp-host-trace.log")
DLL_IN_GUEST = ("/data/user/0/com.droiddeck.launcher/files/linuxfs/usr/local/lib/"
                "droiddeck-xbox/compat/uwp-host.dll")


class Run:
    """Everything the steps need: a serial, an adb, a place to put screenshots."""

    def __init__(self, args):
        self.args = args
        self.out = Path(args.out or tempfile.mkdtemp(prefix="fh4-online-run-"))
        self.out.mkdir(parents=True, exist_ok=True)
        self.step = 0
        self._win_temp = None

    # ---- plumbing -------------------------------------------------------

    def say(self, message):
        print(message, flush=True)

    def ddctl(self, *argv, timeout=240):
        cmd = [sys.executable, str(CTL)]
        if self.args.adb:
            cmd += ["--adb", self.args.adb]
        if self.args.serial:
            cmd += ["--serial", self.args.serial]
        return subprocess.run(cmd + [str(a) for a in argv],
                              capture_output=True, text=True, timeout=timeout)

    def adb(self, *argv, timeout=90):
        exe = self.args.adb or shutil.which("adb")
        if not exe:
            raise SystemExit("no adb on PATH; pass --adb or set ADB")
        cmd = [exe]
        if self.args.serial:
            cmd += ["-s", self.args.serial]
        return subprocess.run(cmd + [str(a) for a in argv],
                              capture_output=True, text=True, timeout=timeout)

    def pad(self, button):
        self.ddctl("input", "pad", button, timeout=90)

    def session(self):
        result = self.ddctl("state", timeout=120)
        start = result.stdout.find("{")
        if start < 0:
            return {}
        import json
        try:
            return json.loads(result.stdout[start:]).get("session") or {}
        except ValueError:
            return {}

    def running(self):
        """A session counts as running only when the title's window exists.

        Phase READY on its own is not enough: the session runner outlives the
        title, which leaves READY with an empty focusable-app list and a black
        screen. Waiting on the window is what catches that.
        """
        session = self.session()
        window = (session.get("focus") or {}).get("focusableApps") or []
        return bool(window), session.get("phase")

    def shot(self, name):
        self.step += 1
        path = self.out / ("%02d-%s.png" % (self.step, name))
        self.ddctl("screenshot", str(path), timeout=120)
        return path if path.exists() else None

    def ocr(self, path):
        """Read the screen. A failed OCR is reported, never treated as empty text.

        Two things make this more than one call:

          * OCR runs on Windows and WinRT cannot open a wsl.localhost path, so the
            screenshot is staged into Windows' own temp directory first;
          * the engine faults intermittently and returns nothing, and an empty
            string is indistinguishable from "this screen has no text" -- which is
            how earlier runs reported NO TITLE on a screen that plainly had one.
            Two attempts and an explicit failure marker keep that honest.
        """
        if path is None:
            return ""
        holder = self.windows_temp()
        if holder is None:
            self.say("  (no Windows temp directory; cannot read the screen)")
            return ""
        staged = str(Path(holder)) + os.sep + path.name
        copy = subprocess.run(
            ["powershell.exe", "-NoProfile", "-Command",
             "Copy-Item -LiteralPath {} -Destination {} -Force".format(
                 ps_quote(wsl_to_windows(path)), ps_quote(staged))],
            capture_output=True, text=True, timeout=120)
        if copy.returncode != 0:
            self.say("  (could not stage %s for OCR)" % path.name)
            return ""
        for _ in range(2):
            result = subprocess.run(
                ["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                 "-File", wsl_to_windows(OCR_PS1), "-Path", staged],
                capture_output=True, text=True, timeout=120)
            text = result.stdout
            if "OCR_FAILED" not in text and "NO_OCR_ENGINE" not in text:
                return text.upper()
            time.sleep(0.5)
        self.say("  (ocr failed on %s)" % path.name)
        return ""

    def windows_temp(self):
        """A native Windows directory, for handing files to the OCR engine.

        WinRT's StorageFile cannot open a wsl.localhost path: the async call fails
        and surfaces as a null task, which the helper reports as OCR_FAILED.
        Screenshots therefore have to cross onto a real Windows path before they can
        be read, so this asks Windows for its own temp directory instead of
        deriving one.
        """
        if self._win_temp is None:
            result = subprocess.run(["powershell.exe", "-NoProfile", "-Command", "$env:TEMP"],
                                    capture_output=True, text=True, timeout=60)
            self._win_temp = result.stdout.strip() or None
        return self._win_temp


def wsl_to_windows(path):
    result = subprocess.run(["wslpath", "-w", str(path)], capture_output=True, text=True)
    return result.stdout.strip() or str(path)


def ps_quote(text):
    """Single-quote a literal for PowerShell, which doubles an inner quote."""
    return "'" + str(text).replace("'", "''") + "'"


# ---- the sequence ---------------------------------------------------------


def ensure_session(run):
    """Get a session with the title's window up, launching it if needed."""
    alive, phase = run.running()
    run.say("session: window=%s phase=%s" % (alive, phase))
    if alive:
        return True
    if run.args.no_launch:
        run.say("no window and --no-launch was given; nothing to attach to")
        return False

    # A launcher stuck on an earlier failed session will not start a new one: it
    # answers the launch and then never leaves FAILED, which looks like the game
    # refusing to boot. Restarting the app clears that.
    if phase not in ("IDLE", None):
        run.adb("shell", "am", "force-stop", LAUNCHER)
        time.sleep(4)
    run.ddctl("wake", timeout=120)
    time.sleep(3)
    result = run.ddctl("launch", "--game", GAME, timeout=240)
    if '"ok": true' not in result.stdout:
        run.say("launch refused: %s" % (result.stdout or result.stderr)[-300:])
        return False

    deadline = time.time() + run.args.launch_timeout
    while time.time() < deadline:
        time.sleep(10)
        alive, phase = run.running()
        if alive:
            run.say("session up: window=%s phase=%s" % (alive, phase))
            return True
    run.say("no window after %ss (phase=%s)" % (run.args.launch_timeout, phase))
    return False


def reach_pause(run):
    """Walk whatever screen is up until the pause list is showing."""
    for step in range(run.args.attempts):
        path = run.shot("reach")
        text = run.ocr(path)
        if any(mark in text for mark in PAUSE):
            run.say("pause list up (step %d)" % step)
            return True
        if any(mark in text for mark in TITLE):
            run.say("title screen; pressing A")
            run.pad("a")
            time.sleep(9)
            continue
        if "CONTINUE" in text:
            run.say("welcome panel; pressing A")
            run.pad("a")
            time.sleep(10)
            continue
        if step == 0:
            # Fresh from the garage: back out to the world before pausing.
            run.pad("b")
            time.sleep(4)
            run.pad("b")
            time.sleep(4)
        run.pad("start")
        time.sleep(3)
    return False


def start_join(run):
    """Right, then A: the pause list opens on Horizon Life, so one right is the
    connect item. A returns to the game and the join runs in the background."""
    run.pad("right")
    time.sleep(1.5)
    run.pad("a")
    run.say("pressed right and A")
    time.sleep(8)


def read_outcome(run):
    """Open the pause list again and read what PLAYER LIST says.

    The start press cannot be sent once and trusted: right after the join the title
    is in a transition, and a press landing there is swallowed, which reads back as
    an ordinary driving frame and looks like "unknown". Press and read until the
    list is actually on screen.
    """
    for attempt in range(run.args.attempts):
        run.pad("start")
        time.sleep(4)
        path = run.shot("outcome")
        text = run.ocr(path)
        marks = [mark for mark in LIST_MARKS if mark in text]
        run.say("  read %d: %s" % (attempt + 1, marks or "no menu"))
        if marks:
            return classify(text), text, path
        time.sleep(5)
    return "unknown", "", None


def classify(text):
    """joined / not_joined / unknown, from the pause list's own wording.

    NOT AVAILABLE next to PLAYER LIST is the title saying it is not in a session;
    CONNECT TO HORIZON LIFE only exists while there is somewhere to connect to.
    Either one means the room is not up, whatever the network counters did.
    """
    if any(mark in text for mark in NOT_IN_SESSION):
        return "not_joined"
    if "PLAYER LIST" in text:
        return "joined"
    return "unknown"


# ---- what the shim published ---------------------------------------------


def published(trace_text):
    ipv4 = sorted(set(TRACE_IPV4.findall(trace_text)))
    ipv6 = sorted(set(TRACE_IPV6.findall(trace_text)))
    return ipv4, ipv6


def device_addresses(run):
    """This device's own IPv4 addresses, for comparison with what was published."""
    result = run.adb("shell", "ip", "-4", "addr", "show")
    return sorted(set(re.findall(r"inet (\d+\.\d+\.\d+\.\d+)/", result.stdout)))


def network_summary(trace_text):
    return {
        "websocket": bool(TRACE_NET.search(trace_text)),
        "statuses": sorted(set(TRACE_STATUS.findall(trace_text))),
        "name_failures": failures_by_host(trace_text),
    }


def failures_by_host(trace_text):
    """Group unresolved-name failures by host.

    A count alone says little: one host failing is a service that moved, while
    dozens spread over many hosts is a resolver problem. The host is taken from the
    failing line itself so the report names what failed instead of only tallying it.
    """
    counts = {}
    for line in trace_text.splitlines():
        if not TRACE_NAMEFAIL.search(line):
            continue
        host = TRACE_HOST.search(line)
        name = host.group(1) if host else "(unparsed)"
        counts[name] = counts.get(name, 0) + 1
    return counts


def pull_trace(run):
    """Copy the shim trace out of the guest, if it is readable.

    "exec-out" is an adb verb, not a shell command: passing "shell" first makes adb
    run the word exec-out inside the device shell, which fails and reads back as an
    unreadable trace.
    """
    path = run.out / "uwp-host-trace.log"
    result = run.adb("exec-out", "run-as", LAUNCHER, "cat", TRACE_IN_GUEST)
    if result.returncode != 0 or len(result.stdout) < 200:
        return None
    path.write_text(result.stdout, encoding="utf-8", errors="replace")
    return path


# ---- report --------------------------------------------------------------


def report(run, outcome, text, trace_path):
    run.say("")
    run.say("--- pause list ---")
    words = [w.strip() for w in text.splitlines() if w.strip()]
    run.say("  " + (" | ".join(words)[:600] if words else "(nothing read)"))
    run.say("  outcome: %s" % outcome)

    run.say("")
    run.say("--- what the shim published ---")
    local = device_addresses(run)
    run.say("  device addresses: %s" % (", ".join(local) or "none found"))
    trace_path = trace_path or pull_trace(run)
    if trace_path is None:
        run.say("  trace: not readable")
        return False
    trace_text = trace_path.read_text(encoding="utf-8", errors="replace")
    ipv4, ipv6 = published(trace_text)
    net = network_summary(trace_text)
    run.say("  sda ipv4: %s" % (", ".join(ipv4) or "none recorded"))
    run.say("  sda ipv6: %s" % (", ".join(ipv6) or "none recorded"))
    run.say("  rta websocket: %s" % ("connected" if net["websocket"] else "no connect seen"))
    run.say("  http statuses: %s" % (", ".join(net["statuses"]) or "none"))
    failures = net["name_failures"]
    run.say("  unresolved names: %d" % sum(failures.values()))
    for host, count in sorted(failures.items(), key=lambda item: -item[1])[:5]:
        run.say("    %4dx %s" % (count, host))

    # The point of publishing an address is that peers can reach it, so the one
    # that matters is whether it is this device's rather than a stranger's.
    matches = bool(local) and any(addr in local for addr in ipv4)
    foreign = [addr for addr in ipv4 if local and addr not in local]
    run.say("  published address is this device's: %s" % ("yes" if matches else "NO"))
    if foreign:
        run.say("  addresses that are not this device: %s" % ", ".join(foreign))

    run.say("")
    run.say("--- verdict ---")
    if outcome == "joined" and matches:
        run.say("  online: room joined, peers were given this device's address")
        return True
    if outcome == "joined" and not matches:
        run.say("  online: room joined, but the published address is not this device")
        return False
    if not matches:
        run.say("  offline: the room did not come up and the address published is wrong")
        return False
    run.say("  offline: the address published is right, so this is not the address path")
    return False


def tidy(run):
    info = run.adb("shell", "dumpsys", "battery").stdout
    fields = {}
    for line in info.splitlines():
        if ":" in line:
            key, _, value = line.partition(":")
            fields[key.strip()] = value.strip()
    if fields.get("level"):
        # dumpsys reports the Android battery status code, not a word.
        state = {"1": "unknown", "2": "charging", "3": "discharging",
                 "4": "not charging", "5": "full"}.get(fields.get("status", ""), "?")
        power = [name for name, key in (("AC", "AC powered"), ("USB", "USB powered"),
                                        ("wireless", "Wireless powered"))
                 if fields.get(key) == "true"]
        run.say("battery: %s%% %s on %s" % (fields["level"], state,
                                            "+".join(power) or "battery"))
    run.adb("shell", "svc", "power", "stayon", "false")
    if run.args.keep:
        run.say("session left running (--keep)")
        return
    run.adb("shell", "am", "force-stop", LAUNCHER)
    run.adb("shell", "input", "keyevent", "223")
    run.say("launcher stopped, device asleep")


def check_dll(run):
    """Optionally confirm the device carries the build this run is meant to test."""
    if not run.args.expect_dll:
        return True
    import hashlib
    local = Path(run.args.expect_dll).read_bytes()
    want = hashlib.md5(local).hexdigest()
    result = run.adb("shell", "run-as", LAUNCHER, "md5sum", DLL_IN_GUEST)
    got = (result.stdout.split() or [""])[0]
    if got != want:
        run.say("device uwp-host.dll is %s, expected %s" % (got or "unreadable", want))
        return False
    run.say("device uwp-host.dll matches the build")
    return True


# ---- entry ---------------------------------------------------------------


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--adb", help="adb executable (default: ADB, PATH, or the Android SDK)")
    parser.add_argument("--serial", help="device serial (default: ADB_SERIAL or the only device)")
    parser.add_argument("--out", help="where screenshots and the trace are written")
    parser.add_argument("--no-launch", action="store_true", help="attach to a running session")
    parser.add_argument("--keep", action="store_true", help="leave the session running")
    parser.add_argument("--expect-dll", help="verify the device's uwp-host.dll is this build")
    parser.add_argument("--launch-timeout", type=int, default=420,
                        help="seconds to wait for the title's window")
    parser.add_argument("--attempts", type=int, default=12, help="screen reads before giving up")
    args = parser.parse_args(argv)

    if not CTL.exists():
        print("droiddeckctl not found at %s" % CTL)
        return 2

    run = Run(args)
    run.say("artifacts: %s" % run.out)
    if not check_dll(run):
        return 2

    # A pad press does not reach the power manager, so the display can time out
    # mid-run; the session runner treats that as the end of the session.
    run.adb("shell", "svc", "power", "stayon", "usb")
    run.ddctl("wake", timeout=120)

    try:
        if not ensure_session(run):
            return 2
        if not reach_pause(run):
            run.say("the pause list never appeared")
            return 2
        start_join(run)
        outcome, text, _ = read_outcome(run)
        trace_path = pull_trace(run)
        ok = report(run, outcome, text, trace_path)
        return 0 if ok else 1
    finally:
        tidy(run)


if __name__ == "__main__":
    raise SystemExit(main())
