"""Steam owns game caps; the nested compositor follows the output refresh rate."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SESSION = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin/droiddeck-session'


class SessionPacingTest(unittest.TestCase):
    def launch(self, refresh, **overrides):
        source = SESSION.read_text()
        start = source.index('  width=${BL_WIDTH:-1280}')
        end = source.index('\nfi\n\nbl_displays()', start)
        block = source[start:end].replace('/usr/local/bin/droiddeck-pipewire', 'pipewire_stub')
        with tempfile.TemporaryDirectory() as directory:
            env = dict(os.environ, XDG_RUNTIME_DIR=directory, BL_REFRESH=str(refresh),
                       BL_MANGOAPP='0', BL_FPS='0', DROIDDECK_GAME_FRAME_LIMIT='0')
            env.update(overrides)
            script = '''
set -u
mode=steam
pipewire_stub() { return 1; }
run_gamescope() { printf 'ARG:%s\\n' "$@"; }
''' + block
            result = subprocess.run(['bash', '-c', script], env=env, capture_output=True,
                                    text=True, timeout=5)
            self.assertEqual(0, result.returncode, result.stderr)
            return [line.removeprefix('ARG:') for line in result.stdout.splitlines()
                    if line.startswith('ARG:')]

    def test_display_refresh_sets_the_nested_compositor_rate(self):
        for refresh in (60, 90, 120):
            with self.subTest(refresh=refresh):
                args = self.launch(refresh)
                self.assertEqual(str(refresh), args[args.index('-r') + 1])
                self.assertIn('-e', args)

    def test_retired_cap_variables_do_not_override_the_display_rate(self):
        for performance in ('0', '1'):
            with self.subTest(performance=performance):
                args = self.launch(120, BL_FPS='30', DROIDDECK_GAME_FRAME_LIMIT=performance)
                self.assertEqual('120', args[args.index('-r') + 1])
                self.assertNotIn('--framerate-limit', args)

    def test_retired_cap_cannot_set_the_rate_when_the_display_rate_is_unknown(self):
        args = self.launch(0, BL_FPS='30', DROIDDECK_GAME_FRAME_LIMIT='1')
        self.assertNotIn('-r', args)
        self.assertNotIn('--framerate-limit', args)


if __name__ == '__main__':
    unittest.main()
