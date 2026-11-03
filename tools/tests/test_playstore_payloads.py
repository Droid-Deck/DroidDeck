import hashlib
import importlib.util
import json
import re
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile


SPEC = importlib.util.spec_from_file_location('playstore_payloads', Path(__file__).resolve().parents[1] / 'playstore/payloads.py')
payloads = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(payloads)
READER_SPEC = importlib.util.spec_from_file_location('component_delivery', Path(__file__).resolve().parents[1] /
    'linuxfs/overlay/usr/local/lib/droiddeck/component_delivery.py')
delivery = importlib.util.module_from_spec(READER_SPEC)
READER_SPEC.loader.exec_module(delivery)


class PlaystorePayloadsTest(unittest.TestCase):
    def test_split_data_is_play_hosted_and_guest_reassembles_the_exact_archive(self):
        import io
        import subprocess
        import tarfile
        from tools.playstore.split_archive import prepare, combine, identity
        content = io.BytesIO()
        with tarfile.open(fileobj=content, mode='w') as archive:
            for name, body in [('program', b'\x7fELFprogram'), ('font.ttf', b'\x00\x01\x00\x00font')]:
                member = tarfile.TarInfo(name)
                member.size = len(body)
                archive.addfile(member, io.BytesIO(body))
        original = self.root / 'original'
        original.write_bytes(subprocess.check_output(['zstd', '-c'], input=content.getvalue()))
        code, data, combined = [self.root / name for name in ['code', 'data', 'combined']]
        version = re.search(r'v([0-9.]+),', subprocess.check_output(['zstd', '--version'], text=True)).group(1)
        layout = prepare(original, code, data, version)
        layout['zstd_version'] = '1.5.5'
        combine([code, data], combined)
        entry = self.manifest['entries'][0]
        source = dict(entry, **identity(original))
        (self.cache / source['sha256']).write_bytes(original.read_bytes())
        entry.update(identity(combined))
        entry['layout'] = dict(layout, source=source)
        (self.cache / entry['sha256']).write_bytes(combined.read_bytes())
        staged = self.root / 'staged'
        index = payloads.stage(self.validate(), self.cache, staged, chunk_bytes=64, module_bytes=256)
        packs = json.loads((staged / 'assetpacks.json').read_text())
        self.assertEqual(['playdata01'], packs)
        for module in json.loads((staged / 'modules.json').read_text()):
            self.assertIn('<dist:on-demand />', (staged / module / 'src/main/AndroidManifest.xml').read_text())
        bundled = self.root / 'installed'
        (bundled / '0').mkdir(parents=True)
        for module in ['base'] + json.loads((staged / 'modules.json').read_text()):
            with zipfile.ZipFile(bundled / '0' / (module + '.apk'), 'w') as archive:
                root = staged / module / 'src/main'
                for file in root.rglob('*'):
                    if file.is_file():
                        archive.write(file, str(file.relative_to(root)))
        import socket
        import struct
        import threading
        endpoint = str(self.root / 'resource-socket')
        listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.addCleanup(listener.close)
        listener.bind(endpoint)
        listener.listen()
        listener.settimeout(5)
        count = sum(chunk['module'] == packs[0] for chunk in self.chunks(index['entries'][entry['urls'][0]]))
        errors = []
        def serve():
            try:
                for _ in range(count):
                    client, _ = listener.accept()
                    with client, client.makefile('rb') as source:
                        length = struct.unpack('>I', source.read(4))[0]
                        path = source.read(length).decode('utf-8')
                        client.sendall(b'\x01' + (staged / packs[0] / 'src/main/assets' / path).read_bytes())
            except Exception as error:
                errors.append(error)
        server = threading.Thread(target=serve, daemon=True)
        server.start()
        with patch.object(delivery, 'SOCKET', endpoint), delivery.BundleReader(bundled) as reader:
            restored = io.BytesIO()
            reader.copy(entry['urls'][0], restored, entry['size'])
        server.join(timeout=5)
        self.assertFalse(server.is_alive())
        self.assertFalse(errors)
        self.assertEqual(combined.read_bytes(), restored.getvalue())
        chunks = self.chunks(index['entries'][entry['urls'][0]])
        self.assertTrue(any(chunk['module'] == packs[0] for chunk in chunks))

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.cache = self.root / 'cache'
        self.cache.mkdir()
        self.content = b'component-payload-for-offline-installation'
        self.digest = hashlib.sha256(self.content).hexdigest()
        (self.cache / self.digest).write_bytes(self.content)
        self.manifest = {'schema': 1, 'required': ['runtime'], 'entries': [{
            'id': 'runtime', 'size': len(self.content), 'sha256': self.digest,
            'urls': ['https://example.test/runtime.tar.zst'], 'provides': ['runtime'],
            'source': 'https://example.test/source', 'source_commit': 'a' * 40,
            'license': {'expression': 'MIT', 'url': 'https://example.test/LICENSE', 'approved': True,
                        'redistribution_review': 'License permits redistribution with included notice'},
        }]}

    def validate(self):
        path = self.root / 'manifest.json'
        path.write_text(json.dumps(self.manifest))
        return payloads.load_manifest(path)

    def chunks(self, entry, staged=None):
        staged = staged or self.root / "staged"
        return json.loads((staged / "base/src/main/assets" / entry["recipe"]).read_text())["chunks"]

    def test_chunks_reassemble_exactly_across_module_boundaries_without_network(self):
        with patch.object(payloads.urllib.request, 'build_opener', side_effect=AssertionError('Unexpected network')):
            index = payloads.stage(self.validate(), self.cache, self.root / 'staged', chunk_bytes=7, module_bytes=14)
        entry = index['entries'][self.manifest['entries'][0]['urls'][0]]
        restored = bytearray()
        modules = {}
        for chunk in self.chunks(entry):
            content = (self.root / 'staged' / chunk['module'] / 'src/main/assets' / chunk['asset']).read_bytes()
            self.assertEqual(hashlib.sha256(content).hexdigest(), chunk['sha256'])
            self.assertEqual(len(content), chunk['size'])
            modules[chunk['module']] = modules.get(chunk['module'], 0) + len(content)
            restored.extend(content)
        self.assertGreater(len(modules), 1)
        self.assertTrue(all(size <= 14 for size in modules.values()))
        self.assertEqual(restored, self.content)
        self.assertEqual(hashlib.sha256(restored).hexdigest(), entry['sha256'])

    def test_equal_payloads_share_chunks_without_duplicate_delivery(self):
        duplicate = dict(self.manifest['entries'][0], id='driver', urls=['https://example.test/driver'], provides=['driver'])
        self.manifest['entries'].append(duplicate)
        self.manifest['required'].append('driver')
        index = payloads.stage(self.validate(), self.cache, self.root / 'staged', chunk_bytes=7, module_bytes=14)
        entries = list(index['entries'].values())
        self.assertEqual(self.chunks(entries[0]), self.chunks(entries[1]))
        delivered = [path for path in (self.root / 'staged').rglob('*') if path.is_file() and path.name.isdigit()]
        self.assertEqual(sum(path.stat().st_size for path in delivered), len(self.content))

    def test_corrupt_cache_does_not_fall_back_to_network_or_write_output(self):
        (self.cache / self.digest).write_bytes(b'corrupt')
        with patch.object(payloads.urllib.request, 'build_opener', side_effect=AssertionError('Unexpected network')):
            with self.assertRaisesRegex(ValueError, 'verification'):
                payloads.stage(self.validate(), self.cache, self.root / 'staged')
        self.assertFalse((self.root / 'staged').exists())
        self.assertEqual((self.cache / self.digest).read_bytes(), b'corrupt')

    def test_existing_output_is_preserved(self):
        output = self.root / 'staged'
        output.mkdir()
        sentinel = output / 'keep'
        sentinel.write_bytes(b'existing')
        with self.assertRaisesRegex(ValueError, 'new directory'):
            payloads.stage(self.validate(), self.cache, output)
        self.assertEqual(sentinel.read_bytes(), b'existing')

    def test_required_feature_cannot_be_silently_omitted(self):
        self.manifest['required'].append('proton')
        with self.assertRaisesRegex(ValueError, 'Missing component coverage: proton'):
            self.validate()

    def test_external_windows_prerequisite_requires_explicit_scope_and_pinned_source(self):
        entry = dict(self.manifest['entries'][0], id='windows-vcredist', delivery='windows-guest',
                     provides=['windows:vcredist'], download_url='https://example.test/vcredist.exe')
        self.manifest['entries'].append(entry)
        entry['urls'] = ['https://example.test/vcredist.exe']
        with self.assertRaisesRegex(ValueError, 'restricted to approved Windows'):
            self.validate()
        self.manifest['windows_guest_downloads'] = True
        self.validate()
        entry['provides'] = ['runtime:linux']
        with self.assertRaisesRegex(ValueError, 'restricted to approved Windows'):
            self.validate()

    def test_external_windows_bytes_are_not_fetched_or_counted_as_bundled_payload(self):
        entry = dict(self.manifest['entries'][0], id='windows-vcredist', delivery='windows-guest',
                     provides=['windows:vcredist'], download_url='https://example.test/vcredist.exe',
                     urls=['https://example.test/vcredist.exe'], sha256='b' * 64, size=5_000_000_000)
        self.manifest['entries'].append(entry)
        self.manifest['windows_guest_downloads'] = True
        with patch.object(payloads.urllib.request, 'build_opener', side_effect=AssertionError('Unexpected network')):
            index = payloads.stage(self.validate(), self.cache, self.root / 'staged')
        metadata = index['entries'][entry['urls'][0]]
        self.assertEqual('windows-guest', metadata['delivery'])
        self.assertEqual(entry['download_url'], metadata['download_url'])
        self.assertNotIn('recipe', metadata)

    def test_shared_storage_chunks_preserve_distinct_component_identities(self):
        first = self.manifest['entries'][0]
        second = dict(first, id='second', urls=['https://example.test/second'], size=first['size'] + 7)
        data = self.content + b'changed'
        second['sha256'] = hashlib.sha256(data).hexdigest()
        (self.cache / second['sha256']).write_bytes(data)
        self.manifest['entries'].append(second)
        index = payloads.stage(self.validate(), self.cache, self.root / 'staged', chunk_bytes=7, module_bytes=14)
        one, two = [index['entries'][entry['urls'][0]] for entry in [first, second]]
        self.assertEqual(self.chunks(one)[0], self.chunks(two)[0])
        self.assertNotEqual(one['sha256'], two['sha256'])

    def test_unknown_schema_and_empty_manifest_are_rejected(self):
        self.manifest['schema'] = 2
        with self.assertRaisesRegex(ValueError, 'schema'):
            self.validate()
        self.manifest['schema'] = 1
        self.manifest['entries'] = []
        with self.assertRaisesRegex(ValueError, 'empty'):
            self.validate()

    def test_prepared_alias_cannot_bypass_upstream_pinning(self):
        entry = self.manifest['entries'][0]
        entry['urls'] = ['droiddeck-bundle://runtime/runtime.tar.zst']
        with self.assertRaisesRegex(ValueError, 'prepared component alias'):
            self.validate()
        entry['prepare'] = {'method': 'gzip-to-zstd-19', 'zstd_version': '1.5.5',
                            'tar_sha256': 'a' * 64, 'tar_size': 1024}
        with self.assertRaisesRegex(ValueError, 'upstream archive'):
            self.validate()
        entry['upstream'] = {'urls': ['https://example.test/runtime.tar.gz'], 'sha256': 'b' * 64, 'size': 100}
        self.validate()
        entry['upstream']['urls'] = ['http://example.test/runtime.tar.gz']
        with self.assertRaisesRegex(ValueError, 'HTTPS'):
            self.validate()

    def test_review_only_inventory_cannot_enter_the_play_release_workflow(self):
        self.manifest['release_ready'] = False
        with self.assertRaisesRegex(ValueError, 'Review-only'):
            payloads.validate_play_engines(self.manifest)

    def test_unconfirmed_redistribution_cannot_be_published_by_toggling_the_release_flag(self):
        self.manifest['release_ready'] = True
        self.manifest['entries'][0]['license']['approved'] = False
        with self.assertRaisesRegex(ValueError, 'redistribution review is incomplete'):
            payloads.validate_play_engines(self.manifest)

    def test_play_bundle_cannot_include_extra_desktop_emulators(self):
        self.manifest['required'].append('proton:droiddeck')
        entry = self.manifest['entries'][0]
        entry['provides'].append('proton:droiddeck')
        entry['urls'] = ['https://github.com/Droid-Deck/DroidDeck-Proton/releases/download/pinned/proton.tar.gz']
        entry['provides'].append('emulator:ppsspp')
        with self.assertRaisesRegex(ValueError, 'Extra desktop emulators'):
            payloads.validate_play_engines(self.manifest)

    def test_frozen_catalog_is_served_without_consulting_a_mutable_external_api(self):
        entry = self.manifest['entries'][0]
        entry['content'] = {'version': 'pinned', 'assets': []}
        data = payloads.catalog_bytes(entry['content'])
        entry['sha256'], entry['size'] = hashlib.sha256(data).hexdigest(), len(data)
        with patch.object(payloads.urllib.request, 'build_opener', side_effect=AssertionError('Unexpected network')):
            target = payloads.fetch(self.validate()['entries'][0], self.cache)
        self.assertEqual(data, target.read_bytes())

    def test_frozen_catalog_changes_cannot_keep_the_old_hash(self):
        entry = self.manifest['entries'][0]
        entry['content'] = {'version': 'changed'}
        with self.assertRaisesRegex(ValueError, 'Frozen catalog identity'):
            self.validate()

    def test_unpinned_or_unreviewed_component_is_rejected(self):
        for field, value, message in [('sha256', 'bad', 'SHA-256'), ('size', True, 'positive byte'),
                                       ('source_commit', 'main', 'pinned source'), ('license', {}, 'redistribution')]:
            with self.subTest(field=field):
                original = self.manifest['entries'][0][field]
                self.manifest['entries'][0][field] = value
                with self.assertRaisesRegex(ValueError, message):
                    self.validate()
                self.manifest['entries'][0][field] = original

    def test_duplicate_urls_and_unsafe_sources_are_rejected(self):
        entry = self.manifest['entries'][0]
        entry['urls'].append(entry['urls'][0])
        with self.assertRaisesRegex(ValueError, 'Duplicate URL'):
            self.validate()
        for url in ['http://example.test/component', 'https://user:secret@example.test/component',
                    'file:///tmp/component', 'https://example.test/component#fragment']:
            entry['urls'] = [url]
            with self.subTest(url=url), self.assertRaisesRegex(ValueError, 'HTTPS'):
                self.validate()

    def test_total_delivery_budget_counts_unique_content_and_base_reserve(self):
        with patch.object(payloads, 'TOTAL_BYTES', len(self.content) + payloads.RESERVED_BYTES - 1):
            with self.assertRaisesRegex(ValueError, 'budget exceeded'):
                self.validate()

    def test_redirect_cannot_downgrade_to_plain_http(self):
        with self.assertRaisesRegex(ValueError, 'HTTPS'):
            payloads.HttpsRedirects().redirect_request(None, None, 302, '', {}, 'http://example.test/payload')

    def test_partial_download_is_preserved_and_not_resumed_without_identity(self):
        target = self.cache / self.digest
        alternate = self.root / 'other-cache'
        alternate.mkdir()
        part = alternate / (self.digest + '.part')
        part.write_bytes(target.read_bytes()[:3])
        with self.assertRaisesRegex(ValueError, 'Incomplete payload'):
            payloads.fetch(self.manifest['entries'][0], alternate)
        self.assertEqual(part.read_bytes(), self.content[:3])

    def apk_set(self, change=None):
        staged = self.root / 'staged'
        index = payloads.stage(self.validate(), self.cache, staged, chunk_bytes=7, module_bytes=14)
        installed = self.root / 'installed/0'
        installed.mkdir(parents=True)
        if change is not None:
            change(staged, index)
        for module in staged.iterdir():
            if not module.is_dir():
                continue
            assets = module / 'src/main/assets'
            with zipfile.ZipFile(installed / (module.name + '.apk'), 'w', zipfile.ZIP_DEFLATED) as archive:
                for path in sorted(assets.rglob('*')):
                    if path.is_file():
                        archive.write(path, 'assets/' + path.relative_to(assets).as_posix())
        return installed.parent

    def test_guest_reads_the_same_chunked_payload_directly_from_split_apks(self):
        installed = self.apk_set()
        self.assertTrue(delivery.enabled(installed))
        with delivery.BundleReader(installed) as reader:
            destination = self.root / 'restored'
            reader.fetch(self.manifest['entries'][0]['urls'][0], destination, self.digest)
            self.assertEqual(destination.read_bytes(), self.content)

    def test_guest_corruption_preserves_existing_file_without_external_fallback(self):
        def corrupt(staged, index):
            chunk = self.chunks(next(iter(index['entries'].values())), staged)[0]
            (staged / chunk['module'] / 'src/main/assets' / chunk['asset']).write_bytes(b'corrupt')
        installed = self.apk_set(corrupt)
        destination = self.root / 'restored'
        destination.write_bytes(b'existing')
        with delivery.BundleReader(installed) as reader:
            with self.assertRaisesRegex(ValueError, 'verification'):
                reader.fetch(self.manifest['entries'][0]['urls'][0], destination)
        self.assertEqual(destination.read_bytes(), b'existing')
        self.assertEqual(list(self.root.glob('*.component-part')), [])

    def test_guest_missing_split_is_a_failure_without_any_download(self):
        installed = self.apk_set()
        split = installed / '0/playpayload02.apk'
        split.rename(self.root / 'preserved-split.apk')
        with delivery.BundleReader(installed) as reader:
            with self.assertRaises(KeyError):
                reader.read(self.manifest['entries'][0]['urls'][0])

    def test_guest_rejects_missing_component_and_wrong_digest(self):
        with delivery.BundleReader(self.apk_set()) as reader:
            with self.assertRaisesRegex(ValueError, 'absent'):
                reader.read('https://example.test/not-bundled')
            with self.assertRaisesRegex(ValueError, 'version'):
                reader.fetch(self.manifest['entries'][0]['urls'][0], self.root / 'restored', '0' * 64)

    def test_guest_rejects_duplicate_assets_across_splits(self):
        installed = self.apk_set()
        original = installed / '0/base.apk'
        (installed / '0/duplicate.apk').write_bytes(original.read_bytes())
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            delivery.BundleReader(installed)

    def bundle(self, change=None):
        staged = self.root / 'staged'
        index = payloads.stage(self.validate(), self.cache, staged, chunk_bytes=7, module_bytes=14)
        if change is not None:
            change(staged, index)
        target = self.root / 'fixture.aab'
        with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as archive:
            for module in staged.iterdir():
                if module.is_dir():
                    assets = module / 'src/main/assets'
                    for path in sorted(assets.rglob('*')):
                        if path.is_file():
                            archive.write(path, module.name + '/assets/' + path.relative_to(assets).as_posix())
        return target

    def test_final_bundle_inventory_and_each_delivered_byte_are_verified(self):
        sizes = payloads.verify_bundle(self.validate(), self.bundle())
        self.assertIn('base', sizes)
        self.assertIn('playpayload02', sizes)

    def test_final_bundle_cannot_contain_corrupt_chunk_bytes(self):
        def corrupt(staged, index):
            entry = next(iter(index['entries'].values()))
            chunk = self.chunks(entry, staged)[0]
            (staged / chunk['module'] / 'src/main/assets' / chunk['asset']).write_bytes(b'corrupt')
        with self.assertRaisesRegex(ValueError, 'digest mismatch'):
            payloads.verify_bundle(self.validate(), self.bundle(corrupt))

    def test_native_payload_cannot_be_relabelled_as_a_data_asset_pack(self):
        def relabel(staged, index):
            entry = next(iter(index['entries'].values()))
            recipe = staged / 'base/src/main/assets' / entry['recipe']
            definition = json.loads(recipe.read_text())
            definition['chunks'][0]['module'] = 'playdata01'
            recipe.write_text(json.dumps(definition))
        with self.assertRaisesRegex(ValueError, 'delivery are mixed'):
            payloads.verify_bundle(self.validate(), self.bundle(relabel))

    def test_final_bundle_cannot_silently_include_stale_unreferenced_components(self):
        def add_stale(staged, index):
            path = staged / 'playpayload01/src/main/assets/play-components/stale'
            path.write_bytes(b'old component')
        with self.assertRaisesRegex(ValueError, 'unreferenced'):
            payloads.verify_bundle(self.validate(), self.bundle(add_stale))

    def test_final_bundle_must_match_the_release_manifest(self):
        bundle = self.bundle()
        self.manifest['entries'][0]['id'] = 'changed'
        with self.assertRaisesRegex(ValueError, 'inventory differs'):
            payloads.verify_bundle(self.manifest, bundle)

    def test_play_distribution_rejects_alternate_proton_engines_and_valve_seed(self):
        with self.assertRaisesRegex(ValueError, 'requires DroidDeck'):
            payloads.validate_play_engines(self.manifest)
        self.manifest['required'].append('proton:droiddeck')
        entry = self.manifest['entries'][0]
        entry['provides'].append('proton:droiddeck')
        entry['urls'] = ['https://github.com/Droid-Deck/DroidDeck-Proton/releases/download/pinned/proton.tar.gz']
        payloads.validate_play_engines(self.manifest)
        for url in ['https://github.com/GloriousEggroll/proton-ge-custom/releases/download/pinned/proton.tar.gz',
                    'https://github.com/CachyOS/proton-cachyos/releases/download/pinned/proton.tar.xz',
                    'https://raw.githubusercontent.com/Droid-Deck/DroidDeck-Components/main/steam-seed.json']:
            entry['urls'] = [url]
            with self.assertRaisesRegex(ValueError, 'forbidden'):
                payloads.validate_play_engines(self.manifest)


if __name__ == '__main__':
    unittest.main()
