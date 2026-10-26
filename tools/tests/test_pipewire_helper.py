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

    def run_helper(self, *args):
        with tempfile.TemporaryFile('w+') as out, tempfile.TemporaryFile('w+') as err:
            done = subprocess.run(['sh', str(self.helper), *args], env=self.environment, stdout=out, stderr=err,
                                  text=True, timeout=15)
            out.seek(0)
            err.seek(0)
            return subprocess.CompletedProcess(done.args, done.returncode, out.read(), err.read())

    def tearDown(self):
        self.run_helper('stop')

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
