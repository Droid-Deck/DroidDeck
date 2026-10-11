import json
import os
from pathlib import Path
import runpy
import shutil
import subprocess
import tempfile
import unittest
from unittest import mock

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
LIVE = runpy.run_path(str(BIN / "droiddeck-steam-compat"))
COMPAT = LIVE["COMPAT"]
TOOL = COMPAT["TOOL"]
TOOL_11 = COMPAT["TOOL_11"]

FAKE_STEAM = r"""
const dec = new TextDecoder(), enc = new TextEncoder();
function readVarint(b, at) { let r = 0n, s = 0n, x; do { x = b[at.i++]; r |= BigInt(x & 0x7f) << s; s += 7n; } while (x & 0x80); return r; }
function fields(b) {
  const f = {}, at = { i: 0 };
  while (at.i < b.length) {
    const k = Number(readVarint(b, at)), n = k >> 3, w = k & 7; let v;
    if (w === 0) v = readVarint(b, at);
    else if (w === 1) { v = 0n; for (let j = 0; j < 8; j++) v |= BigInt(b[at.i + j]) << BigInt(8 * j); at.i += 8; }
    else if (w === 2) { const len = Number(readVarint(b, at)); v = b.slice(at.i, at.i + len); at.i += len; }
    else throw new Error("wire " + w);
    (f[n] = f[n] || []).push(v);
  }
  return f;
}
function vint(n, out) { n = BigInt(n); do { let x = Number(n & 0x7fn); n >>= 7n; if (n) x |= 0x80; out.push(x); } while (n); }
function lenField(n, bytes, out) { vint(n * 8 + 2, out); vint(bytes.length, out); for (const x of bytes) out.push(x); }
function reply(eresult, jobid, body, name) {
  const h = [];
  if (jobid !== null) { vint(11 * 8 + 1, h); let n = jobid; for (let i = 0; i < 8; i++) { h.push(Number(n & 0xffn)); n >>= 8n; } }
  if (name) lenField(12, enc.encode(name), h);
  vint(13 * 8, h); vint(eresult, h);
  const out = new Uint8Array(8 + h.length + body.length), dv = new DataView(out.buffer);
  dv.setUint32(0, (0x80000000 | 147) >>> 0, true); dv.setUint32(4, h.length, true);
  out.set(h, 8); out.set(body, 8 + h.length);
  return out.buffer;
}
const state = { mapping: JSON.parse(process.argv[2]), known: JSON.parse(process.argv[3]), log: [], authed: false, notify: [] };
class FakeSocket {
  constructor(url) { this.url = url; this.readyState = 0; state.socket = this; setTimeout(() => { this.readyState = 1; this.onopen(); }, 0); }
  send(buffer) {
    const b = new Uint8Array(buffer), dv = new DataView(buffer.buffer || buffer);
    if (dv.getUint32(0, true) !== ((0x80000000 | 146) >>> 0)) throw new Error("bad emsg");
    const hl = dv.getUint32(4, true), h = fields(b.slice(8, 8 + hl)), body = fields(b.slice(8 + hl));
    const method = dec.decode(h[12][0]), jobid = h[10][0];
    let out = [], result = 1;
    if (method === "TransportAuth.Authenticate#1") {
      state.authed = dec.decode(h[40][0]) === "secret" && dec.decode(body[1][0]) === "secret";
      result = state.authed ? 1 : 5;
    } else if (!state.authed) result = 5;
    else if (method === "CompatManager.GetCompatTools#1") {
      const app = String(body[1] ? body[1][0] : 0n);
      for (const name of state.known) { const t = []; lenField(1, enc.encode(name), t); lenField(2, enc.encode(name + " shown"), t); lenField(1, t, out); }
      lenField(3, enc.encode(state.mapping[app] || ""), out);
      lenField(4, enc.encode("proton_experimental"), out);
    } else if (method === "CompatManager.SpecifyCompatTool#1") {
      const app = String(body[1] ? body[1][0] : 0n), name = body[2] ? dec.decode(body[2][0]) : "";
      state.mapping[app] = name; state.log.push(app + "=" + name);
      setTimeout(() => this.onmessage({ data: reply(1, null, new Uint8Array(0), "CompatManager.NotifyStateChanged#1") }), 0);
    } else result = 2;
    setTimeout(() => this.onmessage({ data: reply(result, jobid, Uint8Array.from(out)) }), 0);
  }
  close() {}
}
globalThis.window = globalThis;
globalThis.WebSocket = FakeSocket;
globalThis.SteamClient = { WebUITransport: { GetTransportInfo: async () => ({ portClientdll: 4242, authKeyClientdll: "secret" }) } };
window.droiddeckCompatChanged = (n) => state.notify.push(n);
const install = require("fs").readFileSync(process.argv[4], "utf8");
(async () => {
  if (eval(install) !== "installed" || eval(install) !== "present") throw new Error("install is not idempotent");
  const c = window.__droiddeckCompat;
  const before = await c.get(0);
  await c.set(0, "droiddeck-proton-11-arm64");
  await c.set(42, "");
  const after = await c.many(["0", "42", "43"]);
  await new Promise((r) => setTimeout(r, 10));
  console.log(JSON.stringify({ before, after, log: state.log, notify: state.notify, url: state.socket.url }));
})().catch((e) => { console.error(e.stack || String(e)); process.exit(1); });
"""


