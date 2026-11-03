import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location('android_libraries', Path(__file__).resolve().parents[1] / 'release/check_android_libraries.py')
libraries = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(libraries)


class AndroidPageAlignmentTest(unittest.TestCase):
    def check(self, headers):
        with patch.object(libraries.subprocess, 'check_output', return_value=headers):
            return libraries.page_alignment(Path('libsample.so'), 'readelf', 16384)

    def test_sixteen_kilobyte_segments_are_accepted(self):
        self.assertEqual([], self.check('  LOAD 0x000000 0x0000000000000000 0x0000000000000000 0x001000 0x001000 R E 0x4000\n'))

    def test_old_four_kilobyte_segments_are_rejected(self):
        self.assertEqual(1, len(self.check('  LOAD 0x000000 0x0000000000000000 0x0000000000000000 0x001000 0x001000 R E 0x1000\n')))

    def test_each_segment_must_have_congruent_offset_and_address(self):
        headers = ('  LOAD 0x000000 0x0000000000000000 0x0000000000000000 0x001000 0x001000 R E 0x4000\n'
                   '  LOAD 0x001000 0x0000000000008000 0x0000000000008000 0x001000 0x001000 RW 0x4000\n')
        self.assertEqual(1, len(self.check(headers)))

    def test_missing_load_segments_do_not_pass(self):
        self.assertEqual(1, len(self.check('Program Headers:\n')))


if __name__ == '__main__':
    unittest.main()
