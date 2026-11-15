import hashlib
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
from zipfile import ZIP_DEFLATED, ZIP_STORED, ZipFile, ZipInfo

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import deploy_fast


def make_apk(path, entries):
    with ZipFile(path, 'w') as package:
        for name, (data, method) in entries.items():
            info = ZipInfo(name)
            info.extra = b'\0' * 6
            package.writestr(info, data, compress_type=method)


class ClassifyTest(unittest.TestCase):
    agent = 'tools/linuxfs/overlay/usr/local/bin/droiddeck-agent'
    targets = {agent: 'assets/linuxfs/usr/local/bin/droiddeck-agent',
               'tools/linuxfs/overlay/usr/local/bin/droiddeck-new': 'assets/linuxfs/usr/local/bin/droiddeck-new'}
    entries = {'assets/linuxfs/usr/local/bin/droiddeck-agent'}

    def classify(self, changes):
        return deploy_fast.classify(changes, self.targets, self.entries)

    def test_helpers_graft_and_tests_or_docs_are_ignored(self):
        grafts, ignored, blocked = self.classify({
            self.agent: 'M', 'tools/tests/test_x.py': 'M', 'docs/a/b.txt': 'A', 'README.md': 'M'})
        self.assertEqual(grafts, {self.agent: 'assets/linuxfs/usr/local/bin/droiddeck-agent'})
        self.assertEqual(sorted(ignored), ['README.md', 'docs/a/b.txt', 'tools/tests/test_x.py'])
        self.assertEqual(blocked, {})

    def test_everything_else_fails_closed(self):
        changes = {'app/src/main/java/com/droiddeck/launcher/Foo.kt': 'M', 'tools/proot/build.sh': 'M',
                   'tools/linuxfs/overlay/usr/local/bin/droiddeck-new': 'A'}
        self.assertEqual(set(self.classify(changes)[2]), set(changes))
        self.assertEqual(set(self.classify({self.agent: 'D'})[2]), {self.agent})

    def test_real_graft_targets_exist_in_the_overlay(self):
        for path in deploy_fast.graft_targets():
            self.assertTrue((deploy_fast.ROOT / path).is_file(), path)


class GraftTest(unittest.TestCase):
    def test_replaces_entries_keeps_the_rest_and_drops_the_signature(self):
        with tempfile.TemporaryDirectory() as directory:
            donor, output = Path(directory) / 'donor.apk', Path(directory) / 'out.apk'
            make_apk(donor, {'classes.dex': (b'dex', ZIP_DEFLATED), 'lib/arm64-v8a/libx.so': (b'elf', ZIP_STORED),
                             'assets/a': (b'old', ZIP_DEFLATED), 'META-INF/MANIFEST.MF': (b'm', ZIP_DEFLATED),
                             'META-INF/CERT.SF': (b's', ZIP_DEFLATED), 'META-INF/CERT.RSA': (b'r', ZIP_DEFLATED),
                             'META-INF/keep.version': (b'v', ZIP_DEFLATED)})
            deploy_fast.graft(donor, {'assets/a': b'new'}, output)
            with ZipFile(output) as package:
                self.assertEqual(package.read('assets/a'), b'new')
                self.assertEqual(package.read('classes.dex'), b'dex')
                self.assertEqual(package.getinfo('lib/arm64-v8a/libx.so').compress_type, ZIP_STORED)
                self.assertEqual(package.getinfo('classes.dex').compress_type, ZIP_DEFLATED)
                self.assertEqual(package.getinfo('classes.dex').extra, b'')
                self.assertEqual([n for n in package.namelist() if n.startswith('META-INF/')],
                                 ['META-INF/keep.version'])

    def test_unknown_replacement_entry_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            donor = Path(directory) / 'donor.apk'
            make_apk(donor, {'assets/a': (b'old', ZIP_DEFLATED)})
            with self.assertRaises(deploy_fast.Refused):
                deploy_fast.graft(donor, {'assets/missing': b'x'}, Path(directory) / 'out.apk')


class RepoTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        patch = mock.patch.object(deploy_fast, 'ROOT', self.root)
        patch.start()
        self.addCleanup(patch.stop)
        self.git('init', '-q', '-b', 'trunk')
        self.git('config', 'user.email', 'a@b.c')
        self.git('config', 'user.name', 'a')
        (self.root / 'kept').write_text('1')
        (self.root / 'gone').write_text('1')
        self.git('add', '.')
        self.git('commit', '-q', '-m', 'base')
        self.base = self.git('rev-parse', 'HEAD').strip()

    def git(self, *args):
        return subprocess.run(['git', '-C', str(self.root), *args], capture_output=True, text=True, check=True).stdout

    def test_changed_paths_cover_staged_untracked_and_deleted_files(self):
        (self.root / 'kept').write_text('2')
        (self.root / 'gone').unlink()
        (self.root / 'fresh').write_text('x')
        self.git('add', 'kept')
        self.assertEqual(deploy_fast.changed_paths(self.base), {'kept': 'M', 'gone': 'D', 'fresh': 'A'})

    def test_helper_bytes_normalize_text_but_not_binaries(self):
        (self.root / 'script').write_bytes(b'a\r\nb\r\n')
        (self.root / 'binary').write_bytes(b'\0a\r\n')
        self.assertEqual(deploy_fast.helper_bytes('script'), b'a\nb\n')
        self.assertEqual(deploy_fast.helper_bytes('binary'), b'\0a\r\n')

    def test_a_donor_ahead_of_the_checkout_is_not_an_ancestor(self):
        self.git('checkout', '-q', '-b', 'side')
        (self.root / 'kept').write_text('3')
        self.git('commit', '-qam', 'side')
        side = self.git('rev-parse', 'HEAD').strip()
        self.git('checkout', '-q', 'trunk')
        self.assertTrue(deploy_fast.is_ancestor(self.base))
        self.assertFalse(deploy_fast.is_ancestor(side))


class DonorTest(unittest.TestCase):
    def test_pick_tag_takes_the_newest_ancestor(self):
        commits = {'main-new': 'n', 'main-mid': 'm', 'main-old': 'o'}
        with mock.patch.object(deploy_fast, 'release_tags', return_value=list(commits)), \
                mock.patch.object(deploy_fast, 'donor_commit', side_effect=lambda tag: commits[tag]), \
                mock.patch.object(deploy_fast, 'is_ancestor', side_effect=lambda commit: commit != 'n'):
            self.assertEqual(deploy_fast.pick_tag(None), 'main-mid')
            self.assertEqual(deploy_fast.pick_tag('main-new'), 'main-new')

    def test_pick_tag_without_an_ancestor_is_refused(self):
        with mock.patch.object(deploy_fast, 'release_tags', return_value=['main-a']), \
                mock.patch.object(deploy_fast, 'donor_commit', return_value='a'), \
                mock.patch.object(deploy_fast, 'is_ancestor', return_value=False):
            with self.assertRaises(deploy_fast.Refused):
                deploy_fast.pick_tag(None)

    def test_checksum_mismatch_discards_the_cached_donor(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory) / 'main-x'
            folder.mkdir()
            apk = folder / 'DroidDeck-main-x.apk'
            sums = folder / 'SHA256SUMS.txt'
            apk.write_bytes(b'apk')
            sums.write_text('0' * 64 + '  DroidDeck-main-x.apk\n')
            with mock.patch.object(deploy_fast, 'CACHE', Path(directory)):
                with self.assertRaises(deploy_fast.Refused):
                    deploy_fast.fetch_donor('main-x')
                self.assertFalse(apk.exists())
                apk.write_bytes(b'apk')
                sums.write_text(hashlib.sha256(b'apk').hexdigest() + '  DroidDeck-main-x.apk\n')
                self.assertEqual(deploy_fast.fetch_donor('main-x'), apk)

    def test_a_cached_apk_without_checksums_is_downloaded_again(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory) / 'main-x'
            folder.mkdir()
            apk = folder / 'DroidDeck-main-x.apk'
            apk.write_bytes(b'truncated')
            calls = []
            sums = hashlib.sha256(b'apk').hexdigest() + '  ' + apk.name + '\n'

            def download(command, timeout, **options):
                pattern = command[command.index('-p') + 1]
                calls.append(pattern)
                (folder / pattern).write_bytes(b'apk' if pattern.endswith('.apk') else sums.encode())
                return mock.Mock(returncode=0, stderr='')

            with mock.patch.object(deploy_fast, 'CACHE', Path(directory)):
                with mock.patch.object(deploy_fast, 'run_bounded', download):
                    self.assertEqual(deploy_fast.fetch_donor('main-x'), apk)
            self.assertEqual(calls, [apk.name, 'SHA256SUMS.txt'])

    def test_a_failed_download_removes_the_apk_and_a_timeout_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory) / 'main-x'
            folder.mkdir()
            apk = folder / 'DroidDeck-main-x.apk'
            apk.write_bytes(b'partial')
            failed = mock.Mock(returncode=1, stderr='boom')
            with mock.patch.object(deploy_fast, 'CACHE', Path(directory)):
                with mock.patch.object(deploy_fast, 'run_bounded', return_value=failed):
                    with self.assertRaises(deploy_fast.Refused):
                        deploy_fast.fetch_donor('main-x')
            self.assertFalse(apk.exists())
        timeout = deploy_fast.subprocess.TimeoutExpired('git', 1)
        with mock.patch.object(deploy_fast.subprocess, 'run', side_effect=timeout):
            with self.assertRaises(deploy_fast.Refused):
                deploy_fast.git('status')


