import hashlib
import io
import lzma
from pathlib import Path
import os
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile
import zlib


SCRIPT = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin/droiddeck-steam-preapply'
NAME = 'steam_client_steamdeck_publicbeta_linuxarm64'


def zip_bytes(entries):
    out = io.BytesIO()
    with zipfile.ZipFile(out, 'w') as z:
        for name, data, mode, stamp in entries:
            info = zipfile.ZipInfo(name, stamp)
            info.external_attr = mode << 16
            z.writestr(info, data)
    return out.getvalue()


def vzip(data):
    packer = lzma.LZMACompressor(lzma.FORMAT_RAW, filters=[
        {'id': lzma.FILTER_LZMA1, 'dict_size': 1 << 20, 'lc': 3, 'lp': 0, 'pb': 2}])
    body = packer.compress(data) + packer.flush()
    props = (2 * 5 + 0) * 9 + 3
    return (b'VZa' + b'\0\0\0\0' + bytes([props]) + struct.pack('<I', 1 << 20) + body
            + struct.pack('<II', zlib.crc32(data) & 0xFFFFFFFF, len(data)) + b'zv')


class SteamPreapplyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.packages = root / 'packages'
        self.packages.mkdir()
        self.steam = root / 'Steam'
        self.manifest = root / 'manifest'

    def write_manifest(self, blocks):
        text = '"linuxarm64"\n{\n\t"version"\t\t"42"\n'
        for block, attrs in blocks.items():
            text += f'\t"{block}"\n\t{{\n'
            for key, value in attrs.items():
                text += f'\t\t"{key}"\t\t"{value}"\n'
            text += '\t}\n'
        self.manifest.write_text(text + '}\n')

    def run_script(self):
        return subprocess.run([sys.executable, '-I', str(SCRIPT), str(self.packages), str(self.steam),
                               str(self.manifest), NAME], text=True, capture_output=True)

    def installed(self):
        return (self.steam / 'package' / (NAME + '.installed')).read_text().split('\n')

    def prepare(self):
        stamp = (2025, 5, 17, 10, 30, 0)
        plain = zip_bytes([
            ('bin/', b'', 0o40755, stamp),
            ('bin/Tool', b'tool', 0o100755, stamp),
            ('bin\\Backslash.txt', b'bs', 0o100644, stamp),
            ('lib/libx.so.1', b'libx', 0o100644, stamp),
            ('lib/libx.so', b'libx.so.1', 0o120777, stamp),
        ])
        packed = zip_bytes([('data/z_file', b'zed', 0o100644, stamp), ('data/A_file', b'aaa', 0o100644, stamp)])
        (self.packages / 'a.zip.1111').write_bytes(plain)
        (self.packages / 'b.zip.vz.2222_14').write_bytes(vzip(packed))
        self.write_manifest({
            'a_all': {'file': 'a.zip.1111', 'size': len(plain), 'sha2': '00'},
            'b_all': {'file': 'b.zip.9999', 'size': 99, 'sha2': '00', 'zipvz': 'b.zip.vz.2222_14', 'sha2vz': '00'},
        })

    def test_extracts_every_package_including_packed_ones(self):
        self.prepare()
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(b'tool', (self.steam / 'bin/Tool').read_bytes())
        self.assertTrue(os.access(self.steam / 'bin/Tool', os.X_OK))
        self.assertEqual(b'bs', (self.steam / 'bin/Backslash.txt').read_bytes())
        self.assertEqual('libx.so.1', os.readlink(self.steam / 'lib/libx.so'))
        self.assertEqual(b'zed', (self.steam / 'data/z_file').read_bytes())

    def test_records_what_steams_updater_records(self):
        self.prepare()
        self.assertEqual(0, self.run_script().returncode)
        stamp = 1747477800 + 28800
        crc = lambda data: zlib.crc32(data) & 0xFFFFFFFF
        lines = self.installed()
        self.assertEqual([
            f'bin/,-1;{stamp};0',
            f'bin/Backslash.txt,2;{stamp};{crc(b"bs")}',
            f'bin/Tool,4;{stamp};{crc(b"tool")}',
            f'data/A_file,3;{stamp};{crc(b"aaa")}',
            f'data/z_file,3;{stamp};{crc(b"zed")}',
            f'lib/libx.so,-2;{stamp};{crc(b"libx.so.1")}',
            f'lib/libx.so.1,4;{stamp};{crc(b"libx")}',
            'OSVER=-184',
            'VERSION=3',
        ], lines[:-2])
        body = ''.join(line + '\n' for line in lines[:-2])
        self.assertEqual('SHA1=' + hashlib.sha1(body.encode()).hexdigest().upper(), lines[-2])
        self.assertEqual('', lines[-1])

    def test_a_missing_package_leaves_no_record_so_steam_extracts_for_itself(self):
        self.prepare()
        (self.packages / 'b.zip.vz.2222_14').unlink()
        result = self.run_script()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.steam / 'package' / (NAME + '.installed')).exists())

    def test_a_corrupt_package_leaves_no_record(self):
        self.prepare()
        packed = (self.packages / 'b.zip.vz.2222_14').read_bytes()
        (self.packages / 'b.zip.vz.2222_14').write_bytes(packed[:-12] + b'\0' * 12)
        self.assertNotEqual(0, self.run_script().returncode)
        self.assertFalse((self.steam / 'package' / (NAME + '.installed')).exists())


    def package_with(self, entries):
        (self.packages / 'a.zip.1111').write_bytes(zip_bytes(entries))
        self.write_manifest({'a_all': {'file': 'a.zip.1111', 'size': 1, 'sha2': '00'}})

    def test_a_path_that_leaves_the_steam_root_is_refused(self):
        stamp = (2025, 5, 17, 10, 30, 0)
        for name in ('../outside', '/abs/outside', 'a/../../outside'):
            with self.subTest(name=name):
                self.package_with([(name, b'x', 0o100644, stamp)])
                self.assertNotEqual(0, self.run_script().returncode)
                self.assertFalse((self.steam / 'package' / (NAME + '.installed')).exists())
                self.assertFalse((Path(self.temp.name) / 'outside').exists())

    def test_a_file_cannot_be_written_through_a_symlink_that_leaves_the_steam_root(self):
        stamp = (2025, 5, 17, 10, 30, 0)
        target = Path(self.temp.name) / 'elsewhere'
        target.mkdir()
        self.package_with([('link', str(target).encode(), 0o120777, stamp), ('link/file', b'x', 0o100644, stamp)])
        self.assertNotEqual(0, self.run_script().returncode)
        self.assertFalse((target / 'file').exists())

    def test_a_symlink_inside_the_steam_root_still_carries_files(self):
        stamp = (2025, 5, 17, 10, 30, 0)
        self.package_with([('real/', b'', 0o40755, stamp), ('alias', b'real', 0o120777, stamp),
                           ('alias/file', b'x', 0o100644, stamp)])
        self.assertEqual(0, self.run_script().returncode)
        self.assertEqual(b'x', (self.steam / 'real/file').read_bytes())


if __name__ == '__main__':
    unittest.main()
