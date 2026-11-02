import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


BIN = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin'
SHIM = BIN / 'droiddeck-steamrt-shim'
SESSION = (BIN / 'droiddeck-session').read_text()
X64 = (BIN / 'droiddeck-steam-x64').read_text()


class SteamrtShimTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.client = self.root / 'steamrtarm64'
        self.client.mkdir()
        self.shim = self.root / 'shim'

    def runtime(self, path):
        runtime = self.client / path
        (runtime / 'bin').mkdir(parents=True)
        (runtime / 'bin/steam-runtime-check-requirements').write_text('#!/bin/sh\nexit 1\n')
        (runtime / 'bin/steam-runtime-system-info').write_text('#!/bin/sh\necho info\n')
        (runtime / 'files').mkdir()
        (runtime / 'files/marker').write_text('runtime files')
        return runtime

    def build(self):
        return subprocess.run(['bash', str(SHIM), str(self.client), str(self.shim)],
                              capture_output=True, text=True, timeout=30)

    def sh(self, *args):
        return subprocess.run(['sh', *map(str, args)], capture_output=True, text=True, timeout=30)

    def assert_shim(self):
        result = self.build()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(0, self.sh(self.shim / 'bin/steam-runtime-check-requirements').returncode)
        self.assertEqual('info\n', self.sh(self.shim / 'bin/steam-runtime-system-info').stdout)
        self.assertEqual('runtime files', (self.shim / 'files/marker').read_text())
        launched = self.sh(self.shim / 'bin/steam-runtime-launch-client', '--env=BL_SHIM_TEST=yes',
                           f'--directory={self.root}', '--', 'sh', '-c', 'echo "$BL_SHIM_TEST $(basename "$PWD")"')
        self.assertEqual(f'yes {self.root.name}\n', launched.stdout)
        launched = self.sh(self.shim / 'bin/steam-runtime-launch-client', '--env', 'BL_SHIM_TEST=split',
                           '--directory', self.root, '--', 'sh', '-c', 'echo "$BL_SHIM_TEST $(basename "$PWD")"')
        self.assertEqual(f'split {self.root.name}\n', launched.stdout)
        missing = self.sh(self.shim / 'bin/steam-runtime-launch-client', '--directory', self.root / 'missing',
                          '--', 'true')
        self.assertEqual(127, missing.returncode)

    def test_a_client_without_a_container_gets_no_shim(self):
        self.shim.mkdir()
        self.assertEqual(1, self.build().returncode)
        self.assertFalse(self.shim.exists())

    def test_the_pressure_vessel_runtime_is_shimmed(self):
        self.runtime('pv-runtime/steam-runtime-steamrt')
        self.assert_shim()

    def test_a_requirements_probe_elsewhere_is_shimmed(self):
        self.runtime('steam-runtime')
        self.assert_shim()

    def test_the_arm64_session_starts_a_containerized_client_through_the_shim(self):
        block = re.search(r'\n( +steam_client=\("\$steam_root/steamrtarm64/steam"\)\n.*?\n    fi\n)', SESSION, re.S).group(1)
        stub = self.root / 'droiddeck-steamrt-shim'
        for status, shimmed in ((1, False), (0, True)):
            stub.write_text(f'#!/bin/bash\nexit {status}\n')
            stub.chmod(0o755)
            script = 'if :; then\n' + block + 'printf "%s\\n" "${steam_client[@]}"\n'
            env = dict(os.environ, HOME=str(self.root), steam_root='/steam',
                       PATH=str(self.root) + os.pathsep + os.environ['PATH'])
            result = subprocess.run(['bash', '-c', script], env=env, capture_output=True, text=True, timeout=30)
            self.assertEqual(0, result.returncode, result.stderr)
            lines = result.stdout.splitlines()
            self.assertEqual('/steam/steamrtarm64/steam', lines[-1])
            self.assertEqual(shimmed, 'Valve containerized the arm64 Steam client' in result.stdout)
            self.assertEqual(shimmed, any(line.startswith('STEAM_RUNTIME_STEAMRT=') and
                                          line.endswith('/.droiddeck-steamrt-shim') for line in lines))

    def test_both_clients_are_told_they_already_run_inside_the_container(self):
        guard = SESSION.index('export STEAM_STEAMRT_RECURSION_GUARD=1')
        self.assertLess(guard, SESSION.index('"${steam_client[@]}" "${_bl_ui[@]}"'))
        self.assertIn('-u STEAM_STEAMRT_RECURSION_GUARD', SESSION[:guard])
        self.assertIn('export STEAM_STEAMRT_RECURSION_GUARD=1', X64)
        self.assertIn('droiddeck-steamrt-shim "$STEAM_X64/steamrt64" "$X64_HOME/steamrt-shim" || rm -rf "$X64_HOME/steamrt-shim"', X64)


class SessionChannelTest(unittest.TestCase):
    BLOCK = re.search(r'\n( +steam_channel=steamdeck_publicbeta\n.*?\n +fi\n)', SESSION, re.S).group(1)

    def channel(self, x64, installer):
        with tempfile.TemporaryDirectory() as temp:
            stub = Path(temp) / 'droiddeck-steam-install'
            stub.write_text('#!/bin/bash\n' + installer + '\n')
            stub.chmod(0o755)
            env = dict(os.environ, steam_x64=x64, PATH=temp + os.pathsep + os.environ['PATH'])
            result = subprocess.run(['bash', '-c', self.BLOCK + 'echo "$steam_channel"\n'], env=env,
                                    capture_output=True, text=True, timeout=30)
        self.assertEqual(0, result.returncode, result.stderr)
        return result.stdout.strip()

    def test_the_arm64_client_follows_the_installed_channel(self):
        self.assertEqual('steamdeck_stable', self.channel('0', '[ "$1" = --channel ] && echo steamdeck_stable'))

    def test_an_installer_without_an_answer_keeps_the_deck_beta(self):
        self.assertEqual('steamdeck_publicbeta', self.channel('0', 'exit 1'))

    def test_the_x86_client_keeps_its_own_channel(self):
        self.assertEqual('steamdeck_publicbeta', self.channel('1', 'echo steamdeck_stable'))

    def test_the_client_branch_on_disk_and_clientbeta_use_the_same_channel(self):
        self.assertIn('-clientbeta "$steam_channel"', SESSION)
        self.assertIn('echo "$steam_channel" > "$steam_beta"', SESSION)


if __name__ == '__main__':
    unittest.main()