class SignTest(unittest.TestCase):
    def test_paths_with_apostrophes_are_escaped_for_powershell(self):
        seen = []

        def run(command, **options):
            seen.append(command)
            return mock.Mock(returncode=1, stdout='', stderr='')

        with mock.patch.object(deploy_fast, 'pwsh', return_value='pwsh'), \
                mock.patch.object(deploy_fast, 'signing_env', return_value=None):
            with mock.patch.object(deploy_fast, 'SIGNER', Path("C:/O'Brien/sign.ps1")):
                with mock.patch.object(deploy_fast.subprocess, 'run', side_effect=run):
                    with self.assertRaises(deploy_fast.Refused):
                        deploy_fast.sign(Path("C:/a'b/in.apk"), Path("C:/a'b/out.apk"))
        script = seen[0][-1]
        self.assertIn("O''Brien", script)
        self.assertIn("a''b", script)
        self.assertNotIn("a'b", script.replace("a''b", ''))
        self.assertNotIn("O'Brien", script.replace("O''Brien", ''))

    def test_signing_env_prefers_the_override_then_the_checkout_then_the_main_checkout(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            (base / 'main').mkdir()
            (base / 'checkout').mkdir()
            base = base.resolve()
            override, local, shared = base / 'override.env', base / 'checkout/.signing.env', base / 'main/.signing.env'
            common = (str(base / 'main/.git') + '\n').encode()
            with mock.patch.object(deploy_fast, 'ROOT', base / 'checkout'), \
                    mock.patch.object(deploy_fast, 'git', return_value=common), \
                    mock.patch.dict(deploy_fast.os.environ, {'DROIDDECK_SIGNING_ENV': str(override)}):
                self.assertIsNone(deploy_fast.signing_env())
                shared.write_text('x')
                self.assertEqual(deploy_fast.signing_env(), shared)
                local.write_text('x')
                self.assertEqual(deploy_fast.signing_env(), local)
                override.write_text('x')
                self.assertEqual(deploy_fast.signing_env(), override)

    def test_the_env_signer_is_used_when_the_windows_signer_is_missing(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            (base / 'build-tools/35.0.0').mkdir(parents=True)
            for tool in ('aapt', 'zipalign', 'apksigner'):
                (base / 'build-tools/35.0.0' / tool).write_text('')
            signed = base / 'out.apk'

            def run(command, **options):
                signed.write_text('apk')
                return mock.Mock(returncode=0, stdout='', stderr='')

            with mock.patch.object(deploy_fast, 'SIGNER', base / 'missing.ps1'), \
                    mock.patch.object(deploy_fast, 'signing_env', return_value=base / '.signing.env'), \
                    mock.patch.object(deploy_fast, 'sdk_root', return_value=base), \
                    mock.patch.object(deploy_fast.subprocess, 'run', side_effect=run) as run_:
                deploy_fast.sign(base / 'in.apk', signed)
            self.assertEqual(run_.call_args[0][0][-4:], [str(deploy_fast.ROOT / 'tools/release/sign-apk.sh'), str(base / 'in.apk'), str(signed), str((base / 'build-tools/35.0.0').resolve())])
            failed = mock.Mock(returncode=1, stdout='', stderr='boom')
            signed.unlink()
            with mock.patch.object(deploy_fast, 'SIGNER', base / 'missing.ps1'), \
                    mock.patch.object(deploy_fast, 'signing_env', return_value=base / '.signing.env'), \
                    mock.patch.object(deploy_fast, 'sdk_root', return_value=base), \
                    mock.patch.object(deploy_fast.subprocess, 'run', return_value=failed):
                with self.assertRaises(deploy_fast.Refused):
                    deploy_fast.sign(base / 'in.apk', signed)

    def test_the_newest_build_tools_win_numerically_and_the_env_file_cannot_replace_them(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory).resolve()
            for version in ('9.0.0', '35.0.0', '100.0.0'):
                (base / 'build-tools' / version).mkdir(parents=True)
                for tool in ('aapt', 'zipalign', 'apksigner'):
                    (base / 'build-tools' / version / tool).write_text('')
            (base / 'build-tools/200.0.0').mkdir()
            (base / 'build-tools/200.0.0/aapt').write_text('')
            root = base / 'checkout'
            (root / 'tools/release').mkdir(parents=True)
            fake = root / 'tools/release/sign-apk.sh'
            fake.write_text('#!/bin/bash\necho "$BUILD_TOOLS" > "$3"\n')
            fake.chmod(0o755)
            env_file = base / 'keys/.signing.env'
            env_file.parent.mkdir()
            env_file.write_text('BUILD_TOOLS=/wrong\n')
            signed = base / 'out.apk'
            with mock.patch.object(deploy_fast, 'ROOT', root), mock.patch.object(deploy_fast, 'sdk_root', return_value=base):
                deploy_fast.sign_with_env(env_file, base / 'in.apk', signed)
            self.assertEqual(signed.read_text().strip(), str(base / 'build-tools/100.0.0'))


if __name__ == '__main__':
    unittest.main()
