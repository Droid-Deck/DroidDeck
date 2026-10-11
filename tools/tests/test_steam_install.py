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
        if shutil.which('sha1sum') is None:
            sha1 = self.bin / 'sha1sum'
            sha1.write_text('#!/bin/bash\nexec shasum -a 1 "$@"\n')
            sha1.chmod(0o755)
        self.env = dict(os.environ, HOME=str(self.home), XDG_DATA_HOME=str(self.data),
                        PATH=str(self.bin) + os.pathsep + os.environ['PATH'],
                        TEST_CDN=str(self.cdn), TEST_REQUESTS=str(self.requests))
        for channel in ('steamdeck_publicbeta',):
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

    def install(self):
        return subprocess.run(['bash', str(SCRIPT)], env=self.env, text=True, capture_output=True)

    def assert_installed(self, channel='steamdeck_publicbeta'):
        result = self.install()
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

    def test_downloads_the_deck_beta_client(self):
        self.assert_installed()

    def test_an_install_from_another_branch_is_left_for_steam_to_move(self):
        # An older app could install publicbeta; droiddeck-session moves the client at its start.
        self.assert_installed()
        requests = self.requests.read_text()
        (self.steam / 'package/beta').write_text('publicbeta\n')
        result = self.install()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(requests, self.requests.read_text())
        self.assertEqual('publicbeta\n', (self.steam / 'package/beta').read_text())

    def test_failed_download_has_no_ready_marker_and_can_retry(self):
        archive = self.cdn / 'steamdeck_publicbeta.zip'
        original = archive.read_bytes()
        archive.write_bytes(b'corrupt download')
        self.assertNotEqual(0, self.install().returncode)
        self.assertFalse((self.steam / 'package/droiddeck-installed').exists())
        archive.write_bytes(original)
        self.assertEqual(0, self.install().returncode)
        self.assertEqual('steamdeck_publicbeta\n', (self.steam / 'package/beta').read_text())

    def break_client(self, starts):
        self.assert_installed()
        self.requests.unlink()
        package = self.steam / 'package'
        (self.steam / 'steamrtarm64/steam').write_text('half updated')
        (package / 'steam_client_steamdeck_publicbeta_linuxarm64.manifest').write_text('manifest')
        (package / 'steam_client_steamdeck_publicbeta_linuxarm64.installed').write_text('installed')
        good = b'complete package'
        (package / ('bins_linuxarm64.zip.vz.' + hashlib.sha1(good).hexdigest() + '_16')).write_bytes(good)
        (package / ('bins_misc_linuxarm64.zip.vz.' + hashlib.sha1(b'whole').hexdigest() + '_5')).write_bytes(b'wh')
        (package / ('strings_all.zip.' + hashlib.sha1(b'strings').hexdigest())).write_bytes(b'strings')
        (self.steam / '.crash').write_text('')
        (package / 'droiddeck-unconfirmed').write_text('start\n' * starts)
        return package

    def test_two_starts_with_nothing_shown_fetch_the_client_again(self):
        package = self.break_client(2)
        self.assert_installed()
        self.assertEqual('repaired\n', (package / 'droiddeck-unconfirmed').read_text())
        self.assertFalse((self.steam / '.crash').exists())
        self.assertEqual([], sorted(p.name for p in package.glob('steam_client_*')))
        self.assertEqual(['bins_linuxarm64.zip.vz.', 'strings_all.zip.'],
                         sorted(p.name[:p.name.rindex('.') + 1] for p in package.glob('*.zip.*')))

    def test_one_start_with_nothing_shown_is_left_alone(self):
        package = self.break_client(1)
        self.assertEqual(0, self.install().returncode)
        self.assertFalse(self.requests.exists())
        self.assertEqual('half updated', (self.steam / 'steamrtarm64/steam').read_text())
        self.assertTrue((package / 'steam_client_steamdeck_publicbeta_linuxarm64.installed').exists())

    def test_a_repair_is_not_repeated_until_the_client_has_been_seen(self):
        package = self.break_client(2)
        self.assertEqual(0, self.install().returncode)
        self.requests.unlink()
        with (package / 'droiddeck-unconfirmed').open('a') as marker:
            marker.write('start\nstart\nstart\n')
        self.assertEqual(0, self.install().returncode)
        self.assertFalse(self.requests.exists())

    def test_requested_repair_runs_once_and_is_retried_until_it_completes(self):
        package = self.break_client(0)
        (package / 'droiddeck-unconfirmed').unlink()
        request = self.home / '.bl-steam-repair'
        request.write_text('1\n')
        archive = self.cdn / 'steamdeck_publicbeta.zip'
        original = archive.read_bytes()
        archive.write_bytes(b'corrupt download')
        self.assertNotEqual(0, self.install().returncode)
        self.assertTrue(request.exists())
        self.assertFalse((package / 'droiddeck-installed').exists())
        archive.write_bytes(original)
        self.requests.unlink()
        self.assert_installed()
        self.assertFalse(request.exists())
        self.requests.unlink()
        self.assertEqual(0, self.install().returncode)
        self.assertFalse(self.requests.exists())


if __name__ == '__main__':
    unittest.main()