class FakePage:
    def __init__(self, mapping, known):
        self.mapping = dict(mapping)
        self.known = list(known)
        self.sets = []

    def compat(self, call):
        name, _, args = call.partition("(")
        args = json.loads("[%s]" % args[:-1])
        if name == "get":
            return {"selected": self.mapping.get(str(args[0]), ""), "fallback": "proton_experimental", "tools": self.known}
        if name == "many":
            return {app: self.mapping.get(app, "") for app in args[0]}
        if name == "set":
            self.mapping[str(args[0])] = args[1]
            self.sets.append((str(args[0]), args[1]))
            return True
        raise AssertionError(call)


@unittest.skipUnless(shutil.which("node"), "node is not installed")
class PageScriptTest(unittest.TestCase):
    def test_transport_framing_auth_and_notifications(self):
        with tempfile.TemporaryDirectory() as tmp:
            script = Path(tmp) / "install.js"
            script.write_text(LIVE["INSTALL_JS"])
            harness = Path(tmp) / "harness.js"
            harness.write_text(FAKE_STEAM)
            result = subprocess.run(["node", str(harness), json.dumps({"0": TOOL, "42": "proton_experimental"}),
                                     json.dumps([TOOL, TOOL_11, "proton_experimental"]), str(script)],
                                    capture_output=True, text=True, timeout=60)
            self.assertEqual(result.returncode, 0, result.stderr)
            out = json.loads(result.stdout)
            self.assertEqual(out["url"], "ws://127.0.0.1:4242/transportsocket/")
            self.assertEqual(out["before"], {"selected": TOOL, "fallback": "proton_experimental", "tools": [TOOL, TOOL_11, "proton_experimental"]})
            self.assertEqual(out["after"], {"0": TOOL_11, "42": "", "43": ""})
            self.assertEqual(out["log"], ["0=" + TOOL_11, "42="])
            self.assertEqual(out["notify"], ["1", "2"])


class DevtoolsHttpTest(unittest.TestCase):
    def test_a_kept_alive_answer_is_read_to_its_length(self):
        import socket
        import threading
        server = socket.socket()
        server.bind(("127.0.0.1", 0))
        server.listen(1)
        port = server.getsockname()[1]
        body = json.dumps([{"title": "other"}, {"title": "SharedJSContext", "webSocketDebuggerUrl": "ws://127.0.0.1:%d/devtools/page/AB" % port}]).encode()
        done = threading.Event()

        def answer():
            conn, _ = server.accept()
            conn.recv(4096)
            conn.sendall(b"HTTP/1.1 200 OK\r\nContent-Length: %d\r\n\r\n" % len(body) + body)
            done.wait(10)
            conn.close()
            server.close()

        thread = threading.Thread(target=answer)
        thread.start()
        try:
            self.assertEqual(LIVE["shared_context"](port), "/devtools/page/AB")
        finally:
            done.set()
            thread.join()


class ReconcileTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        base = Path(self.tmp.name)
        self.steam = base / "Steam"
        for name in COMPAT["SOURCES"]:
            (self.steam / "steamapps/common" / name / "files/bin-arm64").mkdir(parents=True)
        for app in ("10", "11"):
            (self.steam / ("steamapps/appmanifest_%s.acf" % app)).write_text("")
        self.state = base / "compat"
        self.state.mkdir()
        COMPAT["main"].__globals__["COMPAT_DIR"] = str(self.state)
        COMPAT["main"].__globals__["LABEL_FILE"] = str(base / "no-label")
        self.addCleanup(COMPAT["main"].__globals__.__setitem__, "COMPAT_DIR", COMPAT["COMPAT_DIR"])
        self.helper = LIVE["Helper"](str(self.steam), 1)

    def reconcile(self, page):
        self.helper.page = page
        self.helper.reconcile()
        return json.loads((self.state / "state.json").read_text())

    def test_new_install_gets_the_default_live(self):
        page = FakePage({"0": TOOL, "10": TOOL, "11": "proton_experimental"}, [TOOL, TOOL_11])
        state = self.reconcile(page)
        self.assertEqual(page.sets, [("11", TOOL)])
        self.assertEqual(state["auto"], {"10": TOOL, "11": TOOL})
        self.assertTrue(state["live"])
        self.assertEqual(self.helper.installed, ["10", "11"])

    def test_app_request_moves_the_default_and_followers(self):
        page = FakePage({"0": TOOL, "10": TOOL, "11": "GE-Proton11-7"}, [TOOL, TOOL_11])
        (self.state / "state.json").write_text(json.dumps({"default": TOOL, "auto": {"10": TOOL}}))
        (self.state / "request.json").write_text(json.dumps({"seq": 7, "valve": True, "dir": "Proton 11.0 (ARM64)"}))
        state = self.reconcile(page)
        self.assertEqual(page.sets, [("0", TOOL_11), ("10", TOOL_11)])
        self.assertEqual((state["default"], state["source"], state["applied"]), (TOOL_11, "app", 7))
        self.assertEqual(page.mapping["11"], "GE-Proton11-7")

    def test_a_steam_menu_change_is_mirrored(self):
        page = FakePage({"0": TOOL_11, "10": TOOL}, [TOOL, TOOL_11])
        (self.state / "state.json").write_text(json.dumps({"default": TOOL, "auto": {"10": TOOL}}))
        state = self.reconcile(page)
        self.assertEqual((state["default"], state["source"]), (TOOL_11, "steam"))
        self.assertEqual(page.sets, [("10", TOOL_11), ("11", TOOL_11)])

    def test_a_tool_the_client_has_not_loaded_waits_for_a_restart(self):
        page = FakePage({"0": TOOL, "10": TOOL, "11": TOOL}, [TOOL])
        (self.state / "request.json").write_text(json.dumps({"seq": 3, "valve": True, "dir": "Proton 11.0 (ARM64)"}))
        state = self.reconcile(page)
        self.assertEqual(page.sets, [])
        self.assertEqual((state["pending"], state.get("applied", 0)), (TOOL_11, 0))
        self.assertTrue(self.helper.request_waiting())

    def test_file_mode_defers_to_the_live_helper(self):
        (self.state / "live.pid").write_text("%d\n" % os.getpid())
        self.assertTrue(COMPAT["live_helper_running"]())
        LIVE["detach"]()
        self.assertFalse(COMPAT["live_helper_running"]())


