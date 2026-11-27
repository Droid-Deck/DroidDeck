import importlib.machinery
import importlib.util
import shutil
import socket
import tempfile
import threading
import unittest
from pathlib import Path
from unittest import mock

HELPER = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-qam-relay"


def load():
    loader = importlib.machinery.SourceFileLoader("qam_relay", str(HELPER))
    spec = importlib.util.spec_from_loader("qam_relay", loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


class QamRelayTest(unittest.TestCase):
    def setUp(self):
        self.relay = load()
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.relay.FLAG = str(self.tmp / "flag")
        self.relay.INTERVAL = 0.05

    def test_pipes_bytes_both_ways_until_its_own_port_is_stopped(self):
        upstream = socket.socket()
        upstream.bind(("127.0.0.1", 0))
        upstream.listen(1)
        self.addCleanup(upstream.close)

        def echo():
            conn, _ = upstream.accept()
            with conn:
                conn.settimeout(2)
                head = b""
                while not head.endswith(b"\r\n\r\n"):
                    head += conn.recv(100)
                conn.sendall(b"answer:" + head)

        threading.Thread(target=echo, daemon=True).start()
        server = socket.socket()
        server.bind(("127.0.0.1", 0))
        server.listen(1)
        port = server.getsockname()[1]
        Path(self.relay.FLAG).write_text("me %d" % port)
        serving = threading.Thread(target=self.relay.serve, args=(server, upstream.getsockname()[1], "me", "s3cret"), daemon=True)
        serving.start()
        with socket.create_connection(("127.0.0.1", port), 2) as client:
            client.sendall(b"GET /s3cret/json HTTP/1.1\r\nHost: x\r\n\r\n")
            client.settimeout(2)
            expected = b"answer:GET /json HTTP/1.1\r\nHost: x\r\n\r\n"
            got = b""
            while len(got) < len(expected):
                data = client.recv(100)
                if not data:
                    break
                got += data
            self.assertEqual(expected, got)
        with mock.patch.object(self.relay.sys, "argv", ["x", "stop", str(port + 1)]):
            self.assertEqual(0, self.relay.main())
        serving.join(0.3)
        self.assertTrue(serving.is_alive())
        with mock.patch.object(self.relay.sys, "argv", ["x", "stop", str(port)]):
            self.assertEqual(0, self.relay.main())
        serving.join(2)
        self.assertFalse(serving.is_alive())

    def test_refuses_requests_without_the_secret_or_outside_the_allowed_paths(self):
        rewrite = self.relay.rewrite
        self.assertEqual(b"GET /devtools/page/AB-1 HTTP/1.1\r\n", rewrite(b"GET /k/devtools/page/AB-1 HTTP/1.1\r\n", "k"))
        for head in (b"GET /json HTTP/1.1\r\n", b"GET /x/json HTTP/1.1\r\n", b"POST /k/json HTTP/1.1\r\n",
                     b"GET /k/devtools/browser/1 HTTP/1.1\r\n", b"GET /k/json/new?x HTTP/1.1\r\n", b"GET /k/json HTTP/1.1"):
            self.assertIsNone(rewrite(head, "k"), head)

    def test_stops_after_an_idle_period(self):
        self.relay.IDLE_SECONDS = 0.1
        server = socket.socket()
        server.bind(("127.0.0.1", 0))
        server.listen(1)
        Path(self.relay.FLAG).write_text("me %d" % server.getsockname()[1])
        serving = threading.Thread(target=self.relay.serve, args=(server, 1, "me", "k"), daemon=True)
        serving.start()
        serving.join(2)
        self.assertFalse(serving.is_alive())

    def test_rejects_unknown_commands(self):
        for argv in (["x"], ["x", "start"], ["x", "stop", "1", "2"]):
            with mock.patch.object(self.relay.sys, "argv", argv), mock.patch.object(self.relay.sys, "stderr"):
                self.assertEqual(2, self.relay.main())


if __name__ == "__main__":
    unittest.main()
