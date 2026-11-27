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
                conn.sendall(b"answer:" + conn.recv(100))

        threading.Thread(target=echo, daemon=True).start()
        server = socket.socket()
        server.bind(("127.0.0.1", 0))
        server.listen(1)
        port = server.getsockname()[1]
        Path(self.relay.FLAG).write_text("me %d" % port)
        serving = threading.Thread(target=self.relay.serve, args=(server, upstream.getsockname()[1], "me"), daemon=True)
        serving.start()
        with socket.create_connection(("127.0.0.1", port), 2) as client:
            client.sendall(b"GET /json")
            client.settimeout(2)
            self.assertEqual(b"answer:GET /json", client.recv(100))
        with mock.patch.object(self.relay.sys, "argv", ["x", "stop", str(port + 1)]):
            self.assertEqual(0, self.relay.main())
        serving.join(0.3)
        self.assertTrue(serving.is_alive())
        with mock.patch.object(self.relay.sys, "argv", ["x", "stop", str(port)]):
            self.assertEqual(0, self.relay.main())
        serving.join(2)
        self.assertFalse(serving.is_alive())

    def test_rejects_unknown_commands(self):
        for argv in (["x"], ["x", "start"], ["x", "stop", "1", "2"]):
            with mock.patch.object(self.relay.sys, "argv", argv), mock.patch.object(self.relay.sys, "stderr"):
                self.assertEqual(2, self.relay.main())


if __name__ == "__main__":
    unittest.main()
