import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile


SCRIPT = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin/droiddeck-steam-install'


class SteamInstallTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.home = self.root / 'home'
        self.home.mkdir()
        self.data = self.root / 'data'
        self.steam = self.data / 'Steam'
        self.cdn = self.root / 'cdn'
        self.cdn.mkdir()
        self.requests = self.root / 'requests'
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        # Only the network is faked: exercise real manifest parsing, checksums and zip extraction.
        curl = self.bin / 'curl'
        curl.write_text('''#!/bin/bash
set -eu
printf '%s\\n' "$2" >> "$TEST_REQUESTS"
cp "$TEST_CDN/${2##*/}" "$4"
''')
        curl.chmod(0o755)
        # macOS has shasum; the guest and Linux CI have sha256sum.
        if shutil.which('sha256sum') is None:
            sha = self.bin / 'sha256sum'
            sha.write_text('#!/bin/bash\nexec shasum -a 256 "$@"\n')
            sha.chmod(0o755)
        self.env = dict(os.environ, HOME=str(self.home), XDG_DATA_HOME=str(self.data),
                        PATH=str(self.bin) + os.pathsep + os.environ['PATH'],
                        TEST_CDN=str(self.cdn), TEST_REQUESTS=str(self.requests))
        self.env.pop('BL_STEAM_CHANNEL', None)
        for channel in ('publicbeta', 'steamdeck_publicbeta'):
            archive = self.cdn / (channel + '.zip')
            with zipfile.ZipFile(archive, 'w') as package:
                package.writestr('steamrtarm64/steam', channel)
                package.writestr('linuxarm64/wrapper', 'wrapper')
            digest = hashlib.sha256(archive.read_bytes()).hexdigest()
            (self.cdn / ('steam_client_' + channel + '_linuxarm64')).write_text(
                '"linuxarm64"\n{\n\t"version" "123456"\n'
                '\t"client_linuxarm64_linuxarm64"\n\t{\n'
                f'\t\t"file" "{archive.name}"\n\t\t"sha2" "{digest}"\n'
                '\t}\n}\n')

    def install(self, channel=None):
        env = self.env.copy()
        if channel is not None:
            env['BL_STEAM_CHANNEL'] = channel
        return subprocess.run(['bash', str(SCRIPT)], env=env, text=True, capture_output=True)

    def assert_installed(self, channel):
        result = self.install(channel)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(channel + '\n', (self.steam / 'package/beta').read_text())
        self.assertEqual('123456\n', (self.steam / 'package/droiddeck-installed').read_text())
        client = self.steam / 'steamrtarm64/steam'
        self.assertTrue(os.access(client, os.X_OK))
        self.assertEqual(channel, client.read_text())
        self.assertEqual((self.steam / 'linuxarm64').resolve(), (self.home / '.steam/sdkarm64').resolve())
        self.assertEqual([
            'https://client-update.fastly.steamstatic.com/steam_client_' + channel + '_linuxarm64',
            'https://client-update.fastly.steamstatic.com/' + channel + '.zip',
        ], self.requests.read_text().splitlines())

    def test_unset_channel_downloads_deck_beta(self):
        result = self.install()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('steamdeck_publicbeta\n', (self.steam / 'package/beta').read_text())
        self.assertIn('/steam_client_steamdeck_publicbeta_linuxarm64\n', self.requests.read_text())

    def test_explicit_deck_beta_downloads_matching_client(self):
        self.assert_installed('steamdeck_publicbeta')

    def test_explicit_public_beta_downloads_matching_client(self):
        self.assert_installed('publicbeta')

    def test_existing_install_is_left_for_steam_to_update(self):
        self.assert_installed('steamdeck_publicbeta')
        requests = self.requests.read_text()
        result = self.install('publicbeta')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(requests, self.requests.read_text())
        self.assertEqual('steamdeck_publicbeta\n', (self.steam / 'package/beta').read_text())

    def test_failed_download_has_no_ready_marker_and_can_retry(self):
        archive = self.cdn / 'publicbeta.zip'
        original = archive.read_bytes()
        archive.write_bytes(b'corrupt download')
        self.assertNotEqual(0, self.install('publicbeta').returncode)
        self.assertFalse((self.steam / 'package/droiddeck-installed').exists())
        archive.write_bytes(original)
        self.assertEqual(0, self.install('publicbeta').returncode)
        self.assertEqual('publicbeta\n', (self.steam / 'package/beta').read_text())

    def test_invalid_channel_fails_before_any_download(self):
        result = self.install('../unknown')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('unsupported Steam channel', result.stderr)
        self.assertFalse(self.requests.exists())


if __name__ == '__main__':
    unittest.main()
