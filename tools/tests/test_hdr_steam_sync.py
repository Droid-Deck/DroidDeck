import json
from pathlib import Path
import runpy
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
BIN = ROOT / "tools/linuxfs/overlay/usr/local/bin"
APP = ROOT / "app/src/main/java/com/droiddeck/launcher"
LIVE = runpy.run_path(str(BIN / "droiddeck-steam-compat"))

HARNESS = r"""
const assert = require('assert');
globalThis.window = globalThis;
globalThis.btoa = text => Buffer.from(text, 'binary').toString('base64');
let now = 0, tick = null;
Object.defineProperty(globalThis, 'performance', { value: { now: () => now }, configurable: true });
globalThis.setInterval = callback => { tick = callback; return 1; };
const varints = bytes => { const out = []; let value = 0, shift = 0;
  for (const b of bytes) { value += (b & 127) * 2 ** shift; shift += 7; if (b < 128) { out.push(value); value = 0; shift = 0; } }
  return out; };
const writes = [], told = [];
let apply = true;
globalThis.settingsStore = { clientSettings: { gamescope_hdr_enabled: false } };
globalThis.SteamClient = { Settings: { SetSetting(encoded) {
  const [key, value] = varints([...Buffer.from(encoded, 'base64')]);
  assert.strictEqual(key, 21015 * 8);
  writes.push(value);
  if (apply) settingsStore.clientSettings.gamescope_hdr_enabled = value === 1;
} } };
window.droiddeckHdrChanged = value => told.push(value);
const script = require('fs').readFileSync(process.argv[2], 'utf8');
assert.strictEqual(eval(script), 'installed');
assert.strictEqual(eval(script), 'present');
const bridge = window.__droiddeckHdr;
tick();
assert.deepStrictEqual(told, [], 'an unchanged client setting is not reported');
assert.strictEqual(bridge.set(true), true);
assert.deepStrictEqual(writes, [1]);
tick();
assert.deepStrictEqual(told, [], 'a value the app set is not reported back to it');
assert.strictEqual(bridge.set(true), true);
assert.deepStrictEqual(writes, [1], 'an equal value is not written again');
settingsStore.clientSettings.gamescope_hdr_enabled = false;
tick();
assert.deepStrictEqual(told, ['0'], 'a change made in the client reaches the app');
tick();
assert.deepStrictEqual(told, ['0']);
settingsStore.clientSettings.gamescope_hdr_enabled = true;
tick();
assert.deepStrictEqual(told, ['0', '1']);
apply = false;
bridge.set(false);
assert.deepStrictEqual(writes, [1, 0]);
now += 500; tick();
assert.deepStrictEqual(told, ['0', '1'], 'the old value is not reported while a write is settling');
now += 3000; tick();
assert.deepStrictEqual(told, ['0', '1', '1'], 'a write the client did not take is reported as its real value');
assert.strictEqual(bridge.set('yes'), true);
assert.deepStrictEqual(writes, [1, 0]);
delete globalThis.__droiddeckHdr;
delete settingsStore.clientSettings.gamescope_hdr_enabled;
assert.strictEqual(eval(script), 'unavailable');
"""


