"""The Quick Access Frame Limit follows the panel when its refresh rate changes."""
from pathlib import Path
import runpy
import subprocess
import tempfile
import unittest
from unittest import mock

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
LIVE = runpy.run_path(str(BIN / "droiddeck-steam-compat"))

HARNESS = r"""
const assert = require('assert');
globalThis.window = globalThis;
globalThis.btoa = text => Buffer.from(text, 'binary').toString('base64');
let timers = new Map(), nextTimer = 0;
globalThis.setTimeout = (callback, ms) => { timers.set(++nextTimer, callback); return nextTimer; };
globalThis.clearTimeout = id => timers.delete(id);
globalThis.setInterval = () => 0;
const fire = async () => {
  const pending = [...timers.values()]; timers = new Map();
  for (const callback of pending) await callback();
};
let rates = [36, 48, 72, 144];
const notify = [];
const service = {
  GetState: async () => ({ BSuccess: () => true,
    Body: () => ({ toObject: () => ({ state: { active_display_info: { supported_frame_rates: rates } } }) }) }),
  RegisterForNotifyStateChanged(callback) { notify.push(callback); return {}; },
  SetBlurParams() {},
};
const writes = [];
globalThis.settingsStore = { clientSettings: {
  gamescope_enable_app_target_framerate: true, gamescope_app_target_framerate: 120, gamescope_disable_framelimit: false } };
const varints = bytes => { const out = []; let value = 0, shift = 0;
  for (const b of bytes) { value += (b & 127) * 2 ** shift; shift += 7; if (b < 128) { out.push(value); value = 0; shift = 0; } }
  return out; };
globalThis.SteamClient = { Settings: { SetSetting(encoded) {
  const [key, value] = varints([...Buffer.from(encoded, 'base64')]);
  assert.strictEqual(key, 21002 * 8);
  writes.push(value);
  settingsStore.clientSettings.gamescope_app_target_framerate = value;
} } };
const modules = {
  1: { source: () => 'RegisterForNotifyStateChanged only', exports: { other: { GetState() {} } } },
  2: { source: () => 'n.MX.SetBlurParams(); n.MX.RegisterForNotifyStateChanged()', exports: { hooks: () => {} } },
  3: { source: () => 'SetBlurParams RegisterForNotifyStateChanged GetState', exports: { MX: service } },
};
const webpack = modules => { const require = id => modules[id].exports;
  require.m = Object.fromEntries(Object.entries(modules).map(([id, m]) => [id, { toString: m.source }]));
  return require; };
globalThis.webpackChunksteamui = { push([, , callback]) { callback(webpack(modules)); } };
const script = require('fs').readFileSync(process.argv[2], 'utf8');
const change = async next => { rates = next; notify.forEach(callback => callback()); };
(async () => {
  assert.strictEqual(eval(script), 'installed');
  assert.strictEqual(eval(script), 'present');
  await fire();
  assert.deepStrictEqual(writes, [144], 'a remembered limit the panel does not offer moves to the highest value');
  await fire();
  assert.deepStrictEqual(writes, [144], 'unchanged choices are not written again');
  settingsStore.clientSettings.gamescope_app_target_framerate = 72;
  await change([24, 30, 40, 60, 120]);
  await fire();
  assert.deepStrictEqual(writes, [144, 120], '72 is not offered at 120 Hz');
  settingsStore.clientSettings.gamescope_app_target_framerate = 60;
  await change([12, 15, 20, 30, 60]);
  await fire();
  assert.deepStrictEqual(writes, [144, 120], '60 is still offered at 60 Hz and is kept');
  await change([18, 24, 36, 72]);
  await change([24, 30, 40, 60, 120]);
  await fire();
  assert.deepStrictEqual(writes, [144, 120], 'a passing rate during a mode switch is not acted on');
  settingsStore.clientSettings.gamescope_enable_app_target_framerate = false;
  settingsStore.clientSettings.gamescope_app_target_framerate = 72;
  await change([36, 48, 144]);
  await fire();
  assert.deepStrictEqual(writes, [144, 120], 'nothing changes while Steam has the frame limit turned off');
  delete window.__droiddeckFrameLimit;
  webpackChunksteamui.push = ([, , callback]) => callback(webpack({ 1: modules[1], 2: modules[2] }));
  assert.strictEqual(eval(script), 'unavailable');
})().catch(error => { console.error(error); process.exit(1); });
"""


class FrameLimitSyncTest(unittest.TestCase):
    def test_limit_is_kept_when_offered_and_moved_to_the_highest_value_otherwise(self):
        with tempfile.TemporaryDirectory() as directory:
            script = Path(directory) / "frame_limit.js"
            script.write_text(LIVE["FRAME_LIMIT_JS"])
            test = Path(directory) / "test.js"
            test.write_text(HARNESS)
            result = subprocess.run(["node", str(test), str(script)], capture_output=True, text=True, timeout=20)
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_install_is_retried_until_steam_is_ready_and_reported_once_per_state(self):
        helper = LIVE["Helper"]("/steam", 0)
        page = mock.Mock()
        page.evaluate.side_effect = ["unavailable", "unavailable", "installed"]
        helper.page = page
        said = []
        clock = [100.0]
        globals_ = LIVE["Helper"].sync_frame_limit.__globals__
        with mock.patch.dict(globals_, {"say": said.append}), mock.patch.object(globals_["time"], "monotonic", lambda: clock[0]):
            helper.sync_frame_limit()
            helper.sync_frame_limit()
            clock[0] += 10
            helper.sync_frame_limit()
            clock[0] += 10
            helper.sync_frame_limit()
            clock[0] += 10
            helper.sync_frame_limit()
        self.assertEqual(page.evaluate.call_count, 3)
        self.assertEqual(said, ["frame limit sync: unavailable", "frame limit sync: installed"])


if __name__ == "__main__":
    unittest.main()
