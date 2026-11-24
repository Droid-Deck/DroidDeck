import struct
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "linuxfs/unwrap-dwarfs.sh"


class UnwrapDwarfsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.dir = Path(self.temp.name)
        self.output = self.dir / "dwarfsextract"

    def wrapper(self, payload, magic=b"SQUEEZE!", packed=None):
        compressed = subprocess.run(["zstd", "-q", "-c"], input=payload, capture_output=True, check=True).stdout
        size = len(compressed) if packed is None else packed
        path = self.dir / "wrapper"
        path.write_bytes(b"\x7fELF stub" * 100 + compressed + magic + struct.pack("<QQQ", len(payload), size, 0))
        return path

    def unwrap(self, wrapper):
        return subprocess.run(["bash", str(SCRIPT), str(wrapper), str(self.output)], capture_output=True, text=True)

    def test_the_payload_behind_the_stub_is_unpacked(self):
        payload = b"\x7fELF" + bytes(range(256)) * 64
        run = self.unwrap(self.wrapper(payload))
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(self.output.read_bytes(), payload)

    def test_a_file_without_the_trailer_is_refused(self):
        run = self.unwrap(self.wrapper(b"payload", magic=b"NOTSQZ!!"))
        self.assertNotEqual(run.returncode, 0)
        self.assertIn("no SQUEEZE! trailer", run.stderr)

    def test_a_payload_size_beyond_the_file_is_refused(self):
        run = self.unwrap(self.wrapper(b"payload", packed=1 << 40))
        self.assertNotEqual(run.returncode, 0)
        self.assertIn("bad payload size", run.stderr)


if __name__ == "__main__":
    unittest.main()
