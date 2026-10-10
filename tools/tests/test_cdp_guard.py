import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[1] / "linuxfs/preload/net.c"


@unittest.skipUnless(sys.platform.startswith("linux") and shutil.which("cc"), "needs Linux and a C compiler")
class CdpGuardTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.build = tempfile.TemporaryDirectory()
        cls.library = Path(cls.build.name) / "net.so"
        subprocess.run(["cc", "-shared", "-fPIC", "-O2", str(SOURCE), str(SOURCE.parent / "binfmt.c"), "-x", "c", "-", "-ldl",
                        "-o", str(cls.library)],
                       input=b"int bl_udevmon_stand_in(int fd) { return 0; }", check=True, capture_output=True)

    @classmethod
    def tearDownClass(cls):
        cls.build.cleanup()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.registry = Path(self.temp.name) / "net"
        self.registry.mkdir()

    def port(self, ipv6=False):
        with socket.socket(socket.AF_INET6 if ipv6 else socket.AF_INET) as s:
            s.bind(("::1" if ipv6 else "127.0.0.1", 0))
            return s.getsockname()[1]

    def env(self, port):
        return dict(os.environ, LD_PRELOAD=str(self.library), BL_CDP_GUARD=str(port), BL_SYSV_DIR=self.temp.name)

    def test_guarded_ipv4_bind_is_loopback_only(self):
        port = self.port()
        program = "import socket; s=socket.socket(); s.bind(('0.0.0.0', %d)); print(s.getsockname()[0])" % port
        result = subprocess.run([sys.executable, "-c", program], env=self.env(port), check=True, capture_output=True, text=True)
        self.assertEqual("127.0.0.1", result.stdout.strip())

    def test_guarded_ipv6_bind_is_loopback_only(self):
        if not socket.has_ipv6:
            self.skipTest("needs IPv6")
        port = self.port(True)
        program = "import socket; s=socket.socket(socket.AF_INET6); s.bind(('::', %d)); print(s.getsockname()[0])" % port
        result = subprocess.run([sys.executable, "-c", program], env=self.env(port), check=True, capture_output=True, text=True)
        self.assertEqual("::1", result.stdout.strip())

    def test_other_listeners_are_unchanged(self):
        port = self.port()
        program = "import socket; s=socket.socket(); s.bind(('0.0.0.0', %d)); print(s.getsockname()[0])" % port
        result = subprocess.run([sys.executable, "-c", program], env=self.env(0), check=True, capture_output=True, text=True)
        self.assertEqual("0.0.0.0", result.stdout.strip())

    def connect(self, address, registered, stale=False, wrong_destination=False):
        port = self.port()
        program = "import socket; s=socket.socket(); s.bind(('0.0.0.0', %d)); s.listen(); print('ready', flush=True); c,_=s.accept(); c.sendall(b'accepted')" % port
        server = subprocess.Popen([sys.executable, "-u", "-c", program], env=self.env(port), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            self.assertEqual(b"ready\n", server.stdout.readline())
            with socket.socket() as client:
                client.settimeout(3)
                client.bind((address, 0))
                if registered:
                    peer = client.getsockname()[1]
                    inode = os.fstat(client.fileno()).st_ino + (1 if stale else 0)
                    destination = port + (1 if wrong_destination else 0)
                    (self.registry / ("p%d" % peer)).write_text("%d 1 %d %d %d %d %d\n" %
                        (os.getpid(), os.getuid(), destination, client.fileno(), inode, socket.AF_INET))
                client.connect(("127.0.0.1", port))
                try:
                    return client.recv(8)
                except ConnectionResetError:
                    return b""
        finally:
            server.kill()
            server.wait()
            server.stdout.close()
            server.stderr.close()

    def test_registered_guest_loopback_peer_is_accepted(self):
        self.assertEqual(b"accepted", self.connect("127.0.0.1", True))

    def test_unregistered_local_peer_is_refused(self):
        self.assertEqual(b"", self.connect("127.0.0.1", False))

    def test_matching_port_record_cannot_authorize_another_address(self):
        self.assertEqual(b"", self.connect("127.0.0.2", True))

    def test_stale_socket_record_does_not_authorize_a_reused_port(self):
        self.assertEqual(b"", self.connect("127.0.0.1", True, stale=True))

    def test_record_for_another_destination_does_not_authorize_cdp(self):
        self.assertEqual(b"", self.connect("127.0.0.1", True, wrong_destination=True))

    def test_preloaded_guest_connect_still_works(self):
        port = self.port()
        program = "import socket; s=socket.socket(); s.bind(('0.0.0.0', %d)); s.listen(); print('ready', flush=True); c,_=s.accept(); c.sendall(b'accepted')" % port
        server = subprocess.Popen([sys.executable, "-u", "-c", program], env=self.env(port), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            self.assertEqual(b"ready\n", server.stdout.readline())
            client = "import socket; s=socket.create_connection(('127.0.0.1', %d), timeout=3); print(s.recv(8).decode())" % port
            result = subprocess.run([sys.executable, "-c", client], env=self.env(port), check=True, capture_output=True, text=True, timeout=5)
            self.assertEqual("accepted", result.stdout.strip())
        finally:
            server.kill()
            server.wait()
            server.stdout.close()
            server.stderr.close()


if __name__ == "__main__":
    unittest.main()
