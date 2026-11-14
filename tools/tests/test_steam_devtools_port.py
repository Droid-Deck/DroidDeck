import re
import shlex
import socket
import subprocess
import tempfile
import unittest
from pathlib import Path

SESSION = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-session"


def devtools_function():
    match = re.search(r"^steam_devtools\(\) \{\n.*?^\}\n", SESSION.read_text(), re.M | re.S)
    return match.group(0) if match else None


class SteamDevtoolsPortTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def free_port(self):
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            return sock.getsockname()[1]

    def choose(self, port):
        function = devtools_function()
        self.assertIsNotNone(function, "droiddeck-session has no steam_devtools()")
        script = function + "steam_root=%s\nsteam_devtools %d\n" % (shlex.quote(str(self.root)), port)
        script += 'echo "port=$BL_CDP_PORT"\necho "args=${_bl_cdp[*]}"\n'
        out = subprocess.run(["bash", "-c", script], capture_output=True, text=True, timeout=30, check=True).stdout
        lines = out.splitlines()
        port_line = next(l for l in lines if l.startswith("port="))
        args_line = next(l for l in lines if l.startswith("args="))
        return int(port_line[5:]), args_line[5:].split(), out

    def test_without_remote_debugging_steam_gets_a_private_port(self):
        port, args, _ = self.choose(self.free_port())
        self.assertEqual(["-cef-enable-debugging", "-devtools-port", str(port)], args)
        self.assertTrue(20000 <= port < 50000)

    def test_remote_debugging_keeps_the_fixed_port_when_it_is_free(self):
        (self.root / ".cef-enable-remote-debugging").touch()
        fixed = self.free_port()
        port, args, out = self.choose(fixed)
        self.assertEqual(fixed, port)
        self.assertEqual([], args)
        self.assertNotIn("held by another app", out)

    def test_a_fixed_port_another_app_holds_falls_back_to_a_private_one(self):
        (self.root / ".cef-enable-remote-debugging").touch()
        with socket.socket() as other:
            other.bind(("127.0.0.1", 0))
            other.listen(1)
            taken = other.getsockname()[1]
            port, args, out = self.choose(taken)
        self.assertNotEqual(taken, port)
        self.assertEqual(["-cef-enable-debugging", "-devtools-port", str(port)], args)
        self.assertIn("held by another app", out)

    def test_a_fixed_port_bound_without_listening_also_falls_back(self):
        (self.root / ".cef-enable-remote-debugging").touch()
        with socket.socket() as other:
            other.bind(("127.0.0.1", 0))
            taken = other.getsockname()[1]
            port, args, out = self.choose(taken)
        self.assertNotEqual(taken, port)
        self.assertEqual(["-cef-enable-debugging", "-devtools-port", str(port)], args)

    def test_the_x86_64_decky_exemption_only_covers_the_fixed_port(self):
        text = SESSION.read_text()
        self.assertIn('[ "${decky_arch:-}" = x86_64 ] && [ ${#_bl_cdp[@]} = 0 ]', text)


if __name__ == "__main__":
    unittest.main()
