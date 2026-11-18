import os
from pathlib import Path
import subprocess
import tempfile
import unittest


DESKTOP = (Path(__file__).resolve().parents[1] / 'linuxfs/desktop/droiddeck-desktop').read_text()
START = 'if [ "$(cat /usr/share/droiddeck/kwin-compositor 2>/dev/null)" = opengl ]; then'
LAUNCH = 'dbus-run-session -- startplasma-wayland'
STUBS = '''
pipewire() { return 0; }
dbus-run-session() { sh -c 'echo "kopper:${LIBGL_KOPPER_DISABLE:-on}"'; }
'''


class DesktopPowerVrTest(unittest.TestCase):
    def kopper_at_plasma_launch(self, gl_present):
        segment = DESKTOP[DESKTOP.index(START):DESKTOP.index(LAUNCH) + len(LAUNCH)]
        with tempfile.TemporaryDirectory() as run:
            segment = (segment.replace(START, 'if true; then')
                       .replace('/usr/local/bin/droiddeck-pipewire', 'pipewire')
                       .replace('/run/droiddeck', run))
            environment = {key: value for key, value in os.environ.items()
                           if key not in ('DD_STEAM_GL_PRESENT', 'LIBGL_KOPPER_DISABLE')}
            if gl_present is not None:
                environment['DD_STEAM_GL_PRESENT'] = gl_present
            result = subprocess.run(['bash', '-c', STUBS + segment], env=environment,
                                    capture_output=True, text=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        return result.stdout.strip().splitlines()[-1]

    def test_system_powervr_plasma_starts_with_kopper_off(self):
        self.assertEqual('kopper:true', self.kopper_at_plasma_launch('1'))

    def test_other_drivers_start_plasma_with_kopper(self):
        self.assertEqual('kopper:on', self.kopper_at_plasma_launch(None))
        self.assertEqual('kopper:on', self.kopper_at_plasma_launch('0'))


if __name__ == '__main__':
    unittest.main()
