import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest import mock


REPO = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('kwin_windows', REPO / 'tools/ubuntu/build_kwin_windows.py')
kwin = importlib.util.module_from_spec(spec)
spec.loader.exec_module(kwin)


class WindowsKWinBuildTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'source'
        self.folder = Path(self.temp.name) / 'output'
        self.folder.mkdir()
        for name, data in kwin.sources(REPO).items():
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        self.write_outputs()

    def write_outputs(self):
        elf = bytearray(64)
        elf[:6] = b'\x7fELF\x02\x01'
        struct.pack_into('<H', elf, 16, 3)
        struct.pack_into('<H', elf, 18, 183)
        files = {'libkwin.so.6': bytes(elf), 'kwin_wayland': bytes(elf), 'LICENSE': b'GPL-2.0-or-later\n'}
        hashes = {name: kwin.digest(data) for name, data in files.items()}
        files['kwin.sha256'] = ''.join(f'{hashes[name]}  {name}\n' for name in sorted(hashes)).encode()
        manifest = {
            'kwin': '6.6.6', 'systemPackage': '4:6.6.6-0ubuntu0.1+droiddeck1',
            'sources': {name: kwin.digest(data) for name, data in kwin.sources(self.root).items()},
            'outputs': {name: kwin.digest(data) for name, data in files.items()},
        }
        files['build.json'] = (json.dumps(manifest) + '\n').encode()
        for name, data in files.items():
            (self.folder / name).write_bytes(data)
        return files

    def test_cached_build_requires_all_five_outputs_and_current_source_hashes(self):
        files, manifest = kwin.validate(self.folder, self.root)
        self.assertEqual(kwin.OUTPUTS, set(files))
        self.assertEqual('6.6.6', manifest['kwin'])
        source = self.root / 'tools/ubuntu/kwin-independent.cpp'
        source.write_bytes(source.read_bytes() + b'\nchanged\n')
        with self.assertRaisesRegex(ValueError, 'stale'):
            kwin.validate(self.folder, self.root)

    def test_output_checksums_and_architecture_are_verified(self):
        for name in ('LICENSE', 'kwin.sha256', 'libkwin.so.6'):
            with self.subTest(name=name):
                self.write_outputs()
                (self.folder / name).write_bytes(b'changed')
                with self.assertRaises(ValueError):
                    kwin.validate(self.folder, self.root)

    def test_missing_and_unexpected_outputs_are_rejected(self):
        (self.folder / 'LICENSE').unlink()
        with self.assertRaisesRegex(ValueError, 'missing or unexpected'):
            kwin.validate(self.folder, self.root)
        self.write_outputs()
        (self.folder / 'old.so').write_bytes(b'obsolete')
        with self.assertRaisesRegex(ValueError, 'missing or unexpected'):
            kwin.validate(self.folder, self.root)

    def test_crlf_sources_match_the_linux_build(self):
        source = self.root / 'tools/ubuntu/build-kwin-runtime.sh'
        source.write_bytes(source.read_bytes().replace(b'\n', b'\r\n'))
        kwin.validate(self.folder, self.root)

    def test_fresh_build_uses_the_shared_pinned_recipe_in_an_arm64_container(self):
        def run(command, input, check):
            self.assertIn('linux/arm64', command)
            self.assertIn('ubuntu:26.04', command)
            self.assertIn(b'version=4:6.6.6-0ubuntu0.1', input)
            self.assertNotIn(b'\r\n', input)
            snapshot = Path(next(arg for arg in command if arg.startswith('type=bind,source='))
                            .split('source=', 1)[1].split(',target=', 1)[0])
            self.assertEqual(kwin.sources(self.root), kwin.sources(snapshot))
        with mock.patch.object(kwin.subprocess, 'run', side_effect=run) as execute:
            files, _ = kwin.build(self.folder, self.root)
        execute.assert_called_once()
        self.assertEqual(kwin.OUTPUTS, set(files))


if __name__ == '__main__':
    unittest.main()