class HdrSteamSyncTest(unittest.TestCase):
    def test_bridge_keeps_the_client_setting_and_the_app_equal(self):
        with tempfile.TemporaryDirectory() as directory:
            script = Path(directory) / "bridge.js"
            script.write_text(LIVE["HDR_JS"])
            test = Path(directory) / "test.js"
            test.write_text(HARNESS)
            result = subprocess.run(["node", str(test), str(script)], capture_output=True, text=True, timeout=20)
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_requests_accept_only_on_and_off(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "hdr.request"
            for value in ["2", "true", "", None, 1]:
                LIVE["hdr_request"](value, directory)
                self.assertFalse(path.exists())
            LIVE["hdr_request"]("1", directory)
            self.assertEqual(path.read_text(), "1\n")
            LIVE["hdr_request"]("0", directory)
            self.assertEqual(path.read_text(), "0\n")

    def helper(self, directory):
        helper = LIVE["Helper"]("/steam", 0)
        calls = []

        class Page:
            answer = True

            def call(self, method, params=None):
                calls.append((method, params))

            def evaluate(self, expression):
                calls.append(expression)
                return "installed" if expression == LIVE["HDR_JS"] else self.answer

        helper.page = Page()
        return helper, calls

    def test_the_app_state_is_applied_once_per_change(self):
        with tempfile.TemporaryDirectory() as directory:
            globals_ = LIVE["Helper"].sync_hdr.__globals__
            previous = globals_["CONTROLS_DIR"]
            try:
                globals_["CONTROLS_DIR"] = directory
                helper, calls = self.helper(directory)
                path = Path(directory) / "state.json"
                helper.sync_hdr()
                path.write_text(json.dumps({"brightness": 0.5}))
                helper.sync_hdr()
                self.assertEqual(calls, [], "a session that cannot show HDR leaves the client setting alone")
                path.write_text(json.dumps({"brightness": 0.5, "hdr": True}))
                helper.sync_hdr()
                helper.sync_hdr()
                self.assertEqual(calls[0], ("Runtime.addBinding", {"name": LIVE["HDR_BINDING"]}))
                self.assertEqual(calls[1], LIVE["HDR_JS"])
                self.assertEqual(calls[2:], ["window.__droiddeckHdr ? window.__droiddeckHdr.set(true) : false"])
                path.write_text(json.dumps({"brightness": 0.7, "hdr": True}))
                helper.sync_hdr()
                self.assertEqual(len(calls), 3)
                path.write_text(json.dumps({"hdr": False}))
                helper.sync_hdr()
                self.assertEqual(calls[3], "window.__droiddeckHdr ? window.__droiddeckHdr.set(false) : false")
                helper.page.answer = False
                path.write_text(json.dumps({"hdr": True}))
                helper.sync_hdr()
                self.assertFalse(helper.hdr_installed)
                self.assertIs(helper.hdr_state, False)
            finally:
                globals_["CONTROLS_DIR"] = previous

    def test_a_client_without_the_setting_is_left_alone(self):
        with tempfile.TemporaryDirectory() as directory:
            globals_ = LIVE["Helper"].sync_hdr.__globals__
            previous = globals_["CONTROLS_DIR"]
            try:
                globals_["CONTROLS_DIR"] = directory
                helper, calls = self.helper(directory)
                helper.page.evaluate = lambda expression: calls.append(expression) or "unavailable"
                (Path(directory) / "state.json").write_text(json.dumps({"hdr": True}))
                helper.sync_hdr()
                self.assertEqual(calls, [("Runtime.addBinding", {"name": LIVE["HDR_BINDING"]}), LIVE["HDR_JS"]])
                self.assertFalse(helper.hdr_installed)
                self.assertIsNone(helper.hdr_state)
                helper.sync_hdr()
                self.assertEqual(len(calls), 2, "a client without the setting is not asked again straight away")
            finally:
                globals_["CONTROLS_DIR"] = previous

    def test_the_app_publishes_and_takes_the_shared_value(self):
        controls = (APP / "runtime/DeviceControlsComponent.kt").read_text()
        self.assertIn('if (SessionState.hdrCapable) controls.put("hdr", SessionState.hdr)', controls)
        self.assertIn('"hdr.request" -> if (text == "0" || text == "1")', controls)
        self.assertIn('SessionHdr.set(context, SessionPrefs.prefMode(SessionState.mode), text == "1")', controls)
        self.assertIn('"hdr.request")', controls[controls.index("REQUESTS = setOf"):])
        session = (APP / "SessionActivity.kt").read_text()
        self.assertIn("if (key == SessionPrefs.hdrKey(hdrPrefMode()))", session)
        self.assertIn("registerOnSharedPreferenceChangeListener(hdrPrefListener)", session)
        self.assertIn("unregisterOnSharedPreferenceChangeListener(hdrPrefListener)", session)

    def test_frame_limit_writes_share_the_setting_writer(self):
        self.assertIn("(21002, value)", LIVE["FRAME_LIMIT_JS"])
        self.assertNotIn("__SET_SETTING__", LIVE["FRAME_LIMIT_JS"] + LIVE["HDR_JS"])


if __name__ == "__main__":
    unittest.main()
