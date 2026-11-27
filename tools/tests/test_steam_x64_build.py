from pathlib import Path
import os
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin/droiddeck-steam-x64'


class X64BuildTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        self.package = self.home / '.droiddeck-x64/.local/share/Steam/package'
        self.package.mkdir(parents=True)

    def manifest(self, channel, version):
        (self.package / f'steam_client_{channel}_steamrt64.manifest').write_text(
            f'"steamrt64"\n{{\n\t"version" "{version}"\n}}\n')

    def build(self):
        env = dict(os.environ, HOME=str(self.home))
        env.pop('XDG_DATA_HOME', None)
        env.pop('DD_X64_HOME', None)
        result = subprocess.run(['bash', str(SCRIPT), '--build'], env=env, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        return result.stdout.strip()

    def test_the_build_comes_from_the_default_channel_manifest(self):
        self.manifest('steamdeck_publicbeta', '1001')
        self.assertEqual('1001', self.build())

    def test_the_build_follows_the_channel_the_client_is_on(self):
        self.manifest('steamdeck_publicbeta', '1001')
        self.manifest('steamdeck_stable', '900')
        (self.package / 'beta').write_text('steamdeck_stable\n')
        self.assertEqual('900', self.build())

    def test_no_manifest_gives_no_build(self):
        self.assertEqual('', self.build())


if __name__ == '__main__':
    unittest.main()
