"""The guest agent: its request files, exec, errors and the DevTools client, without a session."""
import base64
import hashlib
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import shutil
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


class FakeSteamClient(threading.Thread):

    def __init__(self, showing):
        super().__init__(daemon=True)
        self.showing = showing
        self.server = socket.socket()
        self.server.bind(("127.0.0.1", 0))
        self.server.listen(4)
        self.port = self.server.getsockname()[1]
        self.seen = []
        self.conn = None
        self.lock = threading.Lock()

    def run(self):
        while True:
            try:
                conn, _ = self.server.accept()
            except OSError:
                return
            threading.Thread(target=self.serve, args=(conn,), daemon=True).start()

    def send(self, conn, message):
        payload = json.dumps(message).encode()
        header = bytes([0x81, 126]) + struct.pack(">H", len(payload)) if len(payload) > 125 else bytes([0x81, len(payload)])
        with self.lock:
            conn.sendall(header + payload)

    def push(self, payload, name=None):
        self.send(self.conn, {"method": "Runtime.bindingCalled",
                              "params": {"name": name or agent.KEYBOARD_BINDING, "payload": payload}})

    def drop(self):
        self.conn.shutdown(socket.SHUT_RDWR)
        self.conn.close()

    def serve(self, conn):
        stream = conn.makefile("rb")
        head = b""
        while not head.endswith(b"\r\n\r\n"):
            head += stream.read(1)
        request = head.decode()
        if request.startswith("GET /json"):
            body = json.dumps([{"title": "Steam Big Picture Mode", "url": "https://steamloopback.host/routes/",
                                "webSocketDebuggerUrl": "ws://127.0.0.1:%d/devtools/page/2" % self.port},
                               {"title": "SharedJSContext", "url": "https://steamloopback.host/",
                                "webSocketDebuggerUrl": "ws://127.0.0.1:%d/devtools/page/1" % self.port}]).encode()
            conn.sendall(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: %d\r\n\r\n" % len(body) + body)
            conn.close()
            return
        key = [l.split(":", 1)[1].strip() for l in request.split("\r\n") if l.lower().startswith("sec-websocket-key")][0]
        accept = base64.b64encode(hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
        conn.sendall(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                      "Sec-WebSocket-Accept: %s\r\n\r\n" % accept).encode())
        self.conn = conn
        try:
            while True:
                b0, b1 = stream.read(2)
                n = b1 & 0x7F
                if n == 126:
                    n = struct.unpack(">H", stream.read(2))[0]
                elif n == 127:
                    n = struct.unpack(">Q", stream.read(8))[0]
                mask = stream.read(4)
                data = stream.read(n)
                message = json.loads(bytes(b ^ mask[i % 4] for i, b in enumerate(data)))
                self.seen.append(message)
                self.push("true", name="unrelated")
                result = {"result": {"type": "boolean", "value": self.showing}} if message["method"] == "Runtime.evaluate" else {}
                self.send(conn, {"id": message["id"], "result": result})
        except (OSError, ValueError):
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

    def vr_helper(self, body):
        helper = self.dir / "droiddeck-steamvr"
        helper.write_text("#!/bin/sh\n" + body)
        old = os.environ.get("DROIDDECK_VR_HELPER")
        os.environ["DROIDDECK_VR_HELPER"] = str(helper)
        self.addCleanup(lambda: os.environ.pop("DROIDDECK_VR_HELPER") if old is None
                        else os.environ.__setitem__("DROIDDECK_VR_HELPER", old))
        return helper

    def test_vr_status_is_parsed(self):
        self.vr_helper('echo "installed=1 running=0 installing=0 driver=1"\n')
        answer = self.ask({"kind": "vr", "action": "status"})
        self.assertTrue(answer["ok"])
        self.assertEqual((True, False, False, True),
                         (answer["installed"], answer["running"], answer["installing"], answer["driver"]))

    def test_vr_runs_only_its_own_actions(self):
        log = self.dir / "args"
        self.vr_helper('echo "$@" > %s\n' % log)
        self.assertTrue(self.ask({"kind": "vr", "action": "start"})["ok"])
        self.assertEqual("start", log.read_text().strip())
        answer = self.ask({"kind": "vr", "action": "rm -rf /"})
        self.assertFalse(answer["ok"])
        self.assertEqual("INVALID_REQUEST", answer["error"]["code"])

    def test_vr_without_a_helper_is_unavailable(self):
        os.environ["DROIDDECK_VR_HELPER"] = str(self.dir / "missing")
        self.addCleanup(os.environ.pop, "DROIDDECK_VR_HELPER")
        answer = self.ask({"kind": "vr", "action": "status"})
        self.assertEqual("UNAVAILABLE", answer["error"]["code"])

    def fake_proc(self, pid, parent, comm, env):
        root = self.dir / "proc" / str(pid)
        root.mkdir(parents=True)
        (root / "comm").write_text(comm + "\n")
        (root / "stat").write_text("%d (%s) S %d 0 0" % (pid, comm, parent))
        (root / "environ").write_bytes(b"\0".join(b"%s=%s" % (k.encode(), v.encode()) for k, v in env.items()) + b"\0")

    def tag(self, wins, arch="arm64"):
        snap = {"displays": {":0": [], ":1": wins}}
        with patch.dict(os.environ, {"DISPLAY": ":0", "BL_STEAM_CLIENT_ARCH": arch}), \
                patch.object(agent.subprocess, "run") as run, patch.object(agent, "log"):
            run.return_value.returncode = 0
            tagged = agent.tag_game_windows(snap, proc=self.dir / "proc")
        return tagged, [call.args[0][-1] for call in run.call_args_list]

    def test_a_sandboxed_flatpak_window_is_tagged_with_its_shortcut(self):
        self.fake_proc(40, 1, "gjs", {"FLATPAK_ID": "com.github.tchx84.Flatseal", "SteamAppId": "2166984148"})
        win = {"window": 7, "map": "viewable", "class": "com.github.tchx84.Flatseal", "pid": None}
        self.assertEqual(self.tag([win]), (True, ["2166984148"]))

    def test_a_flatpak_window_named_after_the_app_is_matched(self):
        self.fake_proc(40, 1, "vacuumtube", {"FLATPAK_ID": "rocks.shy.VacuumTube", "SteamAppId": "3000000001"})
        win = {"window": 7, "map": "viewable", "class": "VacuumTube", "pid": None}
        self.assertEqual(self.tag([win]), (True, ["3000000001"]))

    def test_a_window_whose_process_has_no_app_id_falls_back_to_its_flatpak(self):
        self.fake_proc(40, 1, "bwrap", {"FLATPAK_ID": "com.github.tchx84.Flatseal", "SteamAppId": "2166984148"})
        self.fake_proc(41, 1, "gjs", {})
        win = {"window": 7, "map": "viewable", "class": "com.github.tchx84.Flatseal", "pid": 41}
        self.assertEqual(self.tag([win]), (True, ["2166984148"]))

    def test_a_flatpak_shared_by_two_shortcuts_is_not_guessed(self):
        self.fake_proc(40, 1, "gjs", {"FLATPAK_ID": "com.github.tchx84.Flatseal", "SteamAppId": "2166984148"})
        self.fake_proc(42, 1, "gjs", {"FLATPAK_ID": "com.github.tchx84.Flatseal", "SteamAppId": "3000000003"})
        win = {"window": 7, "map": "viewable", "class": "com.github.tchx84.Flatseal", "pid": None}
        self.assertEqual(self.tag([win]), (False, []))

    def test_a_window_outside_the_reaper_takes_its_process_app_id(self):
        self.fake_proc(30, 1, "proot", {})
        self.fake_proc(31, 30, "electron", {"SteamAppId": "3000000002"})
        win = {"window": 8, "map": "viewable", "class": "electron", "pid": 31}
        self.assertEqual(self.tag([win]), (True, ["3000000002"]))

    def test_windows_gamescope_already_resolves_are_left_alone(self):
        self.fake_proc(20, 1, "reaper", {})
        self.fake_proc(21, 20, "game", {"SteamAppId": "413150"})
        wins = [{"window": 9, "map": "viewable", "class": "game", "pid": 21},
                {"window": 10, "map": "viewable", "class": "tagged", "pid": None, "appId": 5},
                {"window": 11, "map": "viewable", "class": "other", "pid": None},
                {"window": 12, "map": "unmapped", "class": "game", "pid": 21},
                {"window": 13, "map": "viewable", "class": "steam_app_550", "pid": None}]
        self.assertEqual(self.tag(wins), (False, []))

    def test_the_x86_client_still_tags_its_game_classes(self):
        win = {"window": 13, "map": "viewable", "class": "steam_app_550", "pid": None}
        self.assertEqual(self.tag([win], arch="x86_64"), (True, ["550"]))

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

    def test_shortcut_cgameid_waits_for_exact_steam_overview_mapping(self):
        game_id = str((3551182975 << 32) | 0x02000000)
        self.assertEqual(3551182975, agent.steam_shortcut_app_id(game_id))
        self.assertIsNone(agent.steam_shortcut_app_id("413150"))
        self.assertIsNone(agent.steam_shortcut_app_id(str((3551182975 << 32) | 0x03000000)))
        clock = [0.0]
        with patch.object(agent, "cdp_eval", side_effect=[{"value": None}, {"value": "3551182975"}]) as evaluate, \
             patch.object(agent.time, "monotonic", side_effect=lambda: clock[0]), \
             patch.object(agent.time, "sleep", side_effect=lambda delay: clock.__setitem__(0, clock[0] + delay)):
            result = agent.wait_for_steam_shortcut_overview(self.dir, game_id, timeout=1)
        self.assertEqual({"supported": True, "ready": True, "appId": 3551182975}, result)
        expression = evaluate.call_args_list[0].args[1]
        self.assertIn("GetAppOverviewByGameID", expression)
        self.assertIn(json.dumps(game_id), expression)
        self.assertNotIn("RequestAppDetails", expression)
        self.assertFalse(evaluate.call_args_list[0].kwargs["await_promise"])

    def test_wrong_overview_app_id_keeps_shortcut_url_held_until_timeout(self):
        game_id = str((3537741571 << 32) | 0x02000000)
        clock = [0.0]
        with patch.object(agent, "cdp_eval", return_value={"value": "646570"}) as evaluate, \
             patch.object(agent.time, "monotonic", side_effect=lambda: clock[0]), \
             patch.object(agent.time, "sleep", side_effect=lambda delay: clock.__setitem__(0, clock[0] + delay)):
            with self.assertRaises(agent.AgentError) as raised:
                agent.wait_for_steam_shortcut_overview(self.dir, game_id, timeout=0.5)
        self.assertEqual("STEAM_OVERVIEW_TIMEOUT", raised.exception.code)
        self.assertGreater(evaluate.call_count, 0)
        self.assertTrue(all("RequestAppDetails" not in call.args[1] for call in evaluate.call_args_list))

    def test_normal_steam_ids_and_unknown_cgameids_skip_overview_wait(self):
        for game_id in ("413150", str((123456789 << 32) | 0x03000000), "18446744073709551616"):
            with self.subTest(game_id=game_id), patch.object(agent, "cdp_eval") as evaluate:
                result = agent.wait_for_steam_shortcut_overview(self.dir, game_id)
            self.assertFalse(result["supported"])
            evaluate.assert_not_called()

    def test_shortcut_state_tells_a_lost_shortcut_from_a_loading_library(self):
        game_id = str((3333273269 << 32) | 0x02000000)
        self.assertEqual("not-shortcut", agent.steam_shortcut_state(self.dir, "413150"))
        for values, wanted, calls in ((["loading", "ready"], "ready", 2), (["loading", "missing"], "missing", 3),
                                      ([None], "loading", 3)):
            clock = [0.0]
            replies = [{"value": v} for v in values] + [{"value": values[-1]}] * 10
            with self.subTest(values=values), patch.object(agent, "cdp_eval", side_effect=replies) as evaluate, \
                 patch.object(agent.time, "monotonic", side_effect=lambda: clock[0]), \
                 patch.object(agent.time, "sleep", side_effect=lambda delay: clock.__setitem__(0, clock[0] + delay)):
                self.assertEqual(wanted, agent.steam_shortcut_state(self.dir, game_id, timeout=1))
            self.assertEqual(calls, evaluate.call_count)
            self.assertIn("GetAppOverviewByGameID(%s)" % json.dumps(game_id), evaluate.call_args_list[0].args[1])

    def test_shortcut_state_waits_out_an_unreachable_client_but_not_other_errors(self):
        game_id = str((3333273269 << 32) | 0x02000000)
        clock = [0.0]
        with patch.object(agent, "cdp_eval", side_effect=agent.AgentError("NO_DEVTOOLS", "down")), \
             patch.object(agent.time, "monotonic", side_effect=lambda: clock[0]), \
             patch.object(agent.time, "sleep", side_effect=lambda delay: clock.__setitem__(0, clock[0] + delay)):
            self.assertEqual("loading", agent.steam_shortcut_state(self.dir, game_id, timeout=1))
        with patch.object(agent, "cdp_eval", side_effect=agent.AgentError("CDP_EXCEPTION", "boom")):
            with self.assertRaises(agent.AgentError):
                agent.steam_shortcut_state(self.dir, game_id, timeout=1)

    def test_shortcut_state_command_exits_with_the_state(self):
        game_id = str((3333273269 << 32) | 0x02000000)
        launch = self.dir.parent
        with patch.dict(os.environ, {"BL_LAUNCH_DIR": str(launch)}):
            for state, code in (("ready", 0), ("missing", 4), ("not-shortcut", 3), ("loading", 1)):
                with self.subTest(state=state), patch.object(agent, "steam_shortcut_state", return_value=state) as ask:
                    self.assertEqual(code, agent.main(["--steam-shortcut-state", game_id, "2"]))
                self.assertEqual((launch / "agent", game_id, "2"), ask.call_args.args)
            with patch.object(agent, "steam_shortcut_state", side_effect=agent.AgentError("CDP_EXCEPTION", "boom")):
                self.assertEqual(1, agent.main(["--steam-shortcut-state", game_id]))
            self.assertEqual(2, agent.main(["--steam-shortcut-state"]))

    def test_asleep_command_reports_steams_own_sleep_state(self):
        with patch.dict(os.environ, {"BL_LAUNCH_DIR": str(self.dir.parent)}):
            for value, code in (("yes", 0), ("no", 5), ("unknown", 1), (None, 1)):
                with self.subTest(value=value), patch.object(agent, "cdp_eval", return_value={"value": value}) as ask:
                    self.assertEqual(code, agent.main(["--steam-asleep"]))
                self.assertIn("m_bSuspending", ask.call_args.args[1])
                self.assertFalse(ask.call_args.kwargs["await_promise"])
            with patch.object(agent, "cdp_eval", side_effect=agent.AgentError("NO_DEVTOOLS", "down")):
                self.assertEqual(1, agent.main(["--steam-asleep"]))

    def test_supports_vr_command_separates_vr_flat_and_unknown_apps(self):
        with patch.dict(os.environ, {"BL_LAUNCH_DIR": str(self.dir.parent)}):
            for value, code in ((True, 0), (False, 5), (None, 1)):
                with self.subTest(value=value), patch.object(agent, "cdp_eval", return_value={"value": value}) as ask:
                    self.assertEqual(code, agent.main(["--steam-supports-vr", "204360"]))
                self.assertIn('GetAppOverviewByGameID?.("204360")', ask.call_args.args[1])
                self.assertIn("vr_supported", ask.call_args.args[1])
            with patch.object(agent, "cdp_eval", side_effect=agent.AgentError("NO_DEVTOOLS", "down")):
                self.assertEqual(1, agent.main(["--steam-supports-vr", "204360"]))
            for bad in ([], ["1;x"], ["-1"], ["1", "2"]):
                self.assertEqual(2, agent.main(["--steam-supports-vr"] + bad))

    def test_idle_inhibit_command_reports_whether_steam_reached_the_state(self):
        with patch.dict(os.environ, {"BL_LAUNCH_DIR": str(self.dir.parent)}):
            for value, code in ((True, 0), (False, 1), (None, 1)):
                with self.subTest(value=value), patch.object(agent, "cdp_eval", return_value={"value": value}) as ask:
                    self.assertEqual(code, agent.main(["--steam-idle-inhibit", "on"]))
                self.assertIn("toggle_idle_suspend_inhibit 1", ask.call_args.args[1])
                self.assertTrue(ask.call_args.args[1].rstrip().endswith("(true)"))
            with patch.object(agent, "cdp_eval", return_value={"value": True}) as ask:
                self.assertEqual(0, agent.main(["--steam-idle-inhibit", "off"]))
            self.assertTrue(ask.call_args.args[1].rstrip().endswith("(false)"))
            with patch.object(agent, "cdp_eval", side_effect=agent.AgentError("NO_DEVTOOLS", "down")):
                self.assertEqual(1, agent.main(["--steam-idle-inhibit", "on"]))
            self.assertEqual(2, agent.main(["--steam-idle-inhibit", "maybe"]))

    def test_idle_suspend_seconds_follow_the_power_source(self):
        vpower = self.dir / "ac_status"
        with patch.object(agent, "cdp_eval", return_value={"value": [3600, 900]}):
            vpower.write_text("Connected\n")
            self.assertEqual(3600, agent.steam_idle_suspend_seconds(self.dir, vpower))
            vpower.write_text("Disconnected\n")
            self.assertEqual(900, agent.steam_idle_suspend_seconds(self.dir, vpower))
        with patch.object(agent, "cdp_eval", return_value={"value": None}):
            self.assertIsNone(agent.steam_idle_suspend_seconds(self.dir, vpower))
        with patch.dict(os.environ, {"BL_LAUNCH_DIR": str(self.dir.parent)}), \
                patch.object(agent, "steam_idle_suspend_seconds", return_value=None):
            self.assertEqual(1, agent.main(["--steam-idle-suspend-seconds"]))

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


class SteamKeyboardTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.dir = Path(self.temp.name)

    def state(self):
        try:
            return json.loads((self.dir / "keyboard.json").read_text())
        except (OSError, ValueError):
            return None

    def wait_for(self, check):
        for _ in range(200):
            if check():
                return
            time.sleep(0.02)
        self.fail("timed out; keyboard.json is %s" % self.state())

    def test_report_writes_only_changes(self):
        watcher = agent.KeyboardWatcher(self.dir)
        watcher.report(False)
        self.assertEqual({"steamKeyboard": False, "seq": 1}, self.state())
        watcher.report(False)
        watcher.report(True)
        self.assertEqual({"steamKeyboard": True, "seq": 2}, self.state())

    def test_follows_the_clients_keyboard_and_rechecks_while_idle(self):
        client = FakeSteamClient(False)
        client.start()
        self.addCleanup(client.server.close)
        (self.dir / "cdp-port").write_text(str(client.port))
        watcher = agent.KeyboardWatcher(self.dir)
        failure = []
        with patch.object(agent, "KEYBOARD_CHECK_SECONDS", 0.3):
            thread = threading.Thread(target=lambda: failure.append(self.capture(watcher.watch)), daemon=True)
            thread.start()
            self.wait_for(lambda: self.state() == {"steamKeyboard": False, "seq": 1})
            self.assertEqual("Runtime.addBinding", client.seen[0]["method"])
            self.assertEqual(agent.KEYBOARD_BINDING, client.seen[0]["params"]["name"])
            self.assertIn("IsShowingVirtualKeyboard", client.seen[1]["params"]["expression"])
            self.assertIn(agent.KEYBOARD_BINDING, client.seen[1]["params"]["expression"])
            self.assertNotIn("__BINDING__", client.seen[1]["params"]["expression"])
            client.push("true")
            self.wait_for(lambda: (self.state() or {}).get("steamKeyboard") is True)
            client.push("false")
            self.wait_for(lambda: (self.state() or {}).get("steamKeyboard") is False)
            client.showing = True
            self.wait_for(lambda: (self.state() or {}).get("steamKeyboard") is True)
            self.assertGreaterEqual(len([m for m in client.seen if m["method"] == "Runtime.evaluate"]), 2)
            client.drop()
            thread.join(5)
        self.assertFalse(thread.is_alive())
        self.assertIsInstance(failure[0], OSError)

    def test_any_loss_reports_the_keyboard_closed_and_backs_off(self):
        watcher = agent.KeyboardWatcher(self.dir)
        watcher.report(True)

        def attached_then_lost():
            watcher.attached = True
            raise OSError("DevTools closed the connection")

        steps = iter([agent.AgentError("NO_DEVTOOLS", "no port"), agent.AgentError("NO_DEVTOOLS", "no port"),
                      attached_then_lost, KeyError("SteamUIStore")])

        def watch():
            step = next(steps, None)
            if step is None:
                raise SystemExit
            if callable(step):
                step()
            raise step

        with patch.object(watcher, "watch", side_effect=watch), patch.object(agent.time, "sleep") as sleep, \
                patch.object(agent, "log") as log:
            with self.assertRaises(SystemExit):
                watcher.run()
        self.assertEqual({"steamKeyboard": False, "seq": 2}, self.state())
        self.assertEqual([2, 4, 2, 4], [c.args[0] for c in sleep.call_args_list])
        self.assertFalse(watcher.attached)
        self.assertEqual(3, log.call_count)

    def test_requests_and_route_files(self):
        watcher = agent.KeyboardWatcher(self.dir)
        watcher.request()
        watcher.request()
        self.assertEqual({"seq": 2}, json.loads((self.dir / "keyboard-request.json").read_text()))
        self.assertFalse(watcher.wanted_route())
        (self.dir / "keyboard-route").write_text("bottom")
        self.assertTrue(watcher.wanted_route())
        (self.dir / "keyboard-route").write_text("top")
        self.assertFalse(watcher.wanted_route())
        watcher.event({"method": "Runtime.bindingCalled", "params": {"name": agent.KEYBOARD_BINDING, "payload": "request"}})
        self.assertEqual({"seq": 3}, json.loads((self.dir / "keyboard-request.json").read_text()))
        self.assertIsNone(self.state())

    def test_dismissals_are_written_for_the_app(self):
        watcher = agent.KeyboardWatcher(self.dir)
        watcher.event({"method": "Runtime.bindingCalled", "params": {"name": agent.KEYBOARD_BINDING, "payload": "dismiss"}})
        watcher.event({"method": "Runtime.bindingCalled", "params": {"name": agent.KEYBOARD_BINDING, "payload": "dismiss"}})
        self.assertEqual({"seq": 2}, json.loads((self.dir / "keyboard-dismiss.json").read_text()))
        self.assertFalse((self.dir / "keyboard-request.json").exists())

    @unittest.skipUnless(shutil.which("node"), "node is not installed")
    def test_page_script_reports_when_the_routed_field_loses_focus(self):
        harness = r"""
const assert = require('assert');
globalThis.window = globalThis;
const fs = require('fs');
const script = fs.readFileSync(process.argv[2], 'utf8');
const route = fs.readFileSync(process.argv[3], 'utf8');
const calls = [];
window.droiddeckSteamKeyboard = value => calls.push(value);
const open = { m_currentValue: false };
const vkm = {
  m_currentVirtualKeyboardRef: null,
  IsShowingVirtualKeyboard: { m_currentValue: false },
  m_bIsInlineVirtualKeyboardOpen: open,
  m_bIsVirtualKeyboardModal: { m_currentValue: false },
  BUseVRKeyboard: () => false,
  m_Instance: { IsDesktopOverlayWindow: () => false },
  SetActiveVirtualKeyboardTarget(ref, props) { this.m_currentVirtualKeyboardRef = ref; },
  SetVirtualKeyboardHidden() { open.m_currentValue = false; this.m_currentVirtualKeyboardRef = null; },
  ShowVirtualKeyboard(ref, props, modal) { this.m_currentVirtualKeyboardRef = ref; open.m_currentValue = true; },
};
globalThis.SteamUIStore = { WindowStore: { SteamUIWindows: [{ VirtualKeyboardManager: vkm }] } };
eval(script);
vkm.ShowVirtualKeyboard('a', {}, false);
eval(route.replace('__ROUTE__', 'true'));
assert.strictEqual(open.m_currentValue, false);
assert.strictEqual(vkm.m_currentVirtualKeyboardRef, 'a');
const wait = ms => new Promise(done => setTimeout(done, ms));
(async () => {
  await wait(500);
  assert.deepStrictEqual(calls.filter(c => c === 'dismiss'), []);
  vkm.m_currentVirtualKeyboardRef = null;
  await wait(500);
  assert.deepStrictEqual(calls.filter(c => c === 'dismiss'), ['dismiss']);
  vkm.ShowVirtualKeyboard('b', {}, false);
  assert.strictEqual(open.m_currentValue, false);
  vkm.m_currentVirtualKeyboardRef = null;
  await wait(160);
  vkm.ShowVirtualKeyboard('c', {}, false);
  await wait(500);
  assert.deepStrictEqual(calls.filter(c => c === 'dismiss'), ['dismiss']);
  vkm.m_currentVirtualKeyboardRef = null;
  await wait(500);
  assert.deepStrictEqual(calls.filter(c => c === 'dismiss'), ['dismiss', 'dismiss']);
  let focused = false;
  vkm.ShowVirtualKeyboard({ BIsElementValidForInput: () => focused }, {}, false);
  await wait(500);
  assert.deepStrictEqual(calls.filter(c => c === 'dismiss'), ['dismiss', 'dismiss']);
  focused = true;
  await wait(500);
  assert.deepStrictEqual(calls.filter(c => c === 'dismiss'), ['dismiss', 'dismiss']);
  focused = false;
  await wait(500);
  assert.deepStrictEqual(calls.filter(c => c === 'dismiss'), ['dismiss', 'dismiss', 'dismiss']);
  const doc = { activeElement: { tagName: 'INPUT', type: 'text' } };
  vkm.m_ownerWindow = { document: doc };
  vkm.ShowVirtualKeyboard({ BIsElementValidForInput: () => false }, {}, false);
  await wait(500);
  assert.deepStrictEqual(calls.filter(c => c === 'dismiss'), ['dismiss', 'dismiss', 'dismiss']);
  doc.activeElement = { tagName: 'DIV' };
  await wait(500);
  assert.deepStrictEqual(calls.filter(c => c === 'dismiss'), ['dismiss', 'dismiss', 'dismiss', 'dismiss']);
  clearInterval(window.__droiddeckKeyboard.timer);
})().catch(e => { console.error(e); process.exit(1); });
"""
        (self.dir / "keyboard.js").write_text(agent.KEYBOARD_JS)
        (self.dir / "route.js").write_text(agent.KEYBOARD_ROUTE_JS)
        (self.dir / "harness.js").write_text(harness)
        result = subprocess.run(["node", str(self.dir / "harness.js"), str(self.dir / "keyboard.js"), str(self.dir / "route.js")],
                                capture_output=True, text=True, timeout=20)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_route_is_applied_once_per_change(self):
        watcher = agent.KeyboardWatcher(self.dir)
        sent = []
        with patch.object(watcher, "call", side_effect=lambda ws, method, params: sent.append(params["expression"]) or {}):
            watcher.apply_route(None)
            watcher.apply_route(None)
            (self.dir / "keyboard-route").write_text("bottom")
            watcher.apply_route(None)
            watcher.apply_route(None, force=True)
        self.assertEqual(3, len(sent))
        self.assertTrue(sent[0].rstrip().endswith("(false)"))
        self.assertTrue(sent[1].rstrip().endswith("(true)"))
        self.assertNotIn("__ROUTE__", sent[2])
        self.assertNotIn("__BINDING__", sent[2])

    @unittest.skipUnless(shutil.which("node"), "node is not installed")
    def test_page_script_routes_the_keyboard_to_the_app(self):
        harness = r"""
const assert = require('assert');
globalThis.window = globalThis;
const fs = require('fs');
const script = fs.readFileSync(process.argv[2], 'utf8');
const route = fs.readFileSync(process.argv[3], 'utf8');
const calls = [];
window.droiddeckSteamKeyboard = value => calls.push(value);
const shown = [], targets = [];
const open = { m_currentValue: false };
const vkm = {
  IsShowingVirtualKeyboard: { m_currentValue: false },
  m_bIsInlineVirtualKeyboardOpen: open,
  m_bIsVirtualKeyboardModal: { m_currentValue: false },
  BUseVRKeyboard: () => false,
  m_Instance: { IsDesktopOverlayWindow: () => false },
  SetActiveVirtualKeyboardTarget(ref, props) { targets.push(ref); },
  SetVirtualKeyboardHidden() { open.m_currentValue = false; },
  ShowVirtualKeyboard(ref, props, modal) { shown.push(ref); open.m_currentValue = true; },
};
globalThis.SteamUIStore = { WindowStore: { SteamUIWindows: [{ VirtualKeyboardManager: vkm }] } };
eval(script);
vkm.ShowVirtualKeyboard('a', {}, false);
assert.deepStrictEqual(shown, ['a']);
assert.strictEqual(eval(route.replace('__ROUTE__', 'true')), true);
assert.strictEqual(open.m_currentValue, false);
assert.deepStrictEqual(calls.filter(c => c === 'request'), ['request']);
vkm.ShowVirtualKeyboard('b', {}, false);
assert.deepStrictEqual(shown, ['a']);
assert.deepStrictEqual(targets, ['b']);
assert.deepStrictEqual(calls.filter(c => c === 'request'), ['request', 'request']);
vkm.ShowVirtualKeyboard('c', {}, true);
assert.deepStrictEqual(shown, ['a', 'c']);
eval(script);
vkm.ShowVirtualKeyboard('d', {}, false);
assert.deepStrictEqual(targets, ['b', 'd']);
eval(route.replace('__ROUTE__', 'false'));
vkm.ShowVirtualKeyboard('e', {}, false);
assert.deepStrictEqual(shown, ['a', 'c', 'e']);
clearInterval(window.__droiddeckKeyboard.timer);
"""
        (self.dir / "keyboard.js").write_text(agent.KEYBOARD_JS)
        (self.dir / "route.js").write_text(agent.KEYBOARD_ROUTE_JS)
        (self.dir / "harness.js").write_text(harness)
        result = subprocess.run(["node", str(self.dir / "harness.js"), str(self.dir / "keyboard.js"), str(self.dir / "route.js")],
                                capture_output=True, text=True, timeout=20)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_no_shared_context_yet(self):
        with patch.object(agent, "cdp_targets", return_value=[{"title": "Steam", "webSocketDebuggerUrl": "ws://127.0.0.1:1/x"}]):
            with self.assertRaises(agent.AgentError) as caught:
                agent.KeyboardWatcher(self.dir).watch()
        self.assertEqual("NO_TARGET", caught.exception.code)

    @unittest.skipUnless(shutil.which("node"), "node is not installed")
    def test_page_script_watches_every_window_with_or_without_subscribe(self):
        harness = r"""
const assert = require('assert');
globalThis.window = globalThis;
const script = require('fs').readFileSync(process.argv[2], 'utf8');
const calls = [];
window.droiddeckSteamKeyboard = value => calls.push(value);
const tick = ms => new Promise(done => setTimeout(done, ms));
const value = subscribe => {
  const v = { m_currentValue: false, m_callbacks: [] };
  if (subscribe) v.Subscribe = cb => { v.m_callbacks.push(cb); return { Unsubscribe() {} }; };
  v.set = next => { v.m_currentValue = next; for (const cb of v.m_callbacks) cb(next); };
  return v;
};
(async () => {
  assert.strictEqual(eval(script), false);
  assert.deepStrictEqual(calls, ['false']);
  const main = value(true), overlay = value(false);
  globalThis.SteamUIStore = { WindowStore: { SteamUIWindows: [{ VirtualKeyboardManager: { IsShowingVirtualKeyboard: main } }] } };
  assert.strictEqual(eval(script), false);
  main.set(true);
  await tick(20);
  assert.deepStrictEqual(calls, ['false', 'true']);
  main.set(false);
  await tick(20);
  SteamUIStore.WindowStore.SteamUIWindows.push({}, { VirtualKeyboardManager: { IsShowingVirtualKeyboard: overlay } });
  overlay.m_currentValue = true;
  await tick(700);
  assert.deepStrictEqual(calls, ['false', 'true', 'false', 'true']);
  assert.strictEqual(eval(script), true);
  assert.strictEqual(main.m_callbacks.length, 1);
  clearInterval(window.__droiddeckKeyboard.timer);
})().catch(e => { console.error(e); process.exit(1); });
"""
        (self.dir / "keyboard.js").write_text(agent.KEYBOARD_JS)
        (self.dir / "harness.js").write_text(harness)
        result = subprocess.run(["node", str(self.dir / "harness.js"), str(self.dir / "keyboard.js")],
                                capture_output=True, text=True, timeout=20)
        self.assertEqual(0, result.returncode, result.stderr)

    @staticmethod
    def capture(call):
        try:
            call()
        except Exception as e:
            return e


class VrFocusTest(unittest.TestCase):
    def test_scene_dashboard_and_external_focus_changes(self):
        focus = agent.VrSceneFocus()
        self.assertEqual(focus.order([9, 7], [7, 42], 42), [42, 9, 7])
        self.assertIsNone(focus.order([42, 9, 7], [7, 42], 42))
        self.assertEqual(focus.order([42, 9, 7], [7, 42], None), [9, 7])
        self.assertIsNone(focus.order([9, 7], [7], 42))
        self.assertEqual(focus.order([9, 7], [7, 42], 42), [42, 9, 7])
        # Do not overwrite a different focus order installed after our last write.
        self.assertIsNone(focus.order([123, 7], [7, 42], None))

    def test_scene_requires_live_server_and_game_environment(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            state = root / 'scene.json'
            for pid in (100, 200):
                (root / str(pid)).mkdir()
            (root / '100/comm').write_text('vrserver\n')
            (root / '200/environ').write_bytes(b'SteamAppId=42\0Other=value\0')
            state.write_text(json.dumps(dict(serverPid=100, scenePid=200, dashboard=False)))
            self.assertEqual(agent.vr_scene_app(state, root), 42)
            state.write_text(json.dumps(dict(serverPid=100, scenePid=200, dashboard=True)))
            self.assertIsNone(agent.vr_scene_app(state, root))
            state.write_text(json.dumps(dict(serverPid=100, scenePid=200, dashboard=False)))
            (root / '100/comm').write_text('other\n')
            self.assertIsNone(agent.vr_scene_app(state, root))
            (root / '100/comm').unlink()
            self.assertIsNone(agent.vr_scene_app(state, root))


if __name__ == "__main__":
    unittest.main()
