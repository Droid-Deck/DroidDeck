import importlib.util
from pathlib import Path
import tempfile
import unittest


spec = importlib.util.spec_from_file_location(
    'ludusavi_build', Path(__file__).resolve().parents[1] / 'ludusavi' / 'build.py')
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)


def elf(machine):
    header = bytearray(20)
    header[:4] = b'\x7fELF'
    header[4] = 2
    header[5] = 1
    header[18:20] = machine.to_bytes(2, 'little')
    return header


class LudusaviBuildTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.binary = Path(self.temp.name) / 'ludusavi'
        self.original_patch = build.PATCH

    def tearDown(self):
        build.PATCH = self.original_patch

    def test_accepts_aarch64_binary(self):
        self.binary.write_bytes(elf(build.AARCH64_EM))
        build.assert_aarch64(self.binary)

    def test_rejects_host_binary(self):
        self.binary.write_bytes(elf(62))
        with self.assertRaises(SystemExit):
            build.assert_aarch64(self.binary)

    def test_patch_change_invalidates_source_cache(self):
        patch = Path(self.temp.name) / 'cli-only.patch'
        patch.write_text('first')
        build.PATCH = patch
        first = build.source_identity()
        patch.write_text('second')
        self.assertNotEqual(first, build.source_identity())
