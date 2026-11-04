import json
from pathlib import Path
import runpy
import shutil
import subprocess
import unittest

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
LIVE = runpy.run_path(str(BIN / "droiddeck-steam-compat"))

HARNESS = r"""
const assert = require('assert');
const listeners = {}, sent = [];
globalThis.window = globalThis;
globalThis.innerWidth = 1000;
globalThis.innerHeight = 600;
globalThis.screen = { availWidth: 1280, availHeight: 670 };
globalThis.addEventListener = (type, callback) => { listeners[type] = callback; };
let stack = [], layers = [];
globalThis.document = { createElement: () => ({}), documentElement: { append() {} }, elementsFromPoint: () => stack,
  querySelectorAll: () => layers };
globalThis.getComputedStyle = target => ({ webkitAppRegion: target.region });
globalThis.droiddeckWindowAction = action => sent.push(action);
globalThis.SteamClient = { Window: { ToggleMaximize() { throw new Error('native'); } } };
assert.strictEqual(eval(SCRIPT), 'installed');
assert.strictEqual(eval(SCRIPT), 'present');
const event = (x, y, region = 'none', extra = {}) => {
  stack = [].concat(region).map(name => ({ region: name }));
  return { clientX: x, clientY: y, button: 0, buttons: 0,
    stopPropagation() { this.stopped = true; }, preventDefault() {}, ...extra };
};
const take = () => sent.splice(0);

listeners.pointerdown(event(500, 300));
listeners.pointermove(event(520, 300, 'none', { buttons: 1 }));
assert.deepStrictEqual(take(), []);

listeners.pointerdown(event(500, 12, 'drag'));
listeners.pointerup(event(500, 12, 'drag'));
listeners.pointermove(event(520, 12, 'drag'));
assert.deepStrictEqual(take(), []);

listeners.pointerdown(event(500, 12, 'drag'));
listeners.pointermove(event(501, 12, 'drag', { buttons: 1 }));
assert.deepStrictEqual(take(), []);
listeners.pointermove(event(510, 12, 'drag', { buttons: 1 }));
assert.deepStrictEqual(take(), ['move']);
listeners.pointermove(event(530, 12, 'drag', { buttons: 1 }));
assert.deepStrictEqual(take(), []);
listeners.pointerup(event(530, 12, 'drag'));

for (const [x, y, side] of [[2, 300, 'w'], [998, 300, 'e'], [500, 2, 'n'], [500, 598, 's'],
                            [8, 8, 'nw'], [992, 8, 'ne'], [8, 592, 'sw'], [992, 592, 'se']]) {
  const press = event(x, y);
  listeners.pointerdown(press);
  assert.strictEqual(press.stopped, true);
  assert.deepStrictEqual(take(), [side]);
}

listeners.pointerdown(event(500, 12, ['none', 'none', 'drag']));
listeners.pointermove(event(520, 12, ['none', 'none', 'drag'], { buttons: 1 }));
assert.deepStrictEqual(take(), ['move']);
listeners.pointerup(event(520, 12));
listeners.pointerdown(event(500, 12, ['none', 'no-drag', 'drag']));
listeners.pointermove(event(520, 12, ['none', 'no-drag', 'drag'], { buttons: 1 }));
assert.deepStrictEqual(take(), []);
listeners.pointerup(event(520, 12));

layers = [{ region: 'none', getBoundingClientRect: () => ({ left: 0, right: 1000, top: 0, bottom: 600 }) },
          { region: 'drag', getBoundingClientRect: () => ({ left: 0, right: 1000, top: 0, bottom: 66 }) }];
listeners.pointerdown(event(500, 12));
listeners.pointermove(event(520, 12, 'none', { buttons: 1 }));
assert.deepStrictEqual(take(), ['move']);
listeners.pointerup(event(520, 12));
listeners.pointerdown(event(500, 300));
listeners.pointermove(event(520, 300, 'none', { buttons: 1 }));
assert.deepStrictEqual(take(), []);
listeners.pointerup(event(520, 300));
layers = [];

const right = event(2, 300, 'none', { button: 2 });
listeners.pointerdown(right);
assert.deepStrictEqual(take(), []);

listeners.dblclick(event(500, 12, 'drag'));
assert.deepStrictEqual(take(), ['maximize']);
listeners.dblclick(event(500, 300));
assert.deepStrictEqual(take(), []);
SteamClient.Window.ToggleMaximize();
assert.deepStrictEqual(take(), ['maximize']);

globalThis.innerWidth = 1280;
globalThis.innerHeight = 670;
listeners.pointerdown(event(2, 300));
assert.deepStrictEqual(take(), []);
"""


class SteamWindowControlsTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which("node"), "needs node")
    def test_header_and_edges_request_window_actions(self):
        script = "const SCRIPT = %s;\n%s" % (json.dumps(LIVE["WINDOW_JS"]), HARNESS)
        result = subprocess.run(["node", "-e", script], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_unknown_actions_are_refused(self):
        self.assertFalse(LIVE["window_action"]("destroy"))
        self.assertFalse(LIVE["window_action"](None))

    def test_moves_use_the_ewmh_directions(self):
        self.assertEqual(LIVE["WINDOW_MOVES"], {"nw": 0, "n": 1, "ne": 2, "e": 3, "se": 4, "s": 5, "sw": 6, "w": 7,
                                                "move": 8})


if __name__ == "__main__":
    unittest.main()
