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
    else if (method === "CompatManager.GetCompatTools#1" && ["16", "128"].includes(String(body[1][0]))) result = Number(body[1][0]);
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
  const after = await c.many(["0", "42", "43", "128"]);
  const timedOut = await c.many(["0", "16"]).then(() => null, (e) => e.eresult);
  await new Promise((r) => setTimeout(r, 10));
  console.log(JSON.stringify({ before, after, timedOut, log: state.log, notify: state.notify, url: state.socket.url }));
})().catch((e) => { console.error(e.stack || String(e)); process.exit(1); });
"""


class FakePage:
    def __init__(self, mapping, known, refused=()):
        self.mapping = dict(mapping)
        self.known = list(known)
        self.refused = set(refused)
        self.sets = []

    def compat(self, call):
        name, _, args = call.partition("(")
        args = json.loads("[%s]" % args[:-1])
        if name == "get":
            return {"selected": self.mapping.get(str(args[0]), ""), "fallback": "proton_experimental", "tools": self.known}
        if name == "many":
            return {app: self.mapping.get(app, "") for app in args[0] if app not in self.refused}
        if name == "set":
            if str(args[0]) in self.refused:
                raise RuntimeError("SpecifyCompatTool %s failed (128)" % args[0])
            self.mapping[str(args[0])] = args[1]
            self.sets.append((str(args[0]), args[1]))
            return True
        raise AssertionError(call)


@unittest.skipUnless(shutil.which("node"), "node is not installed")
class PageScriptTest(unittest.TestCase):
    def test_vr_games_wait_for_steamvr_and_preserve_the_original_launch(self):
        with tempfile.TemporaryDirectory() as tmp:
            script = Path(tmp) / "vr.js"
            script.write_text(LIVE["VR_JS"])
            harness = Path(__file__).with_name("steam_vr_launch_harness.js")
            result = subprocess.run(["node", str(harness), str(script)],
                                    capture_output=True, text=True, timeout=20)
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_tool_installs_skip_only_their_own_install_dialog(self):
        harness_source = r"""
const assert = require('assert');
globalThis.window = globalThis;
const script = require('fs').readFileSync(process.argv[2], 'utf8');
globalThis.SteamClient = { Installs: {}, Downloads: {} };
assert.throws(() => eval(script), /install calls are not ready/);
const opened = [], queued = [];
let wizard = null, continued = 0;
globalThis.SteamClient = {
  Installs: {
    OpenInstallWizard(apps, client) { opened.push([apps, client]); },
    RegisterForShowInstallWizard(callback) { assert.strictEqual(wizard, null); wizard = callback; return { unregister() {} }; },
    ContinueInstall() { continued++; },
  },
  Downloads: { QueueAppUpdate(app, client) { queued.push([app, client]); } },
};
assert.strictEqual(eval(script), 'installed');
assert.strictEqual(eval(script), 'present');
const tools = window.__droiddeckTools;
wizard({ eInstallState: 7, currentAppID: 4183110 });
assert.strictEqual(continued, 0);
tools.install(4183110);
assert.deepStrictEqual(opened, [[[4183110], '0']]);
wizard({ eInstallState: 8, currentAppID: 4183110 });
wizard({ eInstallState: 7, currentAppID: 892970 });
assert.strictEqual(continued, 0);
wizard({ eInstallState: 7, currentAppID: 4183110 });
wizard({ eInstallState: 7, currentAppID: 4183110 });
assert.strictEqual(continued, 1);
tools.update(4427310);
assert.deepStrictEqual(queued, [[4427310, '0']]);
"""
        with tempfile.TemporaryDirectory() as tmp:
            script = Path(tmp) / "tools.js"
            script.write_text(LIVE["TOOLS_JS"])
            harness = Path(tmp) / "harness.js"
            harness.write_text(harness_source)
            result = subprocess.run(["node", str(harness), str(script)], capture_output=True, text=True, timeout=20)
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_steamvr_library_launch_uses_binding_and_other_games_pass_through(self):
        harness_source = r"""
