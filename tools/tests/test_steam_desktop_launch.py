"""Desktop Steam stays on KDE; explicit Big Picture still switches sessions."""
import fcntl
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest

BIN = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin'


class DesktopSteamLaunchTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.session = self.root / 'session'
        self.session.mkdir()
        self.env = dict(os.environ, HOME=str(self.root), XDG_DATA_HOME=str(self.root / 'data'),
                        XDG_CACHE_HOME=str(self.root / 'cache'), BL_DESKTOP='1',
                        BL_LAUNCH_DIR=str(self.session))
        supervisor = self.root / 'supervisor'
        supervisor.write_text('#!/bin/bash\nprintf "%s\\n" "$BL_INSIDE" "$BL_STEAM_UI" "$DISPLAY" "$@"\n')
        supervisor.chmod(0o755)
        self.launcher = self.root / 'launch'
        self.launcher.write_text((BIN / 'droiddeck-steam-launch').read_text().replace(
            '/usr/local/bin/droiddeck-session', str(supervisor)))

    def launch(self, *args):
        return subprocess.run(['bash', str(self.launcher), *args], env=self.env,
                              text=True, capture_output=True)

    def test_desktop_keeps_display_and_does_not_request_session_switch(self):
        self.env['DISPLAY'] = ':7'
        result = self.launch('desktop')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.splitlines(), ['1', 'desktop', ':7', 'steam', 'steam://open/library'])
        self.assertFalse((self.session / 'steam-launch').exists())

    def test_desktop_game_url_goes_to_client(self):
        result = self.launch('desktop', '501590')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.splitlines()[-1], 'steam://rungameid/501590')
        self.assertFalse((self.session / 'steam-launch').exists())

    def test_second_click_forwards_without_another_supervisor(self):
        lock = self.root / 'cache/droiddeck/steam-desktop.lock'
        lock.parent.mkdir(parents=True)
        steam = self.root / 'data/Steam/steamrtarm64/steam'
        steam.parent.mkdir(parents=True)
        steam.write_text('#!/bin/bash\nprintf "%s\\n" "$@"\n')
        steam.chmod(0o755)
        (self.root / '.steam').mkdir()
        os.mkfifo(self.root / '.steam/steam.pipe')
        (self.root / '.steam/steam.pid').write_text(str(os.getpid()))
        with lock.open('w') as handle:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            result = self.launch('desktop', 'steam://open/settings')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.splitlines(), ['-nobigpicture', '-no-cef-sandbox', '-cef-ozone-platform=x11',
                                                   '-cef-disable-gpu', 'steam://open/settings'])
        self.assertFalse((self.session / 'steam-launch').exists())

    def test_second_click_waits_for_steam_ipc_during_startup(self):
        lock = self.root / 'cache/droiddeck/steam-desktop.lock'
        lock.parent.mkdir(parents=True)
        steam = self.root / 'data/Steam/steamrtarm64/steam'
        steam.parent.mkdir(parents=True)
        marker = self.root / 'forwarded'
        steam.write_text(f'#!/bin/bash\ntouch "{marker}"\n')
        steam.chmod(0o755)
        with lock.open('w') as handle:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            process = subprocess.Popen(['bash', str(self.launcher), 'desktop'], env=self.env,
                                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            try:
                time.sleep(0.7)
                self.assertIsNone(process.poll())
                self.assertFalse(marker.exists())
                (self.root / '.steam').mkdir()
                (self.root / '.steam/steam.pid').write_text(str(os.getpid()))
                os.mkfifo(self.root / '.steam/steam.pipe')
                _, stderr = process.communicate(timeout=3)
                self.assertEqual(process.returncode, 0, stderr)
                self.assertTrue(marker.exists())
            finally:
                if process.poll() is None:
                    process.kill()
                    process.communicate()

    def test_big_picture_requests_explicit_handoff(self):
        result = self.launch('bigpicture')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.session / 'steam-launch').read_text(), 'ui=bigpicture\nurl=\n')

    def test_outside_desktop_preserves_session_request(self):
        self.env.pop('BL_DESKTOP')
        result = self.launch('desktop', 'steam://open/settings')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.session / 'steam-launch').read_text(),
                         'ui=desktop\nurl=steam://open/settings\n')

    def test_invalid_target_cannot_trigger_a_handoff(self):
        self.assertEqual(self.launch('desktop', 'bad').returncode, 64)
        self.assertFalse((self.session / 'steam-launch').exists())

    def test_cef_software_is_scoped_to_the_kde_client(self):
        source = (BIN / 'droiddeck-session').read_text()
        setup = source[source.index('    _bl_ui=(-gamepadui)'):source.index('    if [ "${BL_STEAM_UI:-}" != desktop ]; then')]
        for desktop, ui, software in [('1', 'desktop', True), ('1', 'bigpicture', False), ('', 'desktop', False)]:
            with self.subTest(desktop=desktop, ui=ui):
                env = dict(self.env, BL_DESKTOP=desktop, BL_STEAM_UI=ui)
                result = subprocess.check_output(['bash', '-c', setup + '\nprintf "%s\\n" "${_bl_cef[@]}"'],
                                                 env=env, text=True)
                self.assertEqual('-cef-disable-gpu' in result, software)
                self.assertEqual('-cef-use-angle=vulkan' in result, not software)
