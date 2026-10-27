import json
from pathlib import Path
import runpy
import subprocess
import tempfile
import unittest
from unittest import mock

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
LIVE = runpy.run_path(str(BIN / "droiddeck-steam-compat"))


class DeviceControlsTest(unittest.TestCase):
    def test_sync_controls_queued_notifications_are_processed_without_another_frame(self):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory, "state.json").write_text(json.dumps({"brightness": 0.5, "revision": 1}))
            helper = LIVE["Helper"]("/steam", 0)
            helper.controls_installed = True
            page = mock.Mock(events=[])
            page.ws.pending.return_value = False
            helper.page = page
            payload = json.dumps({"id": 1, "options": {"strTitle": "Pick a file"}})
            def evaluate(expression):
                for name, value in [(LIVE["BRIGHTNESS_BINDING"], "0.4"), (LIVE["FILE_BINDING"], payload)]:
                    page.events.append({"method": "Runtime.bindingCalled", "params": {"name": name, "payload": value}})
                return True
            def drain():
                events, page.events = page.events, []
                return events
            page.evaluate.side_effect = evaluate
            page.drain.side_effect = drain
            helper.reconcile = mock.Mock()
            helper.sync_frame_limit = mock.Mock()
            helper.ensure_tools = mock.Mock(side_effect=[None, StopIteration])
            helper.open_chooser = mock.Mock()
            watcher = mock.Mock()
            watcher.fileno.return_value = -1
            watcher.changed.return_value = False
            globals_ = LIVE["Helper"].serve.__globals__
            with mock.patch.dict(globals_, {"CONTROLS_DIR": directory,
                    "brightness_request": lambda value: LIVE["brightness_request"](value, directory)}), \
                    mock.patch.dict(globals_["COMPAT"], {"state_path": lambda name: str(Path(directory, name))}), \
                    mock.patch.object(globals_["select"], "select", return_value=([], [], [])) as select_:
                with self.assertRaises(StopIteration):
                    helper.serve(watcher)
            self.assertEqual(float(Path(directory, "brightness.request").read_text()), 0.4)
            helper.open_chooser.assert_called_once_with(payload)
            self.assertEqual(page.events, [])
            select_.assert_not_called()

    def test_native_brightness_notifications_do_not_echo_android_readback(self):
        harness = r"""
const assert = require('assert');
globalThis.window = globalThis;
const requests = [], callbacks = [];
const timers = [];
globalThis.setTimeout = callback => timers.push(callback);
let current = 1;
window.droiddeckBrightnessChanged = value => requests.push(Number(value));
globalThis.SteamClient = { System: { Display: {
  RegisterForBrightnessChanges(cb) { callbacks.push(cb); cb({ flBrightness: 1 }); return {}; },
  SetBrightness(value) {
    if (current === value) return;
    current = value;
    callbacks.forEach(cb => cb({ flBrightness: value }));
  }
} } };
const script = require('fs').readFileSync(process.argv[2], 'utf8');
assert.strictEqual(eval(script), 'installed');
assert.strictEqual(eval(script), 'present');
const bridge = window.__droiddeckBrightness;
bridge.set(0.5);
bridge.set(0.5);
assert.deepStrictEqual(requests, []);
callbacks.forEach(cb => cb({ flBrightness: 0.8 }));
assert.deepStrictEqual(requests, [0.8]);
bridge.set(0.7992126);
assert.deepStrictEqual(requests, [0.8]);
for (const value of [NaN, Infinity, -1, 2, '0.5']) callbacks.forEach(cb => cb({ flBrightness: value }));
assert.deepStrictEqual(requests, [0.8]);
assert.strictEqual(callbacks.length, 1);
SteamClient.System.Display.SetBrightness = value => callbacks.forEach(cb => cb({ flBrightness: value + 0.1 }));
bridge.set(0.3);
assert.deepStrictEqual(requests, [0.8]);
callbacks.forEach(cb => cb({ flBrightness: 0.6 }));
assert.deepStrictEqual(requests, [0.8, 0.6]);
SteamClient.System.Display.SetBrightness = () => {};
bridge.set(0.65);
timers.forEach(callback => callback());
callbacks.forEach(cb => cb({ flBrightness: 0.7 }));
assert.deepStrictEqual(requests, [0.8, 0.6, 0.7]);
"""
        with tempfile.TemporaryDirectory() as directory:
            script = Path(directory) / "bridge.js"
            script.write_text(LIVE["BRIGHTNESS_JS"])
            test = Path(directory) / "test.js"
            test.write_text(harness)
            result = subprocess.run(["node", str(test), str(script)], capture_output=True, text=True, timeout=20)
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_requests_reject_nonfinite_and_out_of_range_values(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "brightness.request"
            for value in ["NaN", "Infinity", "-0.1", "1.1", "invalid", None]:
                LIVE["brightness_request"](value, directory)
                self.assertFalse(path.exists())
            LIVE["brightness_request"]("0.4", directory)
            self.assertEqual(float(path.read_text()), 0.4)

    def test_failed_request_revision_republishes_the_actual_brightness(self):
        with tempfile.TemporaryDirectory() as directory:
            globals_ = LIVE["Helper"].sync_controls.__globals__
            previous = globals_["CONTROLS_DIR"]
            try:
                globals_["CONTROLS_DIR"] = directory
                helper = LIVE["Helper"]("/steam", 0)
                calls = []
                helper.controls_installed = True
                helper.page = type("Page", (), {"evaluate": lambda self, expression: calls.append(expression) or True})()
                path = Path(directory) / "state.json"
                path.write_text(json.dumps({"brightness": 0.5, "revision": 1}))
                helper.sync_controls()
                helper.sync_controls()
                self.assertEqual(len(calls), 1)
                path.write_text(json.dumps({"brightness": 0.5, "revision": 2}))
                helper.sync_controls()
                self.assertEqual(len(calls), 2)
            finally:
                globals_["CONTROLS_DIR"] = previous

    def test_display_bridge_retries_binding_and_install_before_caching_state(self):
        with tempfile.TemporaryDirectory() as directory:
            globals_ = LIVE["Helper"].sync_controls.__globals__
            previous = globals_["CONTROLS_DIR"]
            try:
                globals_["CONTROLS_DIR"] = directory
                Path(directory, "state.json").write_text(json.dumps({"brightness": 0.5, "revision": 1}))
                calls, installs, writes = [], ["unavailable", "installed", "present"], []
                class Page:
                    present = True
                    def call(self, method, params):
                        calls.append((method, params))
                    def evaluate(self, expression):
                        if expression == LIVE["BRIGHTNESS_JS"]:
                            return installs.pop(0)
                        writes.append(expression)
                        return self.present
                helper = LIVE["Helper"]("/steam", 0)
                helper.page = Page()
                helper.sync_controls()
                self.assertIsNone(helper.controls_state)
                self.assertFalse(writes)
                helper.sync_controls()
                helper.sync_controls()
                self.assertEqual(len(calls), 2)
                self.assertEqual(len(writes), 1)
                self.assertEqual(calls[1], ("Runtime.addBinding", {"name": LIVE["BRIGHTNESS_BINDING"]}))
                helper.page.present = False
                Path(directory, "state.json").write_text(json.dumps({"brightness": 0.7, "revision": 2}))
                helper.sync_controls()
                self.assertIsNone(helper.controls_state)
                self.assertFalse(helper.controls_installed)
                helper.page.present = True
                helper.sync_controls()
                self.assertEqual(len(calls), 3)
                self.assertEqual(helper.controls_state["brightness"], 0.7)
            finally:
                globals_["CONTROLS_DIR"] = previous


if __name__ == "__main__":
    unittest.main()
