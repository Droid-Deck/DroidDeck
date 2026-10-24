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

    def test_local_builds_are_pinned_and_never_fall_back_to_a_download(self):
        bridge, binary, source, mesa = b'bridge', b'built gamescope', b'corresponding source', b'private mesa'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            built, cache = root / 'build/private', root / 'cache'
            built.mkdir(parents=True)
            cache.mkdir()
            lock = self.lock(bridge)
            (cache / (lock['sha256'] + '.tzst')).write_bytes(bridge)
            for name, data in [('gamescope', binary), ('source.tar.gz', source), ('mesa.so', mesa)]:
                (built / name).write_bytes(data)
            lock['gamescope'] = dict(member_sha256=hashlib.sha256(binary).hexdigest(), build=dict(
                binary='build/private/gamescope', source='build/private/source.tar.gz',
                source_sha256=hashlib.sha256(source).hexdigest(), source_size=len(source)))
            lock['mesa'] = dict(directory='build/private', files=[dict(
                path='mesa.so', sha256=hashlib.sha256(mesa).hexdigest(), size=len(mesa))])
            with patch.object(runtime, '__file__', str(root / 'tools/qualcomm/fetch_runtime.py')):
                output = root / 'output'
                runtime.stage(output, cache, lock)
                self.assertEqual((output / 'gamescope').read_bytes(), binary)
                self.assertEqual((output / 'licenses/gamescope-patched-source.tar.gzip').read_bytes(), source)
                self.assertEqual((output / 'mesa/mesa.so').read_bytes(), mesa)
                (built / 'mesa.so').write_bytes(b'wrong mesa')
                with self.assertRaisesRegex(ValueError, 'Mesa member'):
                    runtime.stage(output, cache, lock)
                (built / 'gamescope').unlink()
                with self.assertRaisesRegex(FileNotFoundError, 'Build private Gamescope'):
                    runtime.stage(output, cache, lock)

    def test_built_wsi_members_come_from_the_pinned_local_build(self):
        bridge, binary, license_text, layer = b'bridge', b'gamescope', b'MIT', b'Wayland WSI layer'
        def package(members):
            output = io.BytesIO()
            with zipfile.ZipFile(output, 'w') as archive:
                for name, data in members:
                    archive.writestr(name, data)
            return output.getvalue()
        gamescope_archive = package([('gamescope', binary)])
        wsi_archive = package([('LICENSE', license_text), ('layer.so', b'X11-only layer')])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            built, cache = root / 'build/qualcomm-wsi', root / 'cache'
            built.mkdir(parents=True)
            cache.mkdir()
            (built / 'layer.so').write_bytes(layer)
            lock = self.lock(bridge)
            lock['gamescope'] = self.lock(gamescope_archive) | dict(member='gamescope', member_sha256=hashlib.sha256(binary).hexdigest())
            lock['wsi'] = self.lock(wsi_archive) | dict(build=dict(directory='build/qualcomm-wsi'), files=[
                dict(name='LICENSE', path='licenses/wsi-LICENSE', size=len(license_text), sha256=hashlib.sha256(license_text).hexdigest()),
                dict(name='layer.so', path='wsi/layer.so', size=len(layer), sha256=hashlib.sha256(layer).hexdigest(), built=True)])
            for data, entry in [(bridge, lock), (gamescope_archive, lock['gamescope']), (wsi_archive, lock['wsi'])]:
                (cache / (entry['sha256'] + '.tzst')).write_bytes(data)
            with patch.object(runtime, '__file__', str(root / 'tools/qualcomm/fetch_runtime.py')):
                output = root / 'output'
                runtime.stage(output, cache, lock)
                self.assertEqual((output / 'wsi/layer.so').read_bytes(), layer)
                self.assertEqual((output / 'licenses/wsi-LICENSE').read_bytes(), license_text)
                (built / 'layer.so').write_bytes(b'tampered layer')
                with self.assertRaisesRegex(ValueError, 'WSI member'):
                    runtime.stage(root / 'tampered', cache, lock)
                (built / 'layer.so').unlink()
                with self.assertRaisesRegex(FileNotFoundError, 'build_wsi.py'):
                    runtime.stage(root / 'missing', cache, lock)
                lock['wsi']['files'][1]['name'] = 'nested/layer.so'
                with self.assertRaisesRegex(ValueError, 'build member'):
                    runtime.stage(root / 'nested', cache, lock)
                lock['wsi']['build']['directory'] = '../outside'
                with self.assertRaisesRegex(ValueError, 'build path'):
                    runtime.stage(root / 'outside', cache, lock)


if __name__ == '__main__':
    unittest.main()
