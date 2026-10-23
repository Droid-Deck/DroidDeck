import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile


SOURCE = Path(__file__).resolve().parents[1] / 'qualcomm/fetch_runtime.py'
spec = importlib.util.spec_from_file_location('qualcomm_fetch', SOURCE)
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


class QualcommRuntimeTest(unittest.TestCase):
    def lock(self, data):
        return dict(sha256=hashlib.sha256(data).hexdigest(), size=len(data), url='https://example.invalid/runtime')

    def test_cached_payload_is_rechecked_before_it_is_used(self):
        data = b'approved bridge'
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory)
            lock = self.lock(data)
            target = cache / (lock['sha256'] + '.tzst')
            target.write_bytes(b'x' * len(data))
            with patch.object(runtime.urllib.request, 'urlopen', return_value=io.BytesIO(data)):
                self.assertEqual(runtime.fetch(cache, lock).read_bytes(), data)

    def test_bad_download_never_becomes_the_cached_payload(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory)
            with patch.object(runtime.urllib.request, 'urlopen', return_value=io.BytesIO(b'corrupt')):
                with self.assertRaisesRegex(ValueError, 'SHA-256'):
                    runtime.fetch(cache, self.lock(b'approved'))
            self.assertEqual(list(cache.iterdir()), [])

    def test_only_the_pinned_gamescope_member_is_staged(self):
        bridge, binary = b'bridge', b'ARM64 gamescope'
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, 'w') as package:
            package.writestr('assets/gamescope', binary)
            package.writestr('other-file', b'not staged')
        lock = self.lock(bridge)
        lock['gamescope'] = self.lock(archive.getvalue()) | dict(member='assets/gamescope', member_sha256=hashlib.sha256(binary).hexdigest())
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cache, output = root / 'cache', root / 'output'
            cache.mkdir()
            for data, entry in [(bridge, lock), (archive.getvalue(), lock['gamescope'])]:
                (cache / (entry['sha256'] + '.tzst')).write_bytes(data)
            runtime.stage(output, cache, lock)
            self.assertEqual((output / 'gamescope').read_bytes(), binary)
            self.assertEqual(set(p.name for p in output.iterdir()), {'runtime.tzst', 'gamescope', 'runtime.json'})
            self.assertEqual(json.loads((output / 'runtime.json').read_text()), lock)
            lock['gamescope']['member_sha256'] = '0' * 64
            with self.assertRaisesRegex(ValueError, 'Gamescope'):
                runtime.stage(root / 'rejected', cache, lock)

    def test_wsi_files_must_match_their_individual_pins(self):
        bridge, binary, wsi = b'bridge', b'gamescope', b'WSI library'
        def package(name, data):
            output = io.BytesIO()
            with zipfile.ZipFile(output, 'w') as archive:
                archive.writestr(name, data)
            return output.getvalue()
        gamescope_archive = package('gamescope', binary)
        wsi_archive = package('layer.so', wsi)
        lock = self.lock(bridge)
        lock['gamescope'] = self.lock(gamescope_archive) | dict(member='gamescope', member_sha256=hashlib.sha256(binary).hexdigest())
        lock['wsi'] = self.lock(wsi_archive) | dict(files=[dict(
            name='layer.so', path='wsi/layer.so', size=len(wsi), sha256='0' * 64)])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cache = root / 'cache'
            cache.mkdir()
            for data, entry in [(bridge, lock), (gamescope_archive, lock['gamescope']), (wsi_archive, lock['wsi'])]:
                (cache / (entry['sha256'] + '.tzst')).write_bytes(data)
            with self.assertRaisesRegex(ValueError, 'WSI member'):
                runtime.stage(root / 'bad-member', cache, lock)
            lock['wsi']['files'][0]['sha256'] = hashlib.sha256(wsi).hexdigest()
            lock['wsi']['files'][0]['path'] = '../outside.so'
            with self.assertRaisesRegex(ValueError, 'destination'):
                runtime.stage(root / 'bad-path', cache, lock)
            self.assertFalse((root / 'outside.so').exists())



if __name__ == '__main__':
    unittest.main()
