import hashlib
import io
import json
from pathlib import Path
import runpy
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/release'))
import check_android_libraries as native


class AmazonEngineTest(unittest.TestCase):
    def pack(self, folder, abi=1, library=b'engine', checksum=None, extra=False):
        manifest = {'schema': 1, 'engine': 'amazon', 'abi': abi, 'target': 'aarch64-linux-android',
                    'library_sha256': checksum or hashlib.sha256(library).hexdigest()}
        stream = io.BytesIO()
        files = {'engine.json': json.dumps(manifest).encode(), 'libdroiddeckamazon.so': library}
        if extra:
            files['unexpected'] = b'x'
        with tarfile.open(fileobj=stream, mode='w') as archive:
            for name, content in files.items():
                member = tarfile.TarInfo(name)
                member.size = len(content)
                archive.addfile(member, io.BytesIO(content))
        path = Path(folder) / 'amazon.tar.zst'
        path.write_bytes(stream.getvalue())
        return path

    def validate(self, path, missing=False, bundled=False):
        def command(args, **kwargs):
            if args[0] == 'zstd':
                return path.read_bytes()
            if '-h' in args:
                return 'ELF64 AArch64'
            if '-d' in args:
                return 'NEEDED [libc.so]'
            symbols = native.AMAZON_EXPORTS[:-1] if missing else native.AMAZON_EXPORTS
            if bundled:
                symbols = symbols + native.EXPORTS
            return '\n'.join(' GLOBAL DEFAULT 1 ' + symbol for symbol in symbols)
        with patch.object(native.subprocess, 'check_output', side_effect=command):
            return native.check_amazon_pack(path, 'readelf')

    def test_pack_checks_interface_digest_exports_and_isolation(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(self.validate(self.pack(directory)), [])
            self.assertTrue(self.validate(self.pack(directory, abi=2)))
            self.assertTrue(self.validate(self.pack(directory, checksum='0' * 64)))
            self.assertTrue(self.validate(self.pack(directory), missing=True))
            self.assertTrue(self.validate(self.pack(directory), bundled=True))
            self.assertTrue(self.validate(self.pack(directory, extra=True)))

    def test_manifest_tracks_amazon_and_shared_inputs(self):
        inputs = runpy.run_path(str(ROOT / 'tools/stores/amazon-manifest.py'))['inputs']
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            amazon = root / 'app/src/main/rust/stores/src/amazon/example.rs'
            amazon.parent.mkdir(parents=True)
            amazon.write_text('amazon')
            common = root / 'app/src/main/rust/stores/src/fetch_core.rs'
            common.write_text('common')
            before = inputs(root)
            common.write_text('changed')
            self.assertNotEqual(before, inputs(root))
            self.assertIn(amazon.relative_to(root).as_posix(), before)

    def test_apk_and_publication_use_optional_pack(self):
        gradle = (ROOT / 'app/build.gradle').read_text(encoding='utf-8')
        self.assertIn("'--no-default-features', '--features', 'gog,epic'", gradle)
        self.assertIn('comet amazon; do', (ROOT / '.github/workflows/publish-packs.yml').read_text())
        self.assertIn('pack amazon', (ROOT / 'tools/packs/build.sh').read_text())
