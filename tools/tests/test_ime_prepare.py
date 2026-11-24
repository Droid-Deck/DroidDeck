'''Regression tests for tools/ime/prepare.py RPM extraction.'''

import contextlib
import importlib.util
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile
import unittest
from unittest import mock


MODULE_PATH = Path(__file__).resolve().parents[1] / 'ime' / 'prepare.py'
SPEC = importlib.util.spec_from_file_location('ime_prepare', MODULE_PATH)
PREPARE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREPARE)
NUL = bytes(1)


def _newc(files):
    archive = bytearray()
    for inode, (name, data) in enumerate(files.items(), start=1):
        encoded = name.encode('utf-8')
        name_size = len(encoded) + 1
        header = '070701' + ''.join(f'{value:08x}' for value in (
            inode, 0o100644, 0, 0, 1, 0, len(data), 0, 0, 0, 0, name_size, 0))
        archive += header.encode('ascii')
        archive += encoded + NUL
        archive += NUL * (-(110 + name_size) % 4)
        archive += data
        archive += NUL * (-len(data) % 4)
    trailer = b'TRAILER!!!' + NUL
    header = '070701' + ''.join(f'{value:08x}' for value in (
        0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, len(trailer), 0))
    archive += header.encode('ascii')
    archive += trailer
    archive += NUL * (-(110 + len(trailer)) % 4)
    return bytes(archive)


class ExtractTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def _write_executable(self, path, text):
        path.write_text(text, encoding='utf-8')
        path.chmod(0o755)

    def _tools(self, base, cpio, payload=None, exit_code=1):
        tools = base / 'bin'
        tools.mkdir(parents=True)
        fixture = base / 'payload.cpio'
        if payload is None:
            script = f'''#!/bin/sh
echo rpm2cpio failed >&2
exit {exit_code}
'''
        else:
            fixture.write_bytes(payload)
            script = f'''#!/bin/sh
exec cat {shlex.quote(str(fixture))}
'''
        self._write_executable(tools / 'rpm2cpio', script)
        self._write_executable(tools / 'cpio', f'''#!/bin/sh
exec {shlex.quote(str(cpio))} "$@"
''')
        return tools

    @contextlib.contextmanager
    def _path(self, tools):
        with mock.patch.dict(os.environ,
                             {'PATH': str(tools) + os.pathsep + os.environ.get('PATH', '')}):
            yield

    def _cpio_candidates(self):
        candidates = []
        for command in ('cpio', 'bsdcpio'):
            path = shutil.which(command)
            if path:
                candidates.append((command, Path(path)))
        return candidates

    def test_extract_normal_payload_with_available_cpio(self):
        candidates = self._cpio_candidates()
        if not candidates:
            self.skipTest('no cpio implementation is available')
        payload = _newc({'usr/share/demo.txt': b'hello'})
        for command, cpio in candidates:
            with self.subTest(cpio=command):
                base = self.root / ('normal-' + command)
                base.mkdir()
                tools = self._tools(base, cpio, payload)
                destination = base / 'destination'
                with self._path(tools):
                    PREPARE.extract('demo', base / 'demo.rpm', destination)
                self.assertEqual((destination / 'usr/share/demo.txt').read_bytes(), b'hello')

    def test_extract_rejects_unsafe_paths_before_writing(self):
        candidates = self._cpio_candidates()
        if not candidates:
            self.skipTest('no cpio implementation is available')
        cpio = candidates[0][1]
        for label in ('absolute', 'parent'):
            with self.subTest(case=label):
                base = self.root / ('unsafe-' + label)
                base.mkdir()
                outside = base / (label + '-escaped.txt')
                name = str(outside) if label == 'absolute' else '../' + outside.name
                tools = self._tools(base, cpio, _newc({'payload/safe.txt': b'safe', name: b'bad'}))
                destination = base / 'destination'
                with self._path(tools), self.assertRaises(ValueError) as caught:
                    PREPARE.extract('unsafe', base / 'unsafe.rpm', destination)
                self.assertIn('unsafe path', str(caught.exception))
                self.assertFalse((destination / 'payload/safe.txt').exists())
                self.assertFalse(outside.exists())

    def test_extract_propagates_rpm2cpio_failure(self):
        candidates = self._cpio_candidates()
        if not candidates:
            self.skipTest('no cpio implementation is available')
        base = self.root / 'failed'
        base.mkdir()
        tools = self._tools(base, candidates[0][1], exit_code=7)
        with self._path(tools), self.assertRaises(subprocess.CalledProcessError) as caught:
            PREPARE.extract('broken', base / 'broken.rpm', base / 'destination')
        self.assertEqual(caught.exception.returncode, 7)


if __name__ == '__main__':
    unittest.main()
