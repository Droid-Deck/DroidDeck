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
        curl = self.bin / 'curl'
        curl.write_text('''#!/usr/bin/env python3
import os, sys
args = sys.argv[1:]
url = next(a for a in args if a.startswith('https://'))
out = args[args.index('-o') + 1]
name = url.rsplit('/', 1)[1]
offset = os.path.getsize(out) if '-C' in args and os.path.exists(out) else 0
with open(os.environ['TEST_REQUESTS'], 'a') as log:
    log.write(url + '\\n')
with open(os.environ['TEST_REQUESTS'] + '.offsets', 'a') as log:
    log.write(f'{name} {offset}\\n')
def done(status, code='200'):
    if '-w' in args:
        sys.stdout.write(code)
    sys.exit(status)
host_fail = os.environ.get('TEST_FAIL_HOST')
if host_fail and host_fail in url:
    done(22, '000')
action = []
plan = os.environ.get('TEST_PLAN')
if plan and os.path.exists(plan):
    lines = open(plan).read().splitlines()
    for n, line in enumerate(lines):
        if line.split()[0] == name:
            action = line.split()[1:]
            del lines[n]
            open(plan, 'w').write(''.join(l + '\\n' for l in lines))
            break
if action[:1] == ['fail']:
    done(int(action[1]), '000')
if action[:1] == ['http']:
    done(22, action[1])
if action[:1] == ['norange'] and offset:
    done(33)
data = open(os.path.join(os.environ['TEST_CDN'], name), 'rb').read()
end = int(action[1]) if action[:1] == ['cut'] else len(data)
with open(out, 'ab' if offset else 'wb') as target:
    target.write(data[offset:end])
done(28 if end < len(data) else 0)
''')
        curl.chmod(0o755)
        self.plan = self.root / 'plan'
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
                        TEST_CDN=str(self.cdn), TEST_REQUESTS=str(self.requests),
                        TEST_PLAN=str(self.plan), BL_STEAM_RETRY_DELAYS='0 0 0')
        for channel in ('steamdeck_publicbeta', 'steamdeck_stable'):
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

    def install(self, fail_host=None, *args, **extra):
        env = self.env if fail_host is None else dict(self.env, TEST_FAIL_HOST=fail_host)
        return subprocess.run(['bash', str(SCRIPT), *args], env=dict(env, **extra), text=True, capture_output=True)

    def channel(self, **extra):
        result = self.install(None, '--channel', **extra)
        self.assertEqual(0, result.returncode, result.stderr)
        return result.stdout.strip()

    def assert_installed(self, channel='steamdeck_publicbeta', fail_host=None, expected=None, **extra):
        result = self.install(fail_host, **extra)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(channel + '\n', (self.steam / 'package/beta').read_text())
        self.assertEqual('123456\n', (self.steam / 'package/droiddeck-installed').read_text())
        client = self.steam / 'steamrtarm64/steam'
        self.assertTrue(os.access(client, os.X_OK))
        self.assertEqual(channel, client.read_text())
        self.assertEqual((self.steam / 'linuxarm64').resolve(), (self.home / '.steam/sdkarm64').resolve())
        if expected is None:
            expected = [
                'https://client-update.steamstatic.com/steam_client_' + channel + '_linuxarm64',
                'https://client-update.steamstatic.com/' + channel + '.zip',
            ]
        self.assertEqual(expected, self.requests.read_text().splitlines())
        return result

    def test_downloads_the_deck_beta_client(self):
        self.assert_installed()

    def test_a_cdn_that_does_not_answer_hands_over_to_the_next_one(self):
        akamai = 'https://client-update.akamai.steamstatic.com'
        self.assert_installed(fail_host='client-update.steamstatic.com', expected=[
            'https://client-update.steamstatic.com/steam_client_steamdeck_publicbeta_linuxarm64',
            akamai + '/steam_client_steamdeck_publicbeta_linuxarm64',
            akamai + '/steamdeck_publicbeta.zip',
        ])

    def test_an_install_from_another_branch_is_left_for_steam_to_move(self):
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
        self.assertEqual(0, self.install(BL_STEAM_CHANNEL='beta').returncode)
        self.assertFalse(self.requests.exists())

    def test_the_default_channel_is_the_deck_beta(self):
        self.assertEqual('steamdeck_publicbeta', self.channel())

    def stable_requests(self, beta_manifest=True):
        stable = [
            'https://client-update.steamstatic.com/steam_client_steamdeck_stable_linuxarm64',
            'https://client-update.steamstatic.com/steamdeck_stable.zip',
        ]
        if beta_manifest:
            stable.insert(0, 'https://client-update.steamstatic.com/steam_client_steamdeck_publicbeta_linuxarm64')
        return stable

    def test_a_repaired_beta_client_that_still_shows_nothing_falls_back_to_stable(self):
        package = self.break_client(2)
        self.assertEqual(0, self.install().returncode)
        self.requests.unlink()
        with (package / 'droiddeck-unconfirmed').open('a') as marker:
            marker.write('start\nstart\n')
        result = self.assert_installed('steamdeck_stable', expected=self.stable_requests(False))
        self.assertIn("falling back to Valve's steamdeck_stable Steam client", result.stdout)
        self.assertEqual('steamdeck_stable\n', (package / 'droiddeck-channel').read_text())
        self.assertEqual('repaired\n', (package / 'droiddeck-unconfirmed').read_text())
        self.assertEqual('steamdeck_stable', self.channel())

    def test_the_stable_fallback_is_not_repeated(self):
        self.test_a_repaired_beta_client_that_still_shows_nothing_falls_back_to_stable()
        self.requests.unlink()
        with (self.steam / 'package/droiddeck-unconfirmed').open('a') as marker:
            marker.write('start\nstart\nstart\n')
        self.assertEqual(0, self.install().returncode)
        self.assertFalse(self.requests.exists())
        self.assertEqual('steamdeck_stable\n', (self.steam / 'package/beta').read_text())

    def test_a_pinned_beta_channel_never_falls_back(self):
        package = self.break_client(2)
        self.assertEqual(0, self.install().returncode)
        with (package / 'droiddeck-unconfirmed').open('a') as marker:
            marker.write('start\nstart\n')
        self.assertEqual('steamdeck_publicbeta', self.channel(BL_STEAM_CHANNEL='beta'))
        self.assertEqual(0, self.install(BL_STEAM_CHANNEL='beta').returncode)
        self.assertFalse((package / 'droiddeck-channel').exists())

    def test_the_channel_setting_selects_stable(self):
        setting = self.home / '.config/droiddeck/steam-channel'
        setting.parent.mkdir(parents=True)
        setting.write_text('stable\n')
        self.assertEqual('steamdeck_stable', self.channel())
        self.assert_installed('steamdeck_stable', expected=self.stable_requests(False))
        self.assertFalse((self.steam / 'package/droiddeck-channel').exists())

    def empty_beta_manifest(self):
        (self.cdn / 'steam_client_steamdeck_publicbeta_linuxarm64').write_text(
            '"linuxarm64"\n{\n\t"version" "123457"\n'
            '\t"client_steamrtarm64_linuxarm64"\n\t{\n'
            '\t\t"file" "client.zip"\n\t\t"sha2" "00"\n'
            '\t}\n}\n')

    def test_a_beta_manifest_without_an_arm64_client_falls_back_to_stable(self):
        self.empty_beta_manifest()
        result = self.assert_installed('steamdeck_stable', expected=self.stable_requests())
        self.assertIn('no client components in steam_client_steamdeck_publicbeta_linuxarm64', result.stderr)
        self.assertIn('client_steamrtarm64_linuxarm64', result.stderr)
        self.assertEqual('steamdeck_stable\n', (self.steam / 'package/droiddeck-channel').read_text())
        self.assertEqual('steamdeck_stable', self.channel())

    def test_a_pinned_beta_manifest_without_an_arm64_client_fails_and_names_its_blocks(self):
        self.empty_beta_manifest()
        result = self.install(BL_STEAM_CHANNEL='beta')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('client_steamrtarm64_linuxarm64', result.stderr)
        self.assertFalse((self.steam / 'package/droiddeck-installed').exists())

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

    def publish(self, **parts):
        """The CDN's client as these parts (block name -> byte count of incompressible content),
        listed with sizes as Valve's manifest lists them."""
        files = {}
        blocks = ''
        for block, length in parts.items():
            archive = self.cdn / f'{block}.zip.{hashlib.sha1(block.encode()).hexdigest()}'
            with zipfile.ZipFile(archive, 'w', zipfile.ZIP_STORED) as package:
                package.writestr(f'parts/{block}', os.urandom(length))
                if block.endswith('_linuxarm64_linuxarm64'):
                    package.writestr('steamrtarm64/steam', 'steamdeck_publicbeta')
            data = archive.read_bytes()
            files[block] = archive.name
            blocks += (f'\t"{block}"\n\t{{\n\t\t"file"\t\t"{archive.name}"\n\t\t"size"\t\t"{len(data)}"\n'
                       f'\t\t"sha2"\t\t"{hashlib.sha256(data).hexdigest()}"\n\t}}\n')
        (self.cdn / 'steam_client_steamdeck_publicbeta_linuxarm64').write_text(
            '"linuxarm64"\n{\n\t"version"\t\t"123456"\n' + blocks + '}\n')
        return files

    def two_parts(self):
        return self.publish(a_all=1_200_000, client_linuxarm64_linuxarm64=1_300_000)

    def plan_for(self, name, *actions):
        with self.plan.open('a') as plan:
            plan.writelines(f'{name} {action}\n' for action in actions)

    def offsets(self, name):
        log = Path(str(self.requests) + '.offsets')
        return [int(line.split()[1]) for line in log.read_text().splitlines() if line.split()[0] == name]

    def requested(self):
        return [line.rsplit('/', 1)[1] for line in self.requests.read_text().splitlines()]

    @property
    def download(self):
        return self.steam / 'package/droiddeck-download'

    def test_progress_is_counted_in_megabytes_across_parts(self):
        self.two_parts()
        result = self.install()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('downloading Steam: a_all (1/2), 0 of 2 MB\n', result.stdout)
        self.assertIn('downloading Steam: client_linuxarm64_linuxarm64 (2/2), 1 of 2 MB\n', result.stdout)
        self.assertTrue((self.steam / 'parts/a_all').is_file())
        self.assertFalse(self.download.exists())

    def test_a_dropped_connection_resumes_from_what_arrived(self):
        files = self.two_parts()
        client = files['client_linuxarm64_linuxarm64']
        self.plan_for(client, 'cut 500000')
        result = self.install()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([0, 500000], self.offsets(client))
        self.assertIn('interrupted (curl exit 28), trying again in 0 s', result.stdout)
        self.assertEqual('123456\n', (self.steam / 'package/droiddeck-installed').read_text())

    def test_a_failed_session_keeps_what_it_finished_and_fetched(self):
        files = self.two_parts()
        client = files['client_linuxarm64_linuxarm64']
        self.plan_for(client, 'cut 400000', *['fail 6'] * 12)
        result = self.install()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('could not download client_linuxarm64_linuxarm64 (curl exit 6)', result.stderr)
        self.assertFalse((self.steam / 'package/droiddeck-installed').exists())
        self.assertTrue((self.download / (files['a_all'] + '.unpacked')).is_file())
        self.assertFalse((self.download / files['a_all']).exists())
        self.assertEqual(400000, (self.download / client).stat().st_size)
        self.requests.unlink()
        Path(str(self.requests) + '.offsets').unlink()
        self.assertEqual(0, self.install().returncode)
        self.assertEqual(['steam_client_steamdeck_publicbeta_linuxarm64', client], self.requested())
        self.assertEqual([400000], self.offsets(client))
        self.assertFalse(self.download.exists())

    def test_a_server_that_will_not_resume_starts_the_part_over(self):
        files = self.two_parts()
        client = files['client_linuxarm64_linuxarm64']
        self.plan_for(client, 'cut 400000', 'norange')
        result = self.install()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([0, 400000, 0], self.offsets(client))

    def test_a_missing_part_hands_over_to_the_next_cdn_at_once(self):
        files = self.two_parts()
        client = files['client_linuxarm64_linuxarm64']
        self.plan_for(client, 'http 404')
        result = self.install()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['https://client-update.steamstatic.com/' + client,
                          'https://client-update.akamai.steamstatic.com/' + client],
                         [line for line in self.requests.read_text().splitlines() if line.endswith(client)])
        self.assertIn('HTTP 404 from https://client-update.steamstatic.com', result.stderr)
        self.assertNotIn('trying again', result.stdout)

    def test_a_server_error_is_retried_on_the_same_cdn(self):
        files = self.two_parts()
        client = files['client_linuxarm64_linuxarm64']
        self.plan_for(client, 'http 503')
        result = self.install()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['https://client-update.steamstatic.com/' + client] * 2,
                         [line for line in self.requests.read_text().splitlines() if line.endswith(client)])
        self.assertIn('interrupted (curl exit 22), trying again in 0 s', result.stdout)

    def test_bytes_that_do_not_check_out_are_fetched_again(self):
        files = self.two_parts()
        client = files['client_linuxarm64_linuxarm64']
        self.download.mkdir(parents=True)
        (self.download / client).write_bytes(b'x' * (self.cdn / client).stat().st_size)
        result = self.install()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([0], self.offsets(client))

    def test_parts_left_for_another_client_version_are_dropped(self):
        files = self.two_parts()
        self.download.mkdir(parents=True)
        (self.download / 'a_all.zip.0123').write_bytes(b'older')
        (self.download / 'b_all.zip.4567.unpacked').write_bytes(b'')
        self.plan_for(files['client_linuxarm64_linuxarm64'], *['fail 7'] * 12)
        self.assertNotEqual(0, self.install().returncode)
        self.assertEqual([files['a_all'] + '.unpacked'], sorted(p.name for p in self.download.iterdir()))

    def test_a_repair_unpacks_every_part_again_once(self):
        files = self.two_parts()
        self.assertEqual(0, self.install().returncode)
        (self.home / '.bl-steam-repair').write_text('1\n')
        self.requests.unlink()
        self.plan_for(files['client_linuxarm64_linuxarm64'], *['fail 7'] * 12)
        self.assertNotEqual(0, self.install().returncode)
        self.assertIn(files['a_all'], self.requested())
        self.requests.unlink()
        self.assertEqual(0, self.install().returncode)
        self.assertEqual(['steam_client_steamdeck_publicbeta_linuxarm64', files['client_linuxarm64_linuxarm64']],
                         self.requested())
        self.assertFalse((self.home / '.bl-steam-repair').exists())
        self.assertFalse(self.download.exists())


if __name__ == '__main__':
    unittest.main()
