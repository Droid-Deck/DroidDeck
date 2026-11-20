import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SESSION = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin/droiddeck-session'


class GameResolutionTest(unittest.TestCase):
    def launch(self, mode, **overrides):
        source = SESSION.read_text()
        start = source.index('  width=${DD_WIDTH:-1280}')
        end = source.index('\nfi\n\ndd_displays()', start)
        block = source[start:end].replace('/usr/local/bin/droiddeck-pipewire', 'pipewire_stub')
        with tempfile.TemporaryDirectory() as directory:
            env = dict(os.environ, XDG_RUNTIME_DIR=directory, DD_REFRESH='60', DD_MANGOAPP='0',
                       DD_WIDTH='2560', DD_HEIGHT='1600')
            for name in ('DD_GAME_WIDTH', 'DD_GAME_HEIGHT', 'DD_FOLLOW_OUTPUT', 'DD_GAMESCOPE_STRETCH_16X9'):
                env.pop(name, None)
            env.update(overrides)
            fake = Path(directory) / 'gamescope'
            fake.write_text('#!/bin/bash\nprintf "ARG:%s\\n" "$@"\n')
            fake.chmod(0o755)
            env['PATH'] = directory + os.pathsep + env['PATH']
            script = f'''
set -u
mode={mode}
pipewire_stub() {{ return 1; }}
run_gamescope() {{ printf 'ARG:%s\n' "$@"; }}
''' + block
            result = subprocess.run(['bash', '-c', script], env=env, capture_output=True, text=True, timeout=5)
            self.assertEqual(0, result.returncode, result.stderr)
            return [line.removeprefix('ARG:') for line in result.stdout.splitlines() if line.startswith('ARG:')]

    def test_a_game_started_without_steam_runs_at_its_resolution_with_proportions_kept(self):
        args = self.launch('run', DD_GAME_WIDTH='1280', DD_GAME_HEIGHT='720')
        self.assertEqual(['2560', '1600'], [args[args.index('-W') + 1], args[args.index('-H') + 1]])
        self.assertEqual(['1280', '720'], [args[args.index('-w') + 1], args[args.index('-h') + 1]])
        self.assertNotIn('-S', args)

    def test_without_a_game_resolution_the_game_matches_the_output(self):
        args = self.launch('run')
        self.assertEqual(['2560', '1600'], [args[args.index('-w') + 1], args[args.index('-h') + 1]])

    def test_steam_ignores_the_game_resolution(self):
        args = self.launch('steam', DD_GAME_WIDTH='1280', DD_GAME_HEIGHT='720')
        self.assertEqual(['2560', '1600'], [args[args.index('-w') + 1], args[args.index('-h') + 1]])


if __name__ == '__main__':
    unittest.main()
