import os
import subprocess
import tarfile
import tempfile
import unittest
from pathlib import Path

SOURCE = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin/droiddeck-pipewire'


class PipeWireHelperTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.lib = self.root / 'lib'
        self.runtime = self.root / 'run'
        self.lib.mkdir()
        self.runtime.mkdir()
        self.bundle = self.lib / 'droiddeck-pipewire.tzst'
        self.dir = self.lib / 'droiddeck-pipewire'
        text = SOURCE.read_text().replace('/usr/local/lib/droiddeck-pipewire', str(self.dir))
        self.helper = self.root / 'droiddeck-pipewire'
        self.helper.write_text(text)
        self.environment = dict(os.environ, XDG_RUNTIME_DIR=str(self.runtime))
        self.started = []

    def pack(self, daemon_body):
        tree = self.root / 'tree'
        for name, body in (('pipewire', daemon_body), ('wireplumber', 'exec sleep 30')):
            path = tree / 'bin' / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('#!/bin/sh\n' + body + '\n')
            path.chmod(0o755)
        tar = self.root / 'bundle.tar'
        with tarfile.open(tar, 'w') as archive:
            archive.add(tree, arcname='.')
        subprocess.run(['zstd', '-q', '-f', str(tar), '-o', str(self.bundle)], check=True)

    def pack_with_stub_zstd(self, delay=''):
        tree = self.root / 'tree'
        daemon = tree / 'bin/pipewire'
        daemon.parent.mkdir(parents=True)
        daemon.write_text('#!/bin/sh\npython3 -c "import socket,sys; s=socket.socket(socket.AF_UNIX); '
                          's.bind(sys.argv[1]); import time; time.sleep(30)" "$XDG_RUNTIME_DIR/pipewire-0"\n')
        (tree / 'bin/wireplumber').write_text('#!/bin/sh\nexec sleep 30\n')
        for script in daemon.parent.iterdir():
            script.chmod(0o755)
        with tarfile.open(self.bundle, 'w') as archive:
            archive.add(tree, arcname='.')
        stubs = self.root / 'stubs'
        stubs.mkdir()
        slow = stubs / 'zstd'
        slow.write_text('#!/bin/sh\n' + delay + 'exec cat -- "$2"\n')
        slow.chmod(0o755)
        self.environment['PATH'] = '%s:%s' % (stubs, os.environ['PATH'])

    def run_helper(self, *args, environment=None):
        with tempfile.TemporaryFile('w+') as out, tempfile.TemporaryFile('w+') as err:
            done = subprocess.run(['sh', str(self.helper), *args], env=environment or self.environment, stdout=out, stderr=err,
                                  text=True, timeout=15)
            out.seek(0)
            err.seek(0)
            return subprocess.CompletedProcess(done.args, done.returncode, out.read(), err.read())

    def tearDown(self):
        self.run_helper('stop')
        for environment in self.started:
            self.run_helper('stop', environment=environment)

    def test_env_points_clients_at_the_bundle(self):
        out = self.run_helper('env').stdout
        for part in ('PIPEWIRE_CONFIG_DIR=%s/share/pipewire', 'PIPEWIRE_MODULE_DIR=%s/lib/pipewire-0.3',
                     'SPA_PLUGIN_DIR=%s/lib/spa-0.2'):
            self.assertIn(part % self.dir, out)

    def test_start_waits_for_the_socket_and_stop_ends_both_daemons(self):
        socket = self.runtime / 'pipewire-0'
        self.pack('python3 -c "import socket,sys; s=socket.socket(socket.AF_UNIX); s.bind(sys.argv[1]); '
                  'import time; time.sleep(30)" "$XDG_RUNTIME_DIR/pipewire-0"')
        result = self.run_helper('start')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(socket.is_socket())
        pids = (self.runtime / 'droiddeck-pipewire/pids').read_text().split()
        self.assertEqual(len(pids), 2)
        self.run_helper('stop')
        for pid in pids:
            subprocess.run(['sh', '-c', 'for i in 1 2 3 4 5 6 7 8 9 10; do kill -0 %s 2>/dev/null || exit 0; '
                            'sleep 0.1; done; exit 1' % pid], check=True)

    def test_start_fails_cleanly_when_pipewire_never_listens(self):
        self.pack('exec sleep 30')
        result = self.run_helper('start')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('did not open', result.stderr)
        self.assertFalse((self.runtime / 'droiddeck-pipewire/pids').exists())

    def test_clients_get_full_access_without_the_flatpak_check(self):
        self.pack('python3 -c "import socket,sys; s=socket.socket(socket.AF_UNIX); s.bind(sys.argv[1]); '
                  'import time; time.sleep(30)" "$XDG_RUNTIME_DIR/pipewire-0"')
        for _ in range(2):
            result = self.run_helper('start')
            self.assertEqual(result.returncode, 0, result.stderr)
            daemon = (self.dir / 'share/pipewire/pipewire.conf.d/droiddeck-access.conf').read_text()
            self.assertIn('module.access = false', daemon)
            self.assertIn('name = libpipewire-module-access', daemon)
            self.assertIn('access.socket = { pipewire-0 = "unrestricted", pipewire-0-manager = "unrestricted" }',
                          daemon)
            self.assertNotIn('access.legacy', daemon)
            manager = (self.dir / 'share/wireplumber/wireplumber.conf.d/droiddeck-access.conf').read_text()
            self.assertIn('matches = [ { pipewire.access = "unrestricted" } ]', manager)
            self.assertIn('default_permissions = "all"', manager)

    def test_start_fails_cleanly_when_the_access_settings_cannot_be_written(self):
        self.pack('exec sleep 30')
        self.run_helper('start')
        self.run_helper('stop')
        conf = self.dir / 'share/pipewire/pipewire.conf.d'
        for child in conf.iterdir():
            child.unlink()
        conf.rmdir()
        conf.write_text('')
        result = self.run_helper('start')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('could not write the access settings', result.stderr)
        self.assertFalse((self.runtime / 'droiddeck-pipewire/pids').exists())

    def test_access_settings_are_replaced_atomically_without_leftovers(self):
        self.pack_with_stub_zstd()
        self.assertEqual(self.run_helper('start').returncode, 0)
        fragments = [self.dir / 'share/pipewire/pipewire.conf.d/droiddeck-access.conf',
                     self.dir / 'share/wireplumber/wireplumber.conf.d/droiddeck-access.conf']
        before = [(f.stat().st_ino, f.read_text()) for f in fragments]
        held = [f.open() for f in fragments]
        self.assertEqual(self.run_helper('start').returncode, 0)
        for handle, (_, text) in zip(held, before):
            self.assertEqual(handle.read(), text)
            handle.close()
        for fragment, (inode, text) in zip(fragments, before):
            self.assertNotEqual(fragment.stat().st_ino, inode)
            self.assertEqual(fragment.read_text(), text)
            self.assertEqual([c.name for c in fragment.parent.iterdir()], [fragment.name])

    def test_concurrent_starts_share_one_unpack(self):
        self.pack_with_stub_zstd('sleep 1\n')
        errors = [tempfile.TemporaryFile('w+') for _ in range(3)]
        for index in range(len(errors)):
            runtime = self.root / ('run%d' % index)
            runtime.mkdir()
            self.started.append(dict(self.environment, XDG_RUNTIME_DIR=str(runtime)))
        runs = [subprocess.Popen(['sh', str(self.helper), 'start'], env=environment,
                                 stdout=subprocess.DEVNULL, stderr=err, text=True)
                for environment, err in zip(self.started, errors)]
        for run, err in zip(runs, errors):
            run.wait(timeout=30)
            err.seek(0)
            self.assertEqual(run.returncode, 0, err.read())
        self.assertTrue((self.dir / 'bin/pipewire').is_file())
        self.assertFalse((self.lib / 'droiddeck-pipewire.new').exists())

    def test_unchanged_bundle_is_not_unpacked_again(self):
        self.pack('exec sleep 30')
        self.run_helper('start')
        marker = self.dir / 'kept'
        marker.write_text('x')
        self.run_helper('start')
        self.assertTrue(marker.exists())
        self.pack('exec sleep 31')
        self.run_helper('start')
        self.assertFalse(marker.exists())


if __name__ == '__main__':
    unittest.main()
