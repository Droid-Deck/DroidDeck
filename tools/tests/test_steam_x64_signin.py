from pathlib import Path
import os
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin/droiddeck-steam-x64'

REGISTRY = ('"Registry"\n{\n\t"HKCU"\n\t{\n\t\t"Software"\n\t\t{\n\t\t\t"Valve"\n\t\t\t{\n\t\t\t\t"Steam"\n\t\t\t\t{\n'
            '\t\t\t\t\t"language"\t\t"english"\n%s\t\t\t\t}\n\t\t\t}\n\t\t}\n\t}\n}\n')
CONFIG = ('"InstallConfigStore"\n{\n\t"Software"\n\t{\n\t\t"Valve"\n\t\t{\n\t\t\t"Steam"\n\t\t\t{\n%s'
          '\t\t\t\t"CompatToolMapping"\n\t\t\t\t{\n\t\t\t\t}\n\t\t\t}\n\t\t}\n\t}\n}\n')
ACCOUNTS = ('\t\t\t\t"Accounts"\n\t\t\t\t{\n\t\t\t\t\t"someone"\n\t\t\t\t\t{\n'
            '\t\t\t\t\t\t"SteamID"\t\t"76561198000000001"\n\t\t\t\t\t}\n\t\t\t\t}\n')


def local(token):
    entry = '\t\t"abc1"\t\t"%s"\n' % token if token else ''
    return '"MachineUserConfigStore"\n{\n\t"ConnectCache"\n\t{\n%s\t}\n}\n' % entry


class X64SignInTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        self.arm = self.home / '.local/share/Steam'
        self.x64 = self.home / '.droiddeck-x64/.local/share/Steam'
        for root in (self.arm, self.x64):
            (root / 'config').mkdir(parents=True)
        (self.home / '.steam').mkdir()
        (self.home / '.droiddeck-x64/.steam').mkdir(parents=True)
        self.write(self.arm / 'local.vdf', local('00ff'))
        self.write(self.arm / 'config/loginusers.vdf', '"users"\n{\n}\n')
        self.write(self.arm / 'config/config.vdf', CONFIG % ACCOUNTS)
        self.write(self.home / '.steam/registry.vdf', REGISTRY % '\t\t\t\t\t"AutoLoginUser"\t\t"someone"\n')
        self.write(self.x64 / 'local.vdf', local(None))
        self.write(self.x64 / 'config/loginusers.vdf', '"users"\n{\n}\n')
        self.write(self.x64 / 'config/config.vdf', CONFIG % '')
        self.write(self.home / '.droiddeck-x64/.steam/registry.vdf', REGISTRY % '')
        self.bin = self.home / 'bin'
        self.bin.mkdir()
        (self.bin / 'pgrep').write_text('#!/bin/sh\nexit 1\n')
        (self.bin / 'pgrep').chmod(0o755)

    def write(self, path, text):
        path.write_text(text)

    def sync(self):
        env = dict(os.environ, HOME=str(self.home), PATH='%s:%s' % (self.bin, os.environ['PATH']))
        env.pop('XDG_DATA_HOME', None)
        env.pop('BL_X64_HOME', None)
        return subprocess.run(['bash', str(SCRIPT), '--sync-login'], env=env, capture_output=True, text=True, check=True)

    def test_a_refused_x64_sign_in_gets_the_token_account_and_auto_login(self):
        self.assertIn('sign-in shared from the arm64 client', self.sync().stdout)
        self.assertEqual(local('00ff'), (self.x64 / 'local.vdf').read_text())
        self.assertIn('"SteamID"\t\t"76561198000000001"', (self.x64 / 'config/config.vdf').read_text())
        self.assertIn('"AutoLoginUser"\t\t"someone"', (self.home / '.droiddeck-x64/.steam/registry.vdf').read_text())

    def test_an_earlier_copy_still_gains_the_account_and_nothing_repeats(self):
        self.sync()
        self.write(self.x64 / 'config/config.vdf', CONFIG % '')
        self.write(self.home / '.droiddeck-x64/.steam/registry.vdf', REGISTRY % '')
        self.assertNotIn('sign-in shared', self.sync().stdout)
        self.assertIn('"76561198000000001"', (self.x64 / 'config/config.vdf').read_text())
        self.assertIn('"AutoLoginUser"', (self.home / '.droiddeck-x64/.steam/registry.vdf').read_text())
        self.sync()
        for root in (self.arm, self.x64):
            self.assertEqual(1, (root / 'config/config.vdf').read_text().count('"SteamID"'))
        self.assertEqual(1, (self.home / '.droiddeck-x64/.steam/registry.vdf').read_text().count('AutoLoginUser'))

    def test_a_newer_x64_sign_in_moves_to_arm64(self):
        self.write(self.x64 / 'local.vdf', local('11ee'))
        os.utime(self.arm / 'local.vdf', (1, 1))
        self.assertIn('from the x86-64 client', self.sync().stdout)
        self.assertEqual(local('11ee'), (self.arm / 'local.vdf').read_text())


if __name__ == '__main__':
    unittest.main()
