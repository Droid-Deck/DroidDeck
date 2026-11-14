import json
import os
from pathlib import Path
import socket
import struct
import subprocess
import tempfile
import time
import unittest
import runpy
from types import SimpleNamespace

IME = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin/droiddeck-ime'


def message(obj, opcode, payload=b''):
    return struct.pack('<II', obj, ((8 + len(payload)) << 16) | opcode) + payload


def wl_string(value):
    data = value.encode() + b'\0'
    return struct.pack('<I', len(data)) + data + b'\0' * (-len(data) % 4)


class DesktopImeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.session = Path(self.temp.name)
        self.server, client = socket.socketpair(socket.AF_UNIX, socket.SOCK_STREAM)
        self.addCleanup(self.server.close)
        env = dict(os.environ, BL_LAUNCH_DIR=str(self.session), WAYLAND_SOCKET=str(client.fileno()))
        self.proc = subprocess.Popen(['python3', str(IME)], env=env, pass_fds=[client.fileno()],
                                     stderr=subprocess.PIPE, text=True)
        client.close()
        self.addCleanup(self.stop)
        self.server.settimeout(5)
        self.assertEqual(self.read(12), message(1, 1, struct.pack('<I', 2)))
        self.server.sendall(message(2, 0, struct.pack('<I', 7) + wl_string('zwp_input_method_v1') + struct.pack('<I', 1)))
        bind = self.read(8 + 4 + len(wl_string('zwp_input_method_v1')) + 8)
        self.assertEqual(bind, message(2, 0, struct.pack('<I', 7) + wl_string('zwp_input_method_v1') + struct.pack('<II', 1, 3)))

    def stop(self):
        if self.proc.poll() is None:
            self.proc.kill()
        self.proc.wait(5)
        self.proc.stderr.close()

    def read(self, size):
        data = b''
        while len(data) < size:
            chunk = self.server.recv(size - len(data))
            if not chunk:
                break
            data += chunk
        return data

    def state(self, seq, timeout=5):
        path = self.session / 'agent/ime.json'
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                snapshot = json.loads(path.read_text())
            except (OSError, ValueError):
                snapshot = None
            if snapshot and snapshot['seq'] >= seq:
                return snapshot
            time.sleep(0.02)
        self.fail('ime.json never reached seq %d' % seq)

    def test_focus_and_blur_are_reported(self):
        self.server.sendall(message(3, 0, struct.pack('<I', 100)))
        self.assertTrue(self.state(1)['textField'])
        self.server.sendall(message(100, 2, struct.pack('<II', 0, 9)))
        snapshot = self.state(2)
        self.assertEqual((snapshot['textField'], snapshot['purpose']), (True, 9))
        self.server.sendall(message(3, 1, struct.pack('<I', 100)))
        self.assertEqual(self.read(8), message(100, 0))
        self.assertFalse(self.state(3)['textField'])
        self.assertEqual(oct((self.session / 'agent/ime.json').stat().st_mode & 0o777), '0o600')

    def test_switching_fields_does_not_flicker(self):
        self.server.sendall(message(3, 0, struct.pack('<I', 100)))
        self.assertTrue(self.state(1)['textField'])
        self.server.sendall(message(3, 1, struct.pack('<I', 100)) + message(3, 0, struct.pack('<I', 101)))
        self.assertEqual(self.read(8), message(100, 0))
        time.sleep(0.4)
        snapshot = self.state(2)
        self.assertTrue(snapshot['textField'])
        self.assertEqual(snapshot['hint'], 0)
        self.assertEqual(snapshot['purpose'], 0)
        self.assertTrue(snapshot['context'])

    def request(self, snapshot, action, text, key="wl:one", context=None, cursor=1):
        directory = self.session / "agent/window-text-requests"
        directory.mkdir(exist_ok=True)
        (self.session / "agent/window-host.json").write_text(
            json.dumps({"activeKey": "wl:one", "textField": True}))
        (directory / "001.json").write_text(json.dumps({
            "key": key, "context": context or snapshot["context"],
            "action": action, "text": text, "cursor": cursor,
        }))

    def test_unicode_commit_uses_the_current_context_serial(self):
        self.server.sendall(message(3, 0, struct.pack('<I', 100)) +
                            message(100, 4, struct.pack('<I', 42)))
        snapshot = self.state(1)
        self.request(snapshot, "commit", "中文🙂")
        payload = struct.pack('<I', 42) + wl_string("中文🙂")
        self.assertEqual(self.read(8 + len(payload)), message(100, 1, payload))

    def test_preedit_cursor_uses_utf8_byte_offset(self):
        self.server.sendall(message(3, 0, struct.pack('<I', 100)))
        snapshot = self.state(1)
        self.request(snapshot, "preedit", "中文🙂")
        self.assertEqual(self.read(12), message(100, 4, struct.pack("<i", 10)))
        payload = struct.pack("<I", 0) + wl_string("中文🙂") + wl_string("")
        self.assertEqual(self.read(8 + len(payload)), message(100, 2, payload))

    def test_old_context_and_other_window_cannot_receive_text(self):
        self.server.sendall(message(3, 0, struct.pack('<I', 100)))
        snapshot = self.state(1)
        self.request(snapshot, "commit", "wrong", key="wl:two")
        self.server.settimeout(0.3)
        with self.assertRaises(socket.timeout):
            self.server.recv(100)
        self.request(snapshot, "commit", "stale", context="old-context")
        with self.assertRaises(socket.timeout):
            self.server.recv(100)

    def test_compositor_exit_clears_the_field(self):
        self.server.sendall(message(3, 0, struct.pack('<I', 100)))
        self.assertTrue(self.state(1)['textField'])
        self.server.close()
        self.assertEqual(self.proc.wait(5), 0)
        self.assertFalse(self.state(2)['textField'])


    def test_a_protocol_error_clears_the_field(self):
        self.server.sendall(message(3, 0, struct.pack('<I', 100)))
        self.assertTrue(self.state(1)['textField'])
        self.server.sendall(message(1, 0, struct.pack('<II', 3, 1) + wl_string('bad request')))
        self.assertEqual(self.proc.wait(5), 1)
        self.assertFalse(self.state(2)['textField'])


class AccessibilityTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        cls = runpy.run_path(str(IME))["Accessibility"]
        self.bridge = cls.__new__(cls)
        self.bridge.directory = self.directory
        self.bridge.key = ""
        self.bridge.token = "old"
        self.bridge.api = SimpleNamespace(STATE_FOCUSED=1, STATE_EDITABLE=2, STATE_ENABLED=3,
                                          ROLE_PASSWORD_TEXT=4)
        self.bridge.loop = SimpleNamespace(pending=lambda: False)
        self.values = "before"
        self.caret = len(self.values)
        self.selection = None
        self.units = "unicode"
        self.flags = {1, 2, 3}
        self.target = SimpleNamespace(
            getState=lambda: SimpleNamespace(contains=lambda s: s in self.flags),
            get_interfaces=lambda: ["Text", "EditableText"],
            getApplication=lambda: SimpleNamespace(get_process_id=lambda: 42),
            getRole=lambda: 0, name="", parent=SimpleNamespace(name="Editor A", parent=None),
            queryText=lambda: self, queryEditableText=lambda: self,
        )
        self.bridge.target = self.target
        self.host = {"textField": False, "activeKey": "wl:a",
                     "managed": [{"key": "wl:a", "pid": 42}]}

    @property
    def characterCount(self):
        return len(self.values.encode("utf-16-le", "surrogatepass")) // 2 \
            if self.units.startswith("utf16") else len(self.values)

    @property
    def caretOffset(self):
        return self.caret

    def getNSelections(self):
        return int(self.selection is not None)

    def getSelection(self, index):
        return self.selection

    def deleteText(self, start, end):
        if self.units.startswith("utf16"):
            raw = self.values.encode("utf-16-le", "surrogatepass")
            self.values = (raw[:start * 2] + raw[end * 2:]).decode("utf-16-le", "surrogatepass")
        else:
            self.values = self.values[:start] + self.values[end:]
        self.selection = None
        return True

    def getText(self, start, end):
        if self.units.startswith("utf16"):
            return self.values.encode("utf-16-le", "surrogatepass")[start * 2:end * 2].decode(
                "utf-16-le", "surrogatepass")
        return self.values[start:end]

    def insertText(self, offset, value, length):
        if self.units == "utf8":
            inserted = value.encode()[:length].decode(errors="ignore")
        elif self.units.startswith("utf16"):
            raw = value.encode("utf-16-le")
            if self.units == "utf16-resize":
                raw = raw.ljust(length * 2, b"\0")
            inserted = raw[:length * 2].decode("utf-16-le", "surrogatepass")
        else:
            inserted = value[:length]
        if self.units.startswith("utf16"):
            raw = self.values.encode("utf-16-le", "surrogatepass")
            self.values = (raw[:offset * 2] + inserted.encode("utf-16-le", "surrogatepass") +
                           raw[offset * 2:]).decode("utf-16-le", "surrogatepass")
        else:
            self.values = self.values[:offset] + inserted + self.values[offset:]
        return True

    def setCaretOffset(self, offset):
        self.caret = offset
        return True

    def request(self, text):
        state = self.bridge.state(self.host)
        return {"key": "wl:a", "context": state["token"], "action": "commit", "text": text}

    def test_unicode_inserts_at_caret_and_replaces_selection(self):
        self.caret = 3
        self.assertTrue(self.bridge.commit(self.request("中文🙂"), self.host))
        self.assertEqual(self.values, "bef中文🙂ore")
        self.assertEqual(self.caret, 6)
        self.selection = (0, len(self.values))
        self.assertTrue(self.bridge.commit(self.request("新文本"), self.host))
        self.assertEqual((self.values, self.caret), ("新文本", 3))

    def test_supplementary_characters_remain_whole_for_all_provider_units(self):
        for units in ["unicode", "utf16", "utf8", "utf16-resize"]:
            with self.subTest(units=units):
                self.units = units
                self.values = "before"
                self.caret = 3
                self.assertTrue(self.bridge.commit(self.request("中文🙂"), self.host))
                self.assertEqual(self.values, "bef中文🙂ore")
                self.assertEqual(self.caret, 7 if units.startswith("utf16") else 6)

    def test_utf16_padding_is_removed_before_the_next_commit_and_selection(self):
        self.units = "utf16-resize"
        self.values = ""
        self.caret = 0
        for value in ["🙂", "1", "2", "你"]:
            self.assertTrue(self.bridge.commit(self.request(value), self.host))
        self.assertEqual((self.values, self.caret), ("🙂12你", 5))
        self.selection = (2, 4)
        self.assertTrue(self.bridge.commit(self.request("中文🙂"), self.host))
        self.assertEqual((self.values, self.caret), ("🙂中文🙂你", 6))

    def test_readonly_and_unfocused_fields_do_not_trigger(self):
        self.flags.remove(2)
        self.assertIsNone(self.bridge.state(self.host))
        self.flags = {2, 3}
        self.assertIsNone(self.bridge.state(self.host))

    def test_wayland_field_has_priority(self):
        self.host["textField"] = True
        self.assertIsNone(self.bridge.state(self.host))

    def test_other_window_and_stale_context_cannot_receive_text(self):
        request = self.request("wrong")
        self.host["activeKey"] = "wl:b"
        self.assertFalse(self.bridge.commit(request, self.host))
        self.host["activeKey"] = "wl:a"
        request["context"] = "expired"
        self.assertFalse(self.bridge.commit(request, self.host))
        self.assertEqual(self.values, "before")

    def test_same_process_windows_require_matching_ancestor_title(self):
        self.host["managed"].append({"key": "wl:b", "pid": 42})
        (self.directory / "windows.json").write_text(json.dumps({
            "windows": [{"key": "wl:a", "title": "Editor A"}, {"key": "wl:b", "title": "Editor B"}]}))
        self.assertIsNotNone(self.bridge.state(self.host))
        self.host["activeKey"] = "wl:b"
        self.assertIsNone(self.bridge.state(self.host))

if __name__ == '__main__':
    unittest.main()
