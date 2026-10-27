from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin/droiddeck-session'


def function_source(name):
    source = SCRIPT.read_text()
    start = source.index('%s() {' % name)
    end = source.index('\n        }', start)
    return source[start:end + len('\n        }')]


def run_bash(script, env=None):
    return subprocess.run(['bash', '-c', script], capture_output=True, text=True, timeout=10, env=env)


class ClientZramFocusTest(unittest.TestCase):
    def test_refocus_restarts_the_delay_and_game_exit_restores_a_packed_client(self):
        source = SCRIPT.read_text()
        start = source.index('          if [ "$_bl_game" = 1 ] && bl_game_focused; then')
        stop = source.index('          nap 5', start)
        body = source[start:stop]
        harness = '''
_bl_since=-1 _bl_since_apps= _bl_packed=-1 _bl_apps=" 413150"
_bl_pids=(123)
bl_game_focused() { [ "$focused" = 1 ]; }
bl_over_cap() { return 1; }
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
        result = run_bash(harness)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('', result.stderr)
        self.assertEqual(['refocus:-1', 'send:1', 'send:0'], result.stdout.splitlines())

    def monitor_harness(self):
        source = SCRIPT.read_text()
        start = source.index('          bl_zram_scan\n          if bl_cap_due; then')
        stop = source.index('          nap 5', start)
        return '''
_bl_since=-1 _bl_since_apps= _bl_packed=-1 _bl_measured=-1 _bl_unpack=0 _bl_capped=-1 _bl_backoff=30
_bl_apps=" 413150"
_bl_pids=(123)
step() { :; }
bl_zram_scan() { :; }
bl_game_focused() { [ "$focused" = 1 ]; }
bl_cap_due() { [ "$cap_due" = 1 ]; }
bl_over_cap() { [ "$over_cap" = 1 ]; }
bl_zram_send() { echo "send:$1:$3"; }
tick() {
  SECONDS=$1 _bl_game=$2 focused=$3 over_cap=$4 cap_due=$5
''' + source[start:stop] + '\n}\n'

    def test_client_toggle_still_disables_automatic_compression(self):
        result = run_bash(self.monitor_harness() + '''
BL_ZRAM_CLIENT=0
tick 0 1 1 0 0
tick 25 1 1 0 0
tick 30 0 0 0 0
''')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('', result.stderr)
        self.assertEqual('', result.stdout)

    def test_a_new_game_restarts_the_compression_delay(self):
        result = run_bash(self.monitor_harness() + '''
tick 0 1 1 0 0
_bl_apps=" 413150 1245620"
tick 15 1 1 0 0
tick 20 1 1 0 0
echo "starting:$_bl_packed"
tick 34 1 1 0 0
tick 35 1 1 0 0
''')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('', result.stderr)
        self.assertEqual(['starting:-1', 'send:1:client'], result.stdout.splitlines())

    def test_ram_cap_can_still_compress_an_unfocused_client(self):
        result = run_bash(self.monitor_harness() + '''
BL_ZRAM_CLIENT=0 BL_RAM_CAP_MB=1024
bl_zram_usage() { _bl_rss=$((768 * 1024)); }
tick 0 1 0 1 1
echo "cap:$_bl_packed:$_bl_since:$_bl_backoff"
tick 5 0 0 0 0
''')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('', result.stderr)
        self.assertEqual(['send:1:client', 'send:1:session', 'cap:0:-1:60', 'send:0:client'], result.stdout.splitlines())

    def test_game_exit_defers_restore_until_the_session_is_under_the_ram_cap(self):
        result = run_bash(self.monitor_harness() + '''
tick 0 1 1 0 0
tick 20 1 1 0 0
tick 25 1 0 0 0
tick 30 0 0 1 0
echo "deferred:$_bl_unpack:$_bl_packed"
tick 35 0 0 0 1
echo "restored:$_bl_unpack"
''')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('', result.stderr)
        self.assertEqual(['send:1:client', 'deferred:1:-1', 'send:0:client', 'restored:0'], result.stdout.splitlines())

    def test_scan_keeps_the_running_game_and_ignores_steams_own_reaper(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        self.write_cmd(root, 10, ['/usr/lib/steam/reaper', 'SteamLaunch', 'AppId=769', '--'])
        self.write_cmd(root, 20, ['/usr/bin/FEX', '/usr/lib/steam/reaper', 'SteamLaunch', 'AppId=1245620', '--'])
        self.write_cmd(root, 30, ['/home/deck/.local/share/Steam/steamrtarm64/steam'])
        self.write_cmd(root, 40, ['/usr/bin/wine', 'game.exe'])
        self.write_cmd(root, 50, ['/usr/bin/FEXInterpreter', '/usr/bin/steamwebhelper'])
        scan = function_source('bl_zram_scan').replace('for _d in /proc/[0-9]*', 'for _d in "$FAKE"/[0-9]*')
        result = run_bash('FAKE=%s\n%s\nbl_zram_scan\necho "game=$_bl_game"\necho "apps=${_bl_apps# }"\necho "pids=${_bl_pids[*]}"\necho "others=${_bl_others[*]}"' % (root, scan))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['game=1', 'apps=1245620', 'pids=30 50', 'others=40'], result.stdout.splitlines())

    def test_focus_must_be_the_running_game(self):
        focus = function_source('bl_game_focused')
        script = '''
%s
check() {
  BL_DESKTOP=$1 BL_LAUNCH_DIR=$2 _bl_apps=$3
  if bl_game_focused; then echo yes; else echo no; fi
}
check "" "$dir" " 413150"
check "" "$dir" " 1245620"
printf '%%s\\n' '{"focusedApp": 999}' > "$dir/agent/focus.json"
check "" "$dir" " 413150"
printf '%%s\\n' '{"focusedApp": 769}' > "$dir/agent/focus.json"
check "" "$dir" " 413150"
rm -f "$dir/agent/focus.json"
check 1 "$dir" " 413150"
check "" "" " 413150"
''' % focus
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        launch = Path(temp.name)
        (launch / 'agent').mkdir()
        (launch / 'agent' / 'focus.json').write_text('{"t": 1, "focusedApp": 413150}\n')
        result = run_bash('dir=%s\n%s' % (launch, script))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['yes', 'no', 'no', 'no', 'yes', 'yes'], result.stdout.splitlines())

    @staticmethod
    def write_cmd(root, pid, args):
        proc = root / str(pid)
        proc.mkdir()
        (proc / 'cmdline').write_bytes(b'\0'.join(arg.encode() for arg in args) + b'\0')


if __name__ == '__main__':
    unittest.main()
