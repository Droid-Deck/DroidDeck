from pathlib import Path
import subprocess
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin/droiddeck-session'


class ClientZramFocusTest(unittest.TestCase):
    def test_refocus_restarts_the_delay_and_game_exit_restores_a_packed_client(self):
        source = SCRIPT.read_text()
        start = source.index('          if [ "$_bl_game" = 1 ] && bl_game_focused; then')
        stop = source.index('          nap 5', start)
        body = source[start:stop]
        harness = '''
_bl_since=-1 _bl_packed=-1
_bl_pids=(123)
bl_game_focused() { [ "$focused" = 1 ]; }
bl_zram_send() { echo "send:$1"; }
tick() {
  SECONDS=$1 _bl_game=$2 focused=$3
''' + body + '''
}
tick 0 1 1
tick 5 1 0
tick 30 1 1
echo "refocus:$_bl_packed"
tick 49 1 1
tick 50 1 1
tick 55 1 0
tick 60 0 0
'''
        result = subprocess.run(['bash', '-c', harness], capture_output=True, text=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['refocus:-1', 'send:1', 'send:0'], result.stdout.splitlines())


if __name__ == '__main__':
    unittest.main()
