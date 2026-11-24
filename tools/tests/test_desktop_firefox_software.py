import os
from pathlib import Path
import subprocess
import unittest


DESKTOP = (Path(__file__).resolve().parents[1] / 'linuxfs/desktop/droiddeck-desktop').read_text()
START = 'if [ "$(cat /usr/share/droiddeck/kwin-compositor 2>/dev/null)" = opengl ]; then'
LINE = '\n  [ "${DD_STEAM_GL_PRESENT:-}" = 1 ] && export MOZ_ACCELERATED=0\n'


class DesktopFirefoxSoftwareTest(unittest.TestCase):
    def firefox_environment(self, gl_present):
        head = DESKTOP[:DESKTOP.index('\n', DESKTOP.index('export MOZ_ENABLE_WAYLAND=1'))]
        head = head[head.rindex('\n') + 1:]
        block = DESKTOP[DESKTOP.index(START):]
        block = block[block.index('\n') + 1:block.index('\n  if [ -z "${KWIN_RENDER_NODES:-}" ]')]
        environment = {key: value for key, value in os.environ.items()
                       if key not in ('DD_STEAM_GL_PRESENT', 'MOZ_ENABLE_WAYLAND', 'MOZ_ACCELERATED')}
        if gl_present is not None:
            environment['DD_STEAM_GL_PRESENT'] = gl_present
        script = head + '\n' + block + '\nsh -c \'echo "wayland:${MOZ_ENABLE_WAYLAND:-unset} accelerated:${MOZ_ACCELERATED:-unset}"\''
        result = subprocess.run(['bash', '-c', script], env=environment, capture_output=True,
                                text=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        return result.stdout.strip().splitlines()[-1]

    def test_system_powervr_desktop_runs_firefox_on_wayland_with_software_rendering(self):
        self.assertEqual('wayland:1 accelerated:0', self.firefox_environment('1'))

    def test_other_drivers_keep_firefox_hardware_rendering(self):
        self.assertEqual('wayland:1 accelerated:unset', self.firefox_environment(None))
        self.assertEqual('wayland:1 accelerated:unset', self.firefox_environment('0'))

    def test_override_is_set_before_plasma(self):
        self.assertLess(DESKTOP.index(LINE), DESKTOP.index('dbus-run-session -- startplasma-wayland'))


if __name__ == '__main__':
    unittest.main()
