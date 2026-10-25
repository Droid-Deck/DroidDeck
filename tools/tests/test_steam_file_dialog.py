import io
import json
import os
import runpy
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
LIVE = runpy.run_path(str(BIN / "droiddeck-steam-compat"))

HARNESS = r"""
const assert = require('assert');
globalThis.window = globalThis;
const native = () => Promise.reject({ result: 2, message: '' });
const popupCallbacks = [];
const popup = { window: { SteamClient: { System: { OpenFileDialog: native } } } };
const later = { window: { SteamClient: { System: { OpenFileDialog: native } } } };
globalThis.SteamClient = { System: { OpenFileDialog: native } };
window.g_PopupManager = { GetPopups: () => [popup], AddPopupCreatedCallback: cb => popupCallbacks.push(cb) };
const asked = [];
window.droiddeckOpenFileDialog = payload => asked.push(JSON.parse(payload));
const script = require('fs').readFileSync(process.argv[2], 'utf8');
(async () => {
  assert.strictEqual(eval(script), 'installed');
  const orphan = SteamClient.System.OpenFileDialog({ strTitle: 'Before reconnect' });
  assert.strictEqual(eval(script), 'present');
  await assert.rejects(orphan, e => e.result === 2);
  assert.strictEqual(window.__droiddeckFiles.done(1, '/late'), false);
  asked.length = 0;
  assert.notStrictEqual(SteamClient.System.OpenFileDialog, native);
  assert.strictEqual(popup.window.SteamClient.System.OpenFileDialog, SteamClient.System.OpenFileDialog);
  popupCallbacks.forEach(cb => cb(later));
  assert.strictEqual(later.window.SteamClient.System.OpenFileDialog, SteamClient.System.OpenFileDialog);
  const options = { strTitle: 'Pick', bChooseDirectory: true };
  const chosen = SteamClient.System.OpenFileDialog(options);
  const cancelled = later.window.SteamClient.System.OpenFileDialog({ strTitle: 'Other' });
  assert.deepStrictEqual(asked, [{ id: 2, options }, { id: 3, options: { strTitle: 'Other' } }]);
  assert.strictEqual(window.__droiddeckFiles.done(2, '/root/Storage/Games'), true);
  assert.strictEqual(window.__droiddeckFiles.done(2, '/again'), false);
  assert.strictEqual(await chosen, '/root/Storage/Games');
  window.__droiddeckFiles.done(3, null);
  await assert.rejects(cancelled, e => e.result === 2);
  delete window.droiddeckOpenFileDialog;
  await assert.rejects(SteamClient.System.OpenFileDialog({}), e => e.result === 2);
})().catch(e => { console.error(e); process.exit(1); });
"""


def bound(payload):
    return {"method": "Runtime.bindingCalled", "params": {"name": LIVE["FILE_BINDING"], "payload": payload}}


class FileDialogScriptTest(unittest.TestCase):
    def test_every_window_asks_the_bridge_and_gets_the_path_or_a_cancel(self):
        with tempfile.TemporaryDirectory() as tmp:
            script = Path(tmp) / "files.js"
            script.write_text(LIVE["FILE_JS"])
            harness = Path(tmp) / "harness.js"
            harness.write_text(HARNESS)
            result = subprocess.run(["node", str(harness), str(script)], capture_output=True, text=True, timeout=20)
            self.assertEqual(result.returncode, 0, result.stderr)


class FileDialogBridgeTest(unittest.TestCase):
    def setUp(self):
        self.globals = LIVE["Helper"].attach.__globals__
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.helper = LIVE["Helper"](str(self.root), 1)
        self.helper.page = mock.Mock()

    def answer(self):
        return self.helper.page.evaluate.call_args.args[0]

    def test_the_chooser_runs_under_a_steam_reaper_with_the_options_on_stdin(self):
        reaper = self.root / "steamrtarm64/reaper"
        reaper.parent.mkdir()
        reaper.write_text("#!/bin/sh\n")
        reaper.chmod(0o755)
        proc = mock.Mock(stdin=io.BytesIO())
        proc.stdin.close = lambda: None
        with mock.patch.object(self.globals["subprocess"], "Popen", return_value=proc) as popen:
            self.helper.page.drain.return_value = [bound(json.dumps({"id": 3, "options": {"strTitle": "Pick"}}))]
            self.helper.drain()
        command = popen.call_args.args[0]
        self.assertEqual(command[:4], [str(reaper), "SteamLaunch", "AppId=769", "--"])
        self.assertEqual(command[-1], "--choose-file")
        self.assertEqual(json.loads(proc.stdin.getvalue()), {"strTitle": "Pick"})
        self.assertEqual(self.helper.chooser, (3, proc))

    def test_a_second_request_while_one_is_open_is_cancelled(self):
        first = mock.Mock()
        self.helper.chooser = (1, first)
        self.helper.open_chooser(json.dumps({"id": 2, "options": {}}))
        self.assertEqual(self.answer(), "window.__droiddeckFiles?.done(2, null)")
        self.assertEqual(self.helper.chooser, (1, first))

    def test_the_chosen_path_or_a_cancel_goes_back_to_the_page(self):
        for code, out, expected in ((0, b'"/root/Storage/My Game/game.sh"\n', '"/root/Storage/My Game/game.sh"'),
                                    (0, b'" spaced name "\n', '" spaced name "'),
                                    (1, b"", "null"), (2, b"", "null"), (0, b"\n", "null"), (0, b"not json\n", "null")):
            proc = mock.Mock(stdout=io.BytesIO(out), returncode=code)
            self.helper.chooser = (7, proc)
            self.helper.chooser_done()
            self.assertEqual(self.answer(), "window.__droiddeckFiles?.done(7, %s)" % expected)
            self.assertIsNone(self.helper.chooser)

    def test_bad_requests_are_ignored(self):
        with mock.patch.object(self.globals["subprocess"], "Popen") as popen:
            for payload in ("not json", json.dumps({"options": {}}), json.dumps({"id": 1, "options": []})):
                self.helper.open_chooser(payload)
        popen.assert_not_called()
        self.helper.page.evaluate.assert_not_called()

    def test_choose_file_mode_prints_the_path_and_fails_on_cancel(self):
        for chooser, code, printed in ((mock.Mock(return_value="/root/Storage/a.exe "), 0, '"/root/Storage/a.exe "\n'),
                                       (mock.Mock(return_value=None), 1, ""),
                                       (mock.Mock(side_effect=ValueError("Namespace Gtk not available for version 3.0")), 2, ""),
                                       (mock.Mock(side_effect=ImportError("No module named 'gi'")), 2, "")):
            with mock.patch.dict(LIVE["chooser_main"].__globals__, {"choose_file": chooser}), \
                    mock.patch.object(sys, "stdin", io.StringIO('{"strTitle": "Pick"}')), \
                    mock.patch.object(sys, "stdout", io.StringIO()) as out:
                self.assertEqual(LIVE["chooser_main"](), code)
                self.assertEqual(out.getvalue(), printed)

    def test_attach_installs_the_file_dialog_bridge(self):
        page = mock.Mock()
        page.evaluate.return_value = True
        with mock.patch.dict(self.globals, {"shared_context": lambda port: "/devtools/page/1",
                                            "Page": mock.Mock(return_value=page)}):
            self.helper.attach()
        page.call.assert_any_call("Runtime.addBinding", {"name": LIVE["FILE_BINDING"]})
        page.evaluate.assert_any_call(LIVE["FILE_JS"])


if __name__ == "__main__":
    unittest.main()