const assert = require('assert');
globalThis.window = globalThis;
const calls = [], requests = [];
const apps = { RunGame(...args) { assert.strictEqual(this, apps); calls.push(args); return 'native'; } };
globalThis.SteamClient = { Apps: apps };
window.droiddeckLaunchSteamVr = id => requests.push(id);
const script = require('fs').readFileSync(process.argv[2], 'utf8');
assert.strictEqual(eval(script), 'installed');
assert.strictEqual(eval(script), 'present');
assert.strictEqual(apps.RunGame('250820', '', -1, 100), undefined);
apps.RunGame(250820, '', -1, 100);
assert.deepStrictEqual(requests, ['250820', '250820']);
assert.deepStrictEqual(calls, []);
assert.strictEqual(apps.RunGame('611670', 'custom args', -1, 100), 'native');
assert.strictEqual(apps.RunGame('2508200', '', -1, 100), 'native');
delete window.droiddeckLaunchSteamVr;
assert.strictEqual(apps.RunGame('250820', '', -1, 100), 'native');
assert.deepStrictEqual(calls, [['611670', 'custom args', -1, 100], ['2508200', '', -1, 100], ['250820', '', -1, 100]]);
"""
        with tempfile.TemporaryDirectory() as tmp:
            script = Path(tmp) / "vr.js"
            script.write_text(LIVE["VR_JS"])
            harness = Path(tmp) / "test.js"
            harness.write_text(harness_source)
            result = subprocess.run(["node", str(harness), str(script)],
                                    capture_output=True, text=True, timeout=20)
            self.assertEqual(result.returncode, 0, result.stderr)

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
            self.assertEqual(out["timedOut"], 16)
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

    def test_an_app_the_client_refuses_is_dropped_instead_of_failing_the_sync(self):
        page = FakePage({"0": TOOL, "10": TOOL, "11": "proton_experimental"}, [TOOL, TOOL_11], refused={"1628350"})
        (self.state / "state.json").write_text(json.dumps({"default": TOOL, "auto": {"10": TOOL, "1628350": TOOL}}))
        (self.state / "games.json").write_text(json.dumps({"version": 1, "games": {"1628350": {"valve": True, "dir": "Proton 11.0 (ARM64)"}}}))
        state = self.reconcile(page)
        self.assertEqual(page.sets, [("11", TOOL)])
        self.assertEqual(state["auto"], {"10": TOOL, "11": TOOL})

    def test_a_refused_apps_native_choice_is_kept(self):
        page = FakePage({"0": TOOL, "10": TOOL, "11": TOOL}, [TOOL, TOOL_11], refused={"20"})
        (self.state / "state.json").write_text(json.dumps({"default": TOOL, "auto": {"10": TOOL, "11": TOOL}, "native_choices": ["20"]}))
        state = self.reconcile(page)
        self.assertEqual(page.sets, [])
        self.assertEqual(state["native_choices"], ["20"])

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

    def test_per_game_customize_and_inherit_apply_live(self):
        page = FakePage({"0": TOOL, "10": TOOL, "11": TOOL_11}, [TOOL, TOOL_11])
        (self.state / "games.json").write_text(json.dumps({
            "version": 1,
            "games": {
                "10": {"valve": True, "dir": "Proton 11.0 (ARM64)"},
                "11": None,
            },
        }))
        state = self.reconcile(page)
        self.assertEqual(page.sets, [("10", TOOL_11), ("11", TOOL)])
        self.assertEqual(state["auto"], {"11": TOOL})

    def test_native_arm64_title_drops_auto_proton_once_and_keeps_explicit_choices(self):
        (self.steam / "steamapps/appmanifest_11.acf").unlink()
        (self.state / "state.json").write_text(json.dumps({
            "default": TOOL,
            "auto": {"10": TOOL},
        }))
        page = FakePage({"0": TOOL, "10": TOOL}, [TOOL, TOOL_11])
        original_native_targets = COMPAT["native_targets"]
        COMPAT["native_targets"] = lambda _root, _apps: {"10": None}
        try:
            state = self.reconcile(page)
            self.assertEqual(page.sets, [("10", "")])
            self.assertEqual(page.mapping["10"], "")
            self.assertEqual(state["auto"], {})

            page.sets.clear()
            self.reconcile(page)
            self.assertEqual(page.sets, [])

            (self.state / "games.json").write_text(json.dumps({
                "version": 1,
                "games": {"10": {"valve": True, "dir": "Proton 11.0 (ARM64)"}},
            }))
            state = self.reconcile(page)
            self.assertEqual(page.sets, [("10", TOOL_11)])
            self.assertEqual(page.mapping["10"], TOOL_11)
            self.assertEqual(state["auto"], {})
            (self.state / "games.json").unlink()

            page.sets.clear()
            page.mapping["10"] = "steamlinuxruntime_arm64"
            self.reconcile(page)
            self.assertEqual(page.mapping["10"], "steamlinuxruntime_arm64")
            self.assertEqual(page.sets, [])

            page.mapping["10"] = TOOL_11
            self.reconcile(page)
            self.assertEqual(page.mapping["10"], TOOL_11)
            self.assertEqual(page.sets, [])

            page.mapping["10"] = COMPAT["FEX_TOOL"]
            self.reconcile(page)
            self.assertEqual(page.mapping["10"], COMPAT["FEX_TOOL"])
            self.assertEqual(page.sets, [])

            page.mapping["10"] = "GE-Proton11-7"
            self.reconcile(page)
            self.assertEqual(page.mapping["10"], "GE-Proton11-7")
            self.assertEqual(page.sets, [])
        finally:
            COMPAT["native_targets"] = original_native_targets

    def test_factorio_native_choice_survives_inherited_live_selection_and_restart(self):
        app = "427520"
        (self.steam / ("steamapps/appmanifest_%s.acf" % app)).write_text("")
        config = self.steam / "config/config.vdf"
        config.parent.mkdir(parents=True)
        config.write_text(
            '"InstallConfigStore" { "Software" { "Valve" { "Steam" { '
            '"CompatToolMapping" { "0" { "name" "%s" } } } } } }' % TOOL)
        (self.state / "state.json").write_text(json.dumps({
            "default": TOOL,
            "auto": {app: TOOL},
        }))

        original_native_targets = COMPAT["native_targets"]
        COMPAT["native_targets"] = lambda _root, apps: {
            app: COMPAT["NATIVE_CHOICE"]
        } if app in apps else {}
        try:
            page = FakePage({"0": TOOL, "10": TOOL, "11": TOOL, app: "proton"}, [TOOL, TOOL_11])
            state = self.reconcile(page)
            self.assertEqual(page.sets, [])
            self.assertEqual(state["auto"], {})
            self.assertEqual(state["native_choices"], [app])
            self.assertEqual(COMPAT["read_config_mapping"](str(config)), {"0": TOOL})

            page.sets.clear()
            state = self.reconcile(page)
            self.assertEqual(page.sets, [])
            self.assertEqual(state["native_choices"], [app])
            restarted = LIVE["Helper"](str(self.steam), 1)
            restarted.page = page
            restarted.reconcile()
            state = json.loads((self.state / "state.json").read_text())
            self.assertEqual(page.sets, [])
            self.assertEqual(state["native_choices"], [app])

            config.write_text(
                '"InstallConfigStore" { "Software" { "Valve" { "Steam" { '
                '"CompatToolMapping" { "0" { "name" "%s" } '
                '"%s" { "name" "GE-Proton11-7" } } } } } }' % (TOOL, app))
            page.mapping[app] = "GE-Proton11-7"
            state = self.reconcile(page)
            self.assertEqual(page.mapping[app], "GE-Proton11-7")
            self.assertEqual(page.sets, [])
            self.assertEqual(state["native_choices"], [])

            for choice in ("steamlinuxruntime", COMPAT["FEX_TOOL"], TOOL_11):
                (self.state / "state.json").write_text(json.dumps({
                    "default": TOOL,
                    "auto": {},
                    "native_choices": [app],
                }))
                config.write_text(
                    '"InstallConfigStore" { "Software" { "Valve" { "Steam" { '
                    '"CompatToolMapping" { "0" { "name" "%s" } '
                    '"%s" { "name" "%s" } } } } } }' % (TOOL, app, choice))
                page.mapping[app] = choice
                page.sets.clear()
                state = self.reconcile(page)
                self.assertEqual(page.mapping[app], choice)
                self.assertEqual(page.sets, [])
                self.assertEqual(state["native_choices"], [], choice)
        finally:
            COMPAT["native_targets"] = original_native_targets

    def test_saved_native_choice_survives_live_restart_without_manifest_or_appinfo(self):
        app = "452280"
        config = self.steam / "config/config.vdf"
        config.parent.mkdir(parents=True)
        config.write_text(
            '"InstallConfigStore" { "Software" { "Valve" { "Steam" { '
            '"CompatToolMapping" { "0" { "name" "%s" } } } } } }' % TOOL)
        (self.state / "state.json").write_text(json.dumps({
            "default": TOOL,
            "auto": {},
            "native_choices": [app],
        }))

        original_native_targets = COMPAT["native_targets"]
        COMPAT["native_targets"] = lambda _root, _apps: {}
        try:
            page = FakePage({"0": TOOL, app: "proton"}, [TOOL, TOOL_11])
            state = self.reconcile(page)
            self.assertNotIn(app, self.helper.installed)
            self.assertEqual(state["native_choices"], [app])
            self.assertNotIn(app, state["auto"])
            self.assertNotIn((app, TOOL), page.sets)
            self.assertEqual(COMPAT["read_config_mapping"](str(config)), {"0": TOOL})

            config.write_text('"InstallConfigStore" { "Software" { "Other" {} } }')
            state = self.reconcile(page)
            self.assertEqual(state["native_choices"], [app])
            self.assertNotIn((app, TOOL), page.sets)

            config.write_text(
                '"InstallConfigStore" { "Software" { "Valve" { "Steam" { '
                '"CompatToolMapping" { "0" { "name" "%s" } '
                '"%s" { "name" "GE-Proton11-7" } } } } } }' % (TOOL, app))
            page.mapping[app] = "proton"
            state = self.reconcile(page)
            self.assertEqual(state["native_choices"], [])
            self.assertNotIn((app, TOOL), page.sets)
            self.assertEqual(COMPAT["read_config_mapping"](str(config))[app], "GE-Proton11-7")
        finally:
            COMPAT["native_targets"] = original_native_targets

    def dual_platform_game(self, state):
        app = "892970"
        (self.steam / ("steamapps/appmanifest_%s.acf" % app)).write_text("")
        config = self.steam / "config/config.vdf"
        config.parent.mkdir(parents=True)
        config.write_text(
            '"InstallConfigStore" { "Software" { "Valve" { "Steam" { '
            '"CompatToolMapping" { "0" { "name" "%s" } } } } } }' % TOOL)
        (self.state / "state.json").write_text(json.dumps(state))
        patcher = mock.patch.dict(COMPAT, {"native_targets": lambda _root, apps: {
            app: COMPAT["NATIVE_CHOICE"]
        } if app in apps else {}})
        patcher.start()
        self.addCleanup(patcher.stop)
        return app, FakePage({"0": TOOL, "10": TOOL, "11": TOOL}, [TOOL, TOOL_11])

    def test_unmapped_dual_platform_game_gets_the_default_live(self):
        app, page = self.dual_platform_game({"default": TOOL, "auto": {"10": TOOL, "11": TOOL}})
        state = self.reconcile(page)
        self.assertEqual(page.sets, [(app, TOOL)])
        self.assertEqual(state["auto"][app], TOOL)
        self.assertEqual(state["native_choices"], [])

        page.mapping[app] = ""
        page.sets.clear()
        state = self.reconcile(page)
        self.assertEqual(page.sets, [])
        self.assertEqual(state["native_choices"], [app])

    def test_clearing_a_tool_picked_in_steam_keeps_the_native_build(self):
        app, page = self.dual_platform_game({"default": TOOL, "auto": {"10": TOOL, "11": TOOL}})
        page.known.append(COMPAT["FEX_TOOL"])
        page.mapping[app] = COMPAT["FEX_TOOL"]
        config = self.steam / "config/config.vdf"
        config.write_text(
            '"InstallConfigStore" { "Software" { "Valve" { "Steam" { '
            '"CompatToolMapping" { "0" { "name" "%s" } "%s" { "name" "%s" } } } } } }' % (TOOL, app, COMPAT["FEX_TOOL"]))
        state = self.reconcile(page)
        self.assertEqual(page.sets, [])
        self.assertNotIn(app, state["auto"])

        page.mapping[app] = ""
        state = self.reconcile(page)
        self.assertEqual(page.sets, [])
        config.write_text(
            '"InstallConfigStore" { "Software" { "Valve" { "Steam" { '
            '"CompatToolMapping" { "0" { "name" "%s" } } } } } }' % TOOL)
        state = self.reconcile(page)
        self.assertEqual(page.sets, [])
        self.assertEqual(state["native_choices"], [app])

        restarted = LIVE["Helper"](str(self.steam), 1)
        restarted.page = page
        restarted.reconcile()
        self.assertEqual(page.sets, [])

    def test_a_new_tool_for_a_picked_game_is_kept_before_the_client_writes_its_config(self):
        app, page = self.dual_platform_game({"default": TOOL, "auto": {"10": TOOL, "11": TOOL}, "picked": ["892970"]})
        page.mapping[app] = TOOL_11
        with mock.patch.dict(COMPAT, {"native_targets": lambda _root, apps: {app: COMPAT["FEX_TOOL"]} if app in apps else {}}):
            state = self.reconcile(page)
        self.assertEqual(page.sets, [])
        self.assertEqual(page.mapping[app], TOOL_11)
        self.assertIn(app, state["picked"])

    def test_a_cleared_picked_game_showing_a_stale_inherited_tool_stays_native(self):
        app, page = self.dual_platform_game({"default": TOOL, "auto": {"10": TOOL, "11": TOOL}, "picked": ["892970"]})
        page.mapping[app] = "proton"
        state = self.reconcile(page)
        self.assertEqual(page.sets, [])
        self.assertEqual(state["native_choices"], [app])

    def test_a_clear_saved_after_the_helper_stopped_is_kept_at_the_next_start(self):
        app, page = self.dual_platform_game({"default": TOOL, "auto": {"10": TOOL, "11": TOOL}})
        page.known.append(COMPAT["FEX_TOOL"])
        page.mapping[app] = COMPAT["FEX_TOOL"]
        config = self.steam / "config/config.vdf"
        config.write_text(
            '"InstallConfigStore" { "Software" { "Valve" { "Steam" { '
            '"CompatToolMapping" { "0" { "name" "%s" } "%s" { "name" "%s" } } } } } }' % (TOOL, app, COMPAT["FEX_TOOL"]))
        state = self.reconcile(page)
        self.assertIn(app, state["picked"])

        config.write_text(
            '"InstallConfigStore" { "Software" { "Valve" { "Steam" { '
            '"CompatToolMapping" { "0" { "name" "%s" } } } } } }' % TOOL)
        with mock.patch.dict(COMPAT["register_default"].__globals__, {"native_targets": COMPAT["native_targets"]}):
            COMPAT["register_default"](str(config), ["10", "11", app], settle=True, directory=str(self.state))
        self.assertNotIn(app, COMPAT["read_config_mapping"](str(config)))
        state = json.loads((self.state / "state.json").read_text())
        self.assertEqual(state["native_choices"], [app])
        self.assertNotIn(app, state["picked"])

    def test_unmapped_dual_platform_game_gets_the_default_without_a_readable_config(self):
        app, page = self.dual_platform_game({"default": TOOL, "auto": {"10": TOOL}, "native_choices": ["11"]})
        (self.steam / "config/config.vdf").unlink()
        page.mapping["11"] = ""
        state = self.reconcile(page)
        self.assertEqual(page.sets, [(app, TOOL)])
        self.assertEqual(state["auto"][app], TOOL)
        self.assertEqual(state["native_choices"], ["11"])

    def test_a_tool_set_live_is_kept_before_the_client_writes_its_config(self):
        app, page = self.dual_platform_game({"default": TOOL, "auto": {"10": TOOL, "11": TOOL, "892970": TOOL}})
        page.known.append(COMPAT["FEX_TOOL"])
        page.mapping[app] = TOOL
        config = self.steam / "config/config.vdf"
        config.write_text(
            '"InstallConfigStore" { "Software" { "Valve" { "Steam" { '
            '"CompatToolMapping" { "0" { "name" "%s" } "%s" { "name" "%s" } } } } } }' % (TOOL, app, TOOL))
        with mock.patch.dict(COMPAT, {"native_targets": lambda _root, apps: {app: COMPAT["FEX_TOOL"]} if app in apps else {}}):
            state = self.reconcile(page)
            self.assertEqual(page.sets, [(app, COMPAT["FEX_TOOL"])])
            state = self.reconcile(page)
        self.assertEqual(page.sets, [(app, COMPAT["FEX_TOOL"])])
        self.assertEqual(state["auto"][app], COMPAT["FEX_TOOL"])

    def test_native_list_from_earlier_builds_is_not_trusted(self):
        app, page = self.dual_platform_game({"default": TOOL, "auto": {"10": TOOL, "11": TOOL}, "native": ["892970"]})
        state = self.reconcile(page)
        self.assertEqual(page.sets, [(app, TOOL)])
        self.assertNotIn("native", state)
        self.assertEqual(state["native_choices"], [])

    def test_factorio_without_prior_auto_state_uses_the_default_proton(self):
        app = "427520"
        (self.steam / ("steamapps/appmanifest_%s.acf" % app)).write_text("")
        config = self.steam / "config/config.vdf"
        config.parent.mkdir(parents=True)
        config.write_text(
            '"InstallConfigStore" { "Software" { "Valve" { "Steam" { '
            '"CompatToolMapping" { "0" { "name" "%s" } } } } } }' % TOOL)
        (self.state / "state.json").write_text(json.dumps({"default": TOOL, "auto": {}}))
        page = FakePage({"0": TOOL, "10": TOOL, "11": TOOL, app: "proton"}, [TOOL, TOOL_11])
        original_native_targets = COMPAT["native_targets"]
        COMPAT["native_targets"] = lambda _root, apps: {
            app: COMPAT["NATIVE_CHOICE"]
        } if app in apps else {}
        try:
            state = self.reconcile(page)
            self.assertEqual(page.sets, [(app, TOOL)])
            self.assertEqual(state["auto"], {app: TOOL})
            self.assertEqual(state["native_choices"], [])
            self.assertEqual(COMPAT["read_config_mapping"](str(config)), {"0": TOOL})
        finally:
            COMPAT["native_targets"] = original_native_targets

    def test_missing_per_game_proton_stays_deferred_live(self):
        page = FakePage({"0": TOOL, "10": "GE-Proton9-1", "11": TOOL_11}, [TOOL, TOOL_11])
        (self.state / "games.json").write_text(json.dumps({
            "version": 1,
            "games": {
                "10": {"valve": False, "dir": "GE-Proton9-1"},
                "11": None,
            },
        }))
        state = self.reconcile(page)
        self.assertEqual(page.sets, [("11", TOOL)])
        self.assertEqual(page.mapping["10"], "GE-Proton9-1")
        self.assertEqual(state["auto"], {"11": TOOL})

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


class ToolPage(FakePage):
    def __init__(self, fail=False):
        super().__init__({"0": TOOL}, [TOOL])
        self.calls = []
        self.fail = fail

    def tools(self, call):
        self.calls.append(call)
        if self.fail:
            raise RuntimeError("the client's install calls are not ready")
        return True


class ToolDownloadTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.steam = Path(tmp.name) / "Steam"
        (self.steam / "steamapps").mkdir(parents=True)
        self.helper = LIVE["Helper"](str(self.steam), 1)
        self.now = [1000.0]
        self.running = [False]
        self.requested = []
        patcher = mock.patch.dict(COMPAT, {
            "game_running": lambda: self.running[0],
            "request_fex": lambda root, asked: self.requested.append(root) or ["3127680"],
        })
        patcher.start()
        self.addCleanup(patcher.stop)
        clock = mock.patch.object(LIVE["time"], "monotonic", lambda: self.now[0])
        clock.start()
        self.addCleanup(clock.stop)

    def manifest(self, app, flags):
        (self.steam / ("steamapps/appmanifest_%s.acf" % app)).write_text('"AppState" { "StateFlags" "%s" }' % flags)

    def test_missing_tools_are_installed_and_deferred_ones_queued(self):
        self.manifest("4427310", "6")
        self.manifest("3127680", "4")
        self.manifest("1628350", "1026")
        self.helper.page = page = ToolPage()
        self.helper.ensure_tools()
        self.assertEqual(page.calls, ["update(4427310)", "update(1628350)", "install(4183110)"])
        self.now[0] += 30
        self.helper.ensure_tools()
        self.now[0] += 60
        self.helper.ensure_tools()
        self.assertEqual(len(page.calls), 3)
        self.now[0] += 300
        self.manifest("4427310", "4")
        self.helper.ensure_tools()
        self.assertEqual(page.calls[3:], ["update(1628350)", "install(4183110)"])
        self.assertEqual(self.requested, [])

    def test_nothing_is_queued_while_a_game_runs(self):
        self.running[0] = True
        self.helper.page = page = ToolPage()
        self.helper.ensure_tools()
        self.assertEqual(page.calls, [])
        self.running[0] = False
        self.now[0] += 60
        self.helper.ensure_tools()
        self.assertEqual(len(page.calls), 4)

    def test_an_install_falls_back_to_the_steam_url(self):
        for app in ("4427310", "1628350", "4183110"):
            self.manifest(app, "4")
        self.helper.page = page = ToolPage(fail=True)
        self.helper.ensure_tools()
        self.assertEqual(page.calls, ["install(3127680)"])
        self.assertEqual(self.requested, [str(self.steam)])


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

    def test_vr_bridge_is_installed_for_both_steam_ui_modes(self):
        for steamos in (False, True):
            helper = LIVE["Helper"]("/steam", 1, steamos=steamos)
            helper.attach()
            self.assertIn(("Runtime.addBinding", LIVE["VR_BINDING"]), helper.page.calls)
            self.assertIn(LIVE["VR_JS"], helper.page.scripts)

    def test_vr_bridge_accepts_only_steamvr_and_coalesces_requests(self):
        helper = LIVE["Helper"]("/steam", 1)
        helper.page = mock.Mock()
        event = lambda payload: {"method": "Runtime.bindingCalled", "params": {
            "name": LIVE["VR_BINDING"], "payload": payload}}
        with mock.patch.dict(self.globals, {"launch_steamvr": mock.Mock()}) as patched:
            launch = patched["launch_steamvr"]
            helper.page.drain.return_value = [event("0"), event("250820; touch /tmp/wrong"),
                                            event("-1"), event("01"), event("18446744073709551616"),
                                            event("\u0661"), event(None), event({})]
            helper.drain()
            launch.assert_not_called()
            helper.page.drain.return_value = [event("250820"), event("250820")]
            helper.drain()
            launch.assert_called_once_with()

    def test_vr_game_requests_share_an_in_progress_helper_and_retry_after_it_exits(self):
        helper = LIVE["Helper"]("/steam", 1)
        helper.page = mock.Mock()
        event = lambda app: {"method": "Runtime.bindingCalled", "params": {
            "name": LIVE["VR_BINDING"], "payload": app}}
        child = mock.Mock()
        child.poll.return_value = None
        with mock.patch.dict(self.globals, {"launch_steamvr": mock.Mock(return_value=child)}) as patched:
            helper.page.drain.return_value = [event("611670"), event("620980")]
            helper.drain()
            helper.drain()
            patched["launch_steamvr"].assert_called_once_with()
            child.poll.return_value = 0
            helper.drain()
            self.assertEqual(patched["launch_steamvr"].call_count, 2)

    def test_vr_launch_runs_helper_without_shell_in_background(self):
        with tempfile.TemporaryDirectory() as tmp, mock.patch.dict(os.environ, {"XDG_RUNTIME_DIR": tmp}):
            with mock.patch.object(self.globals["subprocess"], "Popen") as popen:
                LIVE["launch_steamvr"]()
                self.assertEqual(popen.call_args.args, ([LIVE["VR_HELPER"], "launch"],))
                self.assertTrue(popen.call_args.kwargs["start_new_session"])
                self.assertNotIn("shell", popen.call_args.kwargs)
            self.assertTrue((Path(tmp) / "droiddeck-steamvr/library-launch.log").exists())

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