SWITCH_HARNESS = r"""
const sent = [], asked = [];
class FakeSocket { send(data) { sent.push(data); } }
globalThis.window = globalThis;
globalThis.WebSocket = FakeSocket;
window.droiddeckSwitchToDesktop = (x) => asked.push(x);
const install = require("fs").readFileSync(process.argv[2], "utf8");
const frame = (method) => {
  const name = new TextEncoder().encode(method), h = [0x51, 1, 0, 0, 0, 0, 0, 0, 0, 0x62, name.length, ...name];
  const out = new Uint8Array(8 + h.length), dv = new DataView(out.buffer);
  dv.setUint32(0, (0x80000000 | 146) >>> 0, true); dv.setUint32(4, h.length, true); out.set(h, 8);
  return out;
};
if (eval(install) !== "installed" || eval(install) !== "present") throw new Error("install is not idempotent");
const ws = new FakeSocket();
ws.send(frame("CompatManager.GetCompatTools#1"));
ws.send(frame("SteamOSManager.SwitchToDesktop#1").buffer);
ws.send(frame("SteamOSManager.SwitchToDesktop#1"));
ws.send("SteamOSManager.SwitchToDesktop#1");
console.log(JSON.stringify({ sent: sent.length, asked: asked.length }));
"""


@unittest.skipUnless(shutil.which("node"), "node is not installed")
class SwitchScriptTest(unittest.TestCase):
    def test_the_request_is_reported_and_still_sent(self):
        with tempfile.TemporaryDirectory() as tmp:
            script = Path(tmp) / "switch.js"
            script.write_text(LIVE["SWITCH_JS"])
            harness = Path(tmp) / "harness.js"
            harness.write_text(SWITCH_HARNESS)
            result = subprocess.run(["node", str(harness), str(script)], capture_output=True, text=True, timeout=60)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(json.loads(result.stdout), {"sent": 4, "asked": 2})


class AttachPage:
    def __init__(self, port, path):
        self.calls, self.scripts = [], []
        self.ws = mock.Mock()

    def evaluate(self, expression, timeout=30):
        self.scripts.append(expression)
        return True

    def call(self, method, params=None, timeout=30):
        self.calls.append((method, (params or {}).get("name")))
        return {}

    def compat(self, call):
        return {"selected": "", "fallback": "", "tools": []}


class SwitchToDesktopTest(unittest.TestCase):
    def setUp(self):
        self.globals = LIVE["Helper"].attach.__globals__
        patcher = mock.patch.dict(self.globals, {"shared_context": lambda port: "/devtools/page/1", "Page": AttachPage})
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_only_a_client_without_its_steamos_manager_is_watched(self):
        helper = LIVE["Helper"]("/steam", 1, steamos=False)
        helper.attach()
        self.assertIn(("Runtime.addBinding", LIVE["SWITCH_BINDING"]), helper.page.calls)
        self.assertIn(LIVE["SWITCH_JS"], helper.page.scripts)
        helper = LIVE["Helper"]("/steam", 1, steamos=True)
        helper.attach()
        self.assertNotIn(("Runtime.addBinding", LIVE["SWITCH_BINDING"]), helper.page.calls)
        self.assertNotIn(LIVE["SWITCH_JS"], helper.page.scripts)

    def test_the_request_goes_to_steamos_session_select(self):
        helper = LIVE["Helper"]("/steam", 1)
        helper.page = mock.Mock()
        helper.page.drain.return_value = [
            {"method": "Runtime.bindingCalled", "params": {"name": LIVE["BINDING"], "payload": "1"}},
            {"method": "Runtime.bindingCalled", "params": {"name": LIVE["SWITCH_BINDING"], "payload": ""}},
        ]
        with mock.patch.object(self.globals["subprocess"], "call", return_value=0) as call:
            events = helper.drain()
        call.assert_called_once_with(["/usr/bin/steamos-session-select", "plasma"])
        self.assertTrue(any(LIVE["called"](e, LIVE["BINDING"]) for e in events))
        helper.page.drain.return_value = [{"method": "Runtime.bindingCalled", "params": {"name": LIVE["BINDING"]}}]
        with mock.patch.object(self.globals["subprocess"], "call") as call:
            helper.drain()
        call.assert_not_called()


if __name__ == "__main__":
    unittest.main()
