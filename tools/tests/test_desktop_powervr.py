import os
from pathlib import Path
import subprocess
import unittest


DESKTOP = (Path(__file__).resolve().parents[1] / 'linuxfs/desktop/droiddeck-desktop').read_text()
START = 'if [ "$(cat /usr/share/droiddeck/kwin-compositor 2>/dev/null)" = opengl ]; then'


class DesktopPowerVrTest(unittest.TestCase):
    def kopper(self, gl_present):
        block = DESKTOP[DESKTOP.index(START):]
        block = block[block.index('\n') + 1:block.index('\n  if [ -z "${KWIN_RENDER_NODES:-}" ]')]
        environment = {key: value for key, value in os.environ.items()
                       if key not in ('DD_STEAM_GL_PRESENT', 'LIBGL_KOPPER_DISABLE')}
        if gl_present is not None:
            environment['DD_STEAM_GL_PRESENT'] = gl_present
        result = subprocess.run(['bash', '-c', block + '\nsh -c \'echo "kopper:${LIBGL_KOPPER_DISABLE:-on}"\''],
                                env=environment, capture_output=True, text=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        return result.stdout.strip().splitlines()[-1]

    def test_system_powervr_desktop_turns_kopper_off(self):
        self.assertEqual('kopper:true', self.kopper('1'))

    def test_other_drivers_keep_kopper(self):
        self.assertEqual('kopper:on', self.kopper(None))
        self.assertEqual('kopper:on', self.kopper('0'))

    def test_kopper_is_set_before_plasma_starts(self):
        self.assertLess(DESKTOP.index('\n  [ "${DD_STEAM_GL_PRESENT:-}" = 1 ] && export LIBGL_KOPPER_DISABLE=true\n'),
                        DESKTOP.index('dbus-run-session -- startplasma-wayland'))


if __name__ == '__main__':
    unittest.main()
