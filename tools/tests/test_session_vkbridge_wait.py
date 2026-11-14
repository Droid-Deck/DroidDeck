import os
import re
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import unittest
from pathlib import Path

SESSION = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-session"


def wait_functions():
    text = SESSION.read_text()
    found = []
    for name in ("bl_now_ms", "vkbridge_wait_socket"):
        match = re.search(r"^%s\(\) \{\n.*?^\}\n" % name, text, re.M | re.S)
        if match:
            found.append(match.group(0))
    return found


class VkBridgeSocketWaitTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root, True)
        self.sock = self.root / "vkbridge" / "server.sock"
        self.sock.parent.mkdir()
        self.listeners = []
        self.addCleanup(lambda: [s.close() for s in self.listeners])

    def listen(self):
        listener = socket.socket(socket.AF_UNIX)
        listener.bind(str(self.sock))
        listener.listen(1)
        self.listeners.append(listener)

    def listen_after(self, seconds):
        timer = threading.Timer(seconds, self.listen)
        timer.start()
        self.addCleanup(timer.cancel)

    def run_wait(self, limit_ms, prelude=""):
        functions = wait_functions()
        self.assertEqual(2, len(functions), "droiddeck-session lacks bl_now_ms() or vkbridge_wait_socket()")
        script = "set -u\n" + "".join(functions) + prelude
        script += 'if vkbridge_wait_socket "$1" "$2"; then echo USABLE; else echo FALLBACK; fi\n'
        started = time.monotonic()
        out = subprocess.run(["bash", "-c", script, "session", str(self.sock), str(limit_ms)],
                             capture_output=True, text=True, timeout=30, check=True).stdout
        return out, time.monotonic() - started

    def waited_ms(self, out):
        match = re.search(r"waited (\d+) ms|after (\d+) ms", out)
        self.assertIsNotNone(match, out)
        return int(match.group(1) or match.group(2))

    def test_a_socket_already_listening_is_used_at_once(self):
        self.listen()
        out, elapsed = self.run_wait(10000)
        self.assertTrue(out.endswith("USABLE\n"), out)
        self.assertIn("== vkbridge: server socket ready (waited 0 ms)", out)
        self.assertLess(elapsed, 2)

    def test_a_server_still_probing_is_waited_for(self):
        self.listen_after(0.4)
        out, elapsed = self.run_wait(10000)
        self.assertTrue(out.endswith("USABLE\n"), out)
        self.assertIn("== vkbridge: server socket ready (waited ", out)
        waited = self.waited_ms(out)
        self.assertGreaterEqual(waited, 300, out)
        self.assertLess(waited, 5000, out)
        self.assertLess(elapsed, 6)

    def test_no_server_falls_back_after_the_limit(self):
        out, elapsed = self.run_wait(300)
        self.assertTrue(out.endswith("FALLBACK\n"), out)
        self.assertIn("== vkbridge: no server socket after ", out)
        self.assertIn("(gave up at 300 ms)", out)
        self.assertGreaterEqual(self.waited_ms(out), 300)
        self.assertLess(elapsed, 5)

    def test_a_plain_file_is_not_a_socket(self):
        self.sock.write_text("stale")
        out, _ = self.run_wait(200)
        self.assertTrue(out.endswith("FALLBACK\n"), out)

    def test_a_stopping_session_does_not_wait(self):
        out, elapsed = self.run_wait(10000, prelude="bl_stopping=1\n")
        self.assertTrue(out.endswith("FALLBACK\n"), out)
        self.assertIn("the session is stopping", out)
        self.assertLess(elapsed, 2)

    def test_no_socket_path_is_a_fallback_without_waiting(self):
        functions = "".join(wait_functions())
        out = subprocess.run(["bash", "-c", "set -u\n" + functions +
                              'if vkbridge_wait_socket "" 10000; then echo USABLE; else echo FALLBACK; fi\n'],
                             capture_output=True, text=True, timeout=10, check=True).stdout
        self.assertEqual("FALLBACK\n", out)


class VkBridgeSessionCheckTest(unittest.TestCase):

    def setUp(self):
        self.text = SESSION.read_text()
        match = re.search(r'^if \[ "\$\{BL_VKBRIDGE:-0\}" = 1 \]; then\n.*?^fi\n', self.text, re.M | re.S)
        self.assertIsNotNone(match)
        self.block = match.group(0)

    def test_the_first_pass_waits_before_the_usability_check(self):
        wait = self.block.index("vkbridge_wait_socket")
        check = self.block.index('[ -S "${VKBRIDGE_SOCKET:-}" ]')
        self.assertLess(wait, check)
        self.assertIn('[ -z "${BL_INSIDE:-}" ] && vkbridge_wait_socket', self.block)

    def test_the_fallback_message_names_what_is_missing(self):
        self.assertIn("Mali bridge requested but NOT usable", self.block)
        self.assertIn("missing:$bl_vkb_missing", self.block)

    def test_a_stop_ends_the_wait(self):
        self.assertRegex(self.text, r"trap '[^']*bl_stopping=1[^']*' TERM INT HUP")


if __name__ == "__main__":
    unittest.main()
