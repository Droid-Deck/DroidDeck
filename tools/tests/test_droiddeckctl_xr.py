import importlib.machinery
import importlib.util
import json
import gzip
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zlib

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
loader = importlib.machinery.SourceFileLoader('ctl_xr', str(TOOLS / 'droiddeckctl'))
spec = importlib.util.spec_from_loader(loader.name, loader)
ctl = importlib.util.module_from_spec(spec)
loader.exec_module(ctl)


class XrCaptureTest(unittest.TestCase):
    def test_png_keeps_rgb_rows_and_dimensions(self):
        pixels = bytes([255, 0, 0, 0, 255, 0, 0, 0, 255, 255, 255, 255])
        png = ctl.xr_ppm_to_png(b'P6\n2 2\n255\n' + pixels)
        self.assertEqual(png[:8], b'\x89PNG\r\n\x1a\n')
        self.assertEqual(struct.unpack('>II', png[16:24]), (2, 2))
        position = png.index(b'IDAT')
        size = struct.unpack('>I', png[position - 4:position])[0]
        self.assertEqual(zlib.decompress(png[position + 4:position + 4 + size]),
                         b'\0' + pixels[:6] + b'\0' + pixels[6:])

    def test_incomplete_or_invalid_images_are_rejected(self):
        for data in (b'P6\n2 2\n255\nabc', b'P6\n0 1\n255\n', b'P6\n999999 1\n255\n', b'junk'):
            with self.subTest(data=data), self.assertRaises(ctl.ControlError):
                ctl.xr_ppm_to_png(data)

    def test_linear_source_and_display_eye_have_distinct_png_transfer_tags(self):
        image = b'P6\n1 1\n255\n\x37\x37\x37'
        self.assertIn(b'gAMA' + struct.pack('>I', 100000), ctl.xr_ppm_to_png(image, linear=True))
        self.assertIn(b'sRGB\0', ctl.xr_ppm_to_png(image))

    def capture(self, root, failed=False, replaced=False, no_response=False, source=False, truncated=False):
        calls = []
        token = None
        reads = 0
        def adb(_adb, _serial, *args, **kwargs):
            nonlocal token, reads
            calls.append(args)
            stdout = b''
            if args[0] == 'push':
                token = Path(args[1]).read_text()
            if args[:2] == ('shell', 'cat'):
                reads += 1
                stdout = json.dumps({'id': ('old' if reads == 1 or no_response or (replaced and reads > 2) else token),
                                     'ok': not failed, 'validLayer': not failed, 'sourceImage': source}).encode()
            if args[0] == 'exec-out':
                stdout = gzip.compress(b'P6\n1 1\n255\n\x12\x34\x56')
                if truncated:
                    stdout = stdout[:-5]
            return subprocess.CompletedProcess(args, 0, stdout, b'')
        with patch.object(ctl, 'run_adb', side_effect=adb), patch.object(ctl.time, 'sleep'):
            result = ctl.xr_capture('adb', 'quest', root, 0.01 if no_response else 2)
        return result, calls

    def test_stale_response_is_ignored_and_matching_pair_is_published(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'capture'
            result, calls = self.capture(root)
            self.assertTrue(result['ok'])
            self.assertEqual((root / 'left.png').read_bytes(), (root / 'right.png').read_bytes())
            self.assertEqual(len([c for c in calls if c[:2] == ('shell', 'cat')]), 3)
            self.assertTrue((root / 'capture.json').is_file())

    def test_invalid_layer_replaced_capture_and_timeout_publish_no_images(self):
        for flags in ({'failed': True}, {'replaced': True}, {'no_response': True}, {'truncated': True}):
            with self.subTest(flags=flags), tempfile.TemporaryDirectory() as directory:
                root = Path(directory) / 'capture'
                with self.assertRaises(ctl.ControlError):
                    self.capture(root, **flags)
                self.assertFalse(root.exists())

    def test_source_capture_is_pulled_when_supported_by_the_headset(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'capture'
            result, calls = self.capture(root, source=True)
            self.assertEqual(result['source'], str((root / 'source.png').resolve()))
            self.assertEqual(len([c for c in calls if c[0] == 'exec-out']), 3)

    def test_output_creation_and_write_failures_are_file_errors(self):
        for operation in ('mkdir', 'write_bytes'):
            with self.subTest(operation=operation), tempfile.TemporaryDirectory() as directory:
                with patch.object(Path, operation, side_effect=PermissionError('output denied')):
                    with self.assertRaises(ctl.ControlError) as caught:
                        self.capture(Path(directory) / 'capture')
                self.assertEqual(caught.exception.code, 'FILE_IO')
                self.assertEqual(ctl.exit_code(caught.exception), 6)

    def test_stalled_metadata_read_uses_capture_deadline_and_reports_timeout(self):
        calls = []
        def adb(_adb, _serial, *args, **kwargs):
            calls.append((args, kwargs))
            if args[:2] == ('shell', 'cat'):
                self.assertGreater(kwargs['timeout'], 0)
                self.assertLessEqual(kwargs['timeout'], 0.1)
                try:
                    raise subprocess.TimeoutExpired('adb', kwargs['timeout'])
                except subprocess.TimeoutExpired as cause:
                    raise ctl.ControlError('metadata stalled', 'ADB_FAILED') from cause
            return subprocess.CompletedProcess(args, 0, b'', b'')
        with tempfile.TemporaryDirectory() as directory, patch.object(ctl, 'run_adb', side_effect=adb):
            with self.assertRaises(ctl.ControlError) as caught:
                ctl.xr_capture('adb', 'quest', Path(directory) / 'capture', 0.1)
        self.assertEqual(caught.exception.code, 'TIMEOUT')
        self.assertTrue(any(args[:3] == ('shell', 'rm', '-f') for args, _ in calls))


if __name__ == '__main__':
    unittest.main()
