"""The guest agent: its request files, exec, errors and the DevTools client, without a session."""
import base64
import hashlib
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
import tracemalloc

SCRIPT = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-agent"
loader = importlib.machinery.SourceFileLoader("droiddeck_agent", str(SCRIPT))
spec = importlib.util.spec_from_loader(loader.name, loader)
agent = importlib.util.module_from_spec(spec)
loader.exec_module(agent)


class FakeDevTools(threading.Thread):
    """GET /json lists one target; its WebSocket answers Runtime.evaluate the way Chromium does."""

    def __init__(self, answer):
        super().__init__(daemon=True)
        self.answer = answer
        self.server = socket.socket()
        self.server.bind(("127.0.0.1", 0))
        self.server.listen(4)
        self.port = self.server.getsockname()[1]
        self.seen = []

    def run(self):
        while True:
            try:
                conn, _ = self.server.accept()
            except OSError:
                return
            threading.Thread(target=self.serve, args=(conn,), daemon=True).start()

    def serve(self, conn):
        head = b""
        while b"\r\n\r\n" not in head:
            head += conn.recv(4096)
        request = head.decode()
        if request.startswith("GET /json"):
            body = json.dumps([{"title": "SharedJSContext", "url": "https://steamloopback.host/", "type": "page",
                                "webSocketDebuggerUrl": "ws://127.0.0.1:%d/devtools/page/1" % self.port}]).encode()
            conn.sendall(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: %d\r\n\r\n" % len(body) + body)
            conn.close()
            return
        key = [l.split(":", 1)[1].strip() for l in request.split("\r\n") if l.lower().startswith("sec-websocket-key")][0]
        accept = base64.b64encode(hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
        conn.sendall(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                      "Sec-WebSocket-Accept: %s\r\n\r\n" % accept).encode())
        b0, b1 = conn.recv(2)
        n = b1 & 0x7F
        if n == 126:
            n = struct.unpack(">H", conn.recv(2))[0]
        mask = conn.recv(4)
        data = b""
        while len(data) < n:
            data += conn.recv(n - len(data))
        message = json.loads(bytes(b ^ mask[i % 4] for i, b in enumerate(data)))
        self.seen.append(message)
        for reply in ({"method": "Runtime.consoleAPICalled", "params": {}}, dict(self.answer, id=message["id"])):
            payload = json.dumps(reply).encode()
            header = bytes([0x81, 126]) + struct.pack(">H", len(payload)) if len(payload) > 125 else bytes([0x81, len(payload)])
            conn.sendall(header + payload)
        time.sleep(0.2)
        conn.close()


class AgentTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.dir = Path(self.temp.name)
        (self.dir / "resp").mkdir()

    def ask(self, request):
        path = self.dir / "req.json"
        path.write_text(json.dumps(request))
        agent.answer(self.dir, path)
        self.assertFalse(path.exists(), "the request file is consumed")
        return json.loads((self.dir / "resp" / "req.json").read_text())

    def test_exec_output_and_status(self):
        answer = self.ask({"kind": "exec", "argv": ["sh", "-c", "echo out; echo err >&2; exit 3"], "env": {"X": "1"}})
        self.assertTrue(answer["ok"])
        self.assertEqual(3, answer["exitCode"])
        self.assertEqual("out\n", answer["stdout"]["text"])
        self.assertEqual("err\n", answer["stderr"]["text"])
        self.assertFalse(answer["timedOut"])

    def test_exec_environment_stdin_and_cap(self):
        answer = self.ask({"kind": "exec", "argv": ["sh", "-c", "printf %s \"$X\"; cat"], "env": {"X": "a"}, "stdin": "b"})
        self.assertEqual("ab", answer["stdout"]["text"])
        big = self.ask({"kind": "exec", "argv": ["sh", "-c", "head -c 300000 /dev/zero"]})
        self.assertTrue(big["stdout"]["truncated"])
        self.assertEqual(agent.OUTPUT_CAP, len(big["stdout"]["text"]))

    def test_exec_timeout_kills_the_process_group(self):
        started = time.monotonic()
        answer = self.ask({"kind": "exec", "argv": ["sh", "-c", "sleep 30 & sleep 30"], "timeout": 0.5})
        self.assertTrue(answer["timedOut"])
        self.assertLess(time.monotonic() - started, 10)

    def test_exec_memory_is_bounded_while_draining_large_output(self):
        tracemalloc.start()
        try:
            answer = agent.run_exec({"argv": [sys.executable, "-c", "import os; [os.write(1, b'x' * 65536) for _ in range(1024)]"]})
            _, peak = tracemalloc.get_traced_memory()
        finally:
            tracemalloc.stop()
        self.assertEqual(0, answer["exitCode"])
        self.assertEqual(agent.OUTPUT_CAP, len(answer["stdout"]["text"]))
        self.assertTrue(answer["stdout"]["truncated"])
        self.assertLess(peak, 6 * 1024 * 1024)

    def test_exec_drains_both_streams_while_writing_stdin(self):
        program = "import os, sys; os.write(1, b'x' * 300000); print(len(sys.stdin.read()), file=sys.stderr)"
        answer = self.ask({"kind": "exec", "argv": [sys.executable, "-c", program], "stdin": "y" * 100000})
        self.assertFalse(answer["timedOut"])
        self.assertTrue(answer["stdout"]["truncated"])
        self.assertEqual("100000\n", answer["stderr"]["text"])

    def test_flooding_both_streams_still_times_out(self):
        program = "import os\nwhile True:\n os.write(1, b'o' * 65536)\n os.write(2, b'e' * 65536)"
        answer = self.ask({"kind": "exec", "argv": [sys.executable, "-c", program], "timeout": 0.5})
        self.assertTrue(answer["timedOut"])
        for name in ("stdout", "stderr"):
            self.assertEqual(agent.OUTPUT_CAP, len(answer[name]["text"]))
            self.assertTrue(answer[name]["truncated"])

    def test_timeout_applies_when_parent_exits_with_inherited_pipes(self):
        started = time.monotonic()
        answer = self.ask({"kind": "exec", "argv": ["sh", "-c", "sleep 30 & exit 0"], "timeout": 0.5})
        self.assertTrue(answer["timedOut"])
        self.assertLess(time.monotonic() - started, 5)

    def test_invalid_timeout_and_input_are_refused_before_exec(self):
        for value in (0, -1, 601, "NaN", "Infinity", "invalid"):
            answer = self.ask({"kind": "exec", "argv": ["true"], "timeout": value})
            self.assertEqual("INVALID_REQUEST", answer["error"]["code"])
        for fields in ({"stdin": 1}, {"env": []}, {"env": {"BAD=KEY": "value"}}, {"argv": ["true\0"]}):
            answer = self.ask({"kind": "exec", "argv": ["true"], **fields})
            self.assertEqual("INVALID_REQUEST", answer["error"]["code"])

    def test_errors_are_answers(self):
        self.assertEqual("INVALID_REQUEST", self.ask({"kind": "exec", "argv": []})["error"]["code"])
        self.assertEqual("EXEC_FAILED", self.ask({"kind": "exec", "argv": ["/no/such/program"]})["error"]["code"])
        self.assertEqual("UNKNOWN_REQUEST", self.ask({"kind": "nope"})["error"]["code"])
        self.assertEqual("INVALID_APP_ID", self.ask({"kind": "quit", "appId": "x"})["error"]["code"])
        (self.dir / "req.json").write_text("{not json")
        agent.answer(self.dir, self.dir / "req.json")
        self.assertEqual("INVALID_REQUEST", json.loads((self.dir / "resp" / "req.json").read_text())["error"]["code"])

    def test_request_shape_and_size_are_bounded(self):
        self.assertEqual("INVALID_REQUEST", self.ask([])["error"]["code"])
        self.assertEqual("INVALID_REQUEST", self.ask({"kind": "ping", "unused": "x" * agent.REQUEST_CAP})["error"]["code"])

    def test_replies_are_owner_only_even_when_replacing_a_public_file(self):
        path = self.dir / "private.json"
        path.write_text("old")
        path.chmod(0o644)
        agent.write_json(path, {"ok": True})
        self.assertEqual(0o600, path.stat().st_mode & 0o777)

    def test_oversized_websocket_frames_are_refused_before_reading_payload(self):
        ws = agent.WebSocket.__new__(agent.WebSocket)
        ws.buffer = bytes([0x81, 127]) + struct.pack(">Q", agent.RESPONSE_CAP + 1)
        with self.assertRaises(agent.AgentError) as caught:
            ws.recv()
        self.assertEqual("RESPONSE_TOO_LARGE", caught.exception.code)

    def test_devtools_targets_cannot_connect_to_remote_hosts(self):
        for url in ("ws://example.com/devtools", "ws://127.0.0.1@example.com/devtools", "wss://127.0.0.1/devtools"):
            with patch.object(agent.socket, "create_connection") as connect:
                with self.assertRaises(agent.AgentError) as caught:
                    agent.WebSocket(url, 1)
                self.assertEqual("INVALID_TARGET", caught.exception.code)
                connect.assert_not_called()

    def test_cdp_without_a_port(self):
        os.environ.pop("BL_CDP_PORT", None)
        self.assertEqual("NO_DEVTOOLS", self.ask({"kind": "cdp", "expression": "1"})["error"]["code"])

    def test_cdp_eval(self):
        server = FakeDevTools({"result": {"result": {"type": "number", "value": 42}}})
        server.start()
        self.addCleanup(server.server.close)
        (self.dir / "cdp-port").write_text("%d\n" % server.port)
        answer = self.ask({"kind": "cdp", "expression": "6 * 7"})
        self.assertTrue(answer["ok"], answer)
        self.assertEqual(42, answer["value"])
        self.assertEqual("SharedJSContext", answer["target"])
        self.assertEqual("Runtime.evaluate", server.seen[0]["method"])
        self.assertEqual("6 * 7", server.seen[0]["params"]["expression"])
        targets = self.ask({"kind": "cdp", "op": "targets"})
        self.assertEqual("SharedJSContext", targets["targets"][0]["title"])
        missing = self.ask({"kind": "cdp", "expression": "1", "target": "Nope"})
        self.assertEqual("NO_TARGET", missing["error"]["code"])

    def test_steam_selection(self):
        with patch.object(agent, "cdp_eval", return_value={"value": "3180310"}) as evaluate:
            answer = self.ask({"kind": "steam-selection"})
        self.assertEqual(3180310, answer["appId"])
        self.assertIn("document.activeElement", evaluate.call_args.args[1])
        self.assertEqual("Steam Big Picture Mode", evaluate.call_args.kwargs["target"])

    def test_steam_selection_allows_no_highlight(self):
        with patch.object(agent, "cdp_eval", return_value={"value": None}):
            self.assertIsNone(self.ask({"kind": "steam-selection"})["appId"])

    def test_steam_selection_rejects_invalid_app_id(self):
        with patch.object(agent, "cdp_eval", return_value={"value": "4294967296"}):
            answer = self.ask({"kind": "steam-selection"})
        self.assertEqual("INVALID_SELECTION", answer["error"]["code"])

    def test_cdp_exception(self):
        server = FakeDevTools({"result": {"result": {"type": "object"}, "exceptionDetails": {
            "text": "Uncaught", "exception": {"description": "ReferenceError: SteamClient is not defined"}}}})
        server.start()
        self.addCleanup(server.server.close)
        (self.dir / "cdp-port").write_text(str(server.port))
        answer = self.ask({"kind": "cdp", "expression": "SteamClient"})
        self.assertEqual("JS_EXCEPTION", answer["error"]["code"])
        self.assertIn("ReferenceError", answer["error"]["message"])

    def test_serve_answers_request_files(self):
        server = subprocess.Popen([sys.executable, str(SCRIPT), "--no-focus"],
                                  env=dict(os.environ, BL_LAUNCH_DIR=str(self.dir)),
                                  stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        def stop_server():
            server.terminate()
            server.wait(timeout=5)
        self.addCleanup(stop_server)
        agent_dir = self.dir / "agent"
        for _ in range(100):
            if (agent_dir / "hello.json").exists():
                break
            time.sleep(0.02)
        hello = json.loads((agent_dir / "hello.json").read_text())
        self.assertEqual(server.pid, hello["pid"])
        self.assertFalse(hello["focus"])
        (agent_dir / "req" / "a.json.tmp").write_text(json.dumps({"kind": "ping"}))
        os.replace(agent_dir / "req" / "a.json.tmp", agent_dir / "req" / "a.json")
        for _ in range(200):
            if (agent_dir / "resp" / "a.json").exists():
                break
            time.sleep(0.02)
        self.assertEqual(agent.VERSION, json.loads((agent_dir / "resp" / "a.json").read_text())["version"])

    def test_game_pids_reads_the_steam_environment(self):
        import subprocess
        if not Path("/proc").is_dir():
            self.skipTest("needs /proc")
        child = subprocess.Popen(["sleep", "30"], env=dict(os.environ, SteamAppId="424242"))
        self.addCleanup(lambda: (child.kill(), child.wait()))
        time.sleep(0.2)
        self.assertIn(child.pid, agent.game_pids(424242))


if __name__ == "__main__":
    unittest.main()
