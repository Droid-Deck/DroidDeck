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


def state_source():
    source = SCRIPT.read_text()
    start = source.index('        declare -A _dd_deaf=()')
    return source[start:source.index('        while kill -0', start)]


STATE = state_source()


class ClientZramFocusTest(unittest.TestCase):
    def test_the_hidden_epic_launcher_is_compressed_with_the_client(self):
        with tempfile.TemporaryDirectory() as tmp:
            proc = Path(tmp)

            def task(pid, argv, env=()):
                folder = proc / str(pid)
                folder.mkdir()
                (folder / 'cmdline').write_bytes(b''.join(arg.encode() + b'\0' for arg in argv))
                (folder / 'environ').write_bytes(b''.join(item.encode() + b'\0' for item in env))

            fex = '/s/FEX-Emu/usr/bin/FEX'
            task(5000010, ['/s/steamrtarm64/steam', '-gamepadui'])
            task(5000011, [fex, '/e/usr/bin/EpicGamesLauncher'], ['HOME=/root', 'DROIDDECK_EPIC_HIDDEN=1'])
            task(5000012, [fex, './/lib/webkit2gtk-4.1/WebKitWebProcess', '4'], ['DROIDDECK_EPIC_HIDDEN=1', 'LANG=C'])
            task(5000013, [fex, '/e/usr/bin/EpicGamesLauncher'], ['HOME=/root'])
            task(5000014, ['/e/WebKitWebProcess', '4'], ['DROIDDECK_EPIC_HIDDEN=1'])
            task(5000015, [fex, '/e/usr/bin/EpicGamesLauncher'], ['DROIDDECK_EPIC_HIDDEN=10'])
            task(5000016, ['/s/reaper', 'SteamLaunch', 'AppId=413150', '--', 'game'])
            source = function_source('dd_zram_scan').replace('/proc/[0-9]*', tmp + '/[0-9]*')
            result = run_bash(source + '\ndd_zram_scan\necho "${_dd_pids[*]}|${_dd_others[*]}|$_dd_game|$_dd_apps"')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('', result.stderr)
        self.assertEqual('5000010 5000011 5000012|5000013 5000014 5000015|1| 413150', result.stdout.strip())

    def scan_fake(self, tasks):
        with tempfile.TemporaryDirectory() as tmp:
            for pid, argv in tasks:
                self.write_cmd(Path(tmp), pid, argv)
            source = function_source('dd_zram_scan').replace('/proc/[0-9]*', tmp + '/[0-9]*')
            return run_bash(source + '\ndd_zram_scan\necho "${_dd_pids[*]}|${_dd_others[*]}|$_dd_game"')

    def test_battlenet_is_compressed_with_the_client_while_one_of_its_games_runs(self):
        launcher = [
            (6000010, ['/s/steamrtarm64/steam', '-gamepadui']),
            (6000011, ['/s/reaper', 'SteamLaunch', 'AppId=2899116412', '--', 'droiddeck-battlenet']),
            (6000012, ['C:\\Program Files (x86)\\Battle.net\\Battle.net.exe', '--exec=launch S1']),
            (6000013, ['C:\\Program Files (x86)\\Battle.net\\Battle.net.15511\\Battle.net Helper.exe', '--type=renderer']),
            (6000014, ['C:\\ProgramData\\Battle.net\\Agent\\Agent.9324\\Agent.exe', '--internalclienttools']),
            (6000015, ['C:\\windows\\system32\\services.exe']),
            (6000016, ['C:\\windows\\system32\\winedevice.exe', '-uid']),
        ]
        game = [(6000017, ['S:\\Games\\StarCraft\\x86\\StarCraft.exe', '-launch', '-uid', 's1'])]
        before = self.scan_fake(launcher)
        during = self.scan_fake(launcher + game)
        for result in (before, during):
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual('', result.stderr)
        self.assertEqual('6000010|6000015 6000016 6000012 6000013 6000014|1', before.stdout.strip())
        self.assertEqual('6000010 6000012 6000013 6000014|6000015 6000016 6000017|1', during.stdout.strip())

    def test_a_game_without_a_battlenet_uid_leaves_battlenet_alone(self):
        result = self.scan_fake([
            (6000020, ['C:\\Program Files (x86)\\Battle.net\\Battle.net.exe']),
            (6000021, ['D:\\Games\\Other\\Game.exe', '-windowed']),
            (6000022, ['/usr/bin/wine', 'game.exe', '-uid']),
        ])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('|6000021 6000022 6000020|0', result.stdout.strip())

    TICK = '''
dd_zram_scan() { _dd_game=$game _dd_pids=(10) _dd_others=() _dd_apps=" 413150"; }
dd_game_focused() { return $focus; }
dd_memory_read() { _dd_available=$((available * 1024)) _dd_mem_total=$((7168 * 1024)) _dd_floor=$((896 * 1024)); _dd_target=$((target * 1024)) _dd_swap_room=$((4096 * 1024)) _dd_saving=500 _dd_rate=0 _dd_psi=0; }
dd_zram_trim() { echo "trim:$NOW"; _dd_trimmed=$((64 * 1024)); }
dd_zram_usage() { _dd_swap=$((64 * 1024)); }
dd_session_others() { :; }
step() { :; }
kill() { echo "back:$NOW"; }
game=1 focus=0 available=1500 target=1600
run() { for NOW in $(seq "$1" "$2"); do dd_memory_tick; done; }
'''

    def tick(self, body):
        functions = '\n'.join(function_source(name) for name in (
            'dd_memory_need', 'dd_memory_relieve', 'dd_memory_note', 'dd_client_readback', 'dd_memory_tick'))
        with tempfile.TemporaryDirectory() as tmp:
            functions = functions.replace('/tmp/.dd-zram', tmp + '/ctl')
            result = run_bash(('NOW=0\nset -u\n' + STATE + functions + self.TICK + body).replace('SECONDS', 'NOW'))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('', result.stderr)
        return result.stdout.splitlines()

    def test_the_client_behind_a_game_is_trimmed_while_memory_is_short_of_the_target(self):
        self.assertEqual(['trim:0', 'trim:1', 'trim:2'], self.tick('run 0 2\navailable=1700; run 3 9\n'))

    def test_game_exit_reads_the_client_back_after_a_quiet_half_minute(self):
        self.assertEqual(['trim:0', 'back:30'], self.tick('run 0 0\ngame=0 available=4000; run 1 40\n'))

    def test_client_in_front_of_a_running_game_is_read_back_and_trimmed_again_later(self):
        self.assertEqual(['trim:0', 'back:30', 'trim:60'],
                         self.tick('run 0 0\nfocus=1 available=4000; run 1 44\nfocus=0 available=1500; run 45 60\n'))

    def test_a_short_look_at_the_client_keeps_it_compressed(self):
        self.assertEqual(['trim:0'], self.tick('run 0 0\navailable=4000; run 1 34\nfocus=1; run 35 38\nfocus=0; run 39 60\n'))

    def test_unknown_focus_keeps_the_client_compressed(self):
        self.assertEqual(['trim:0'], self.tick('run 0 0\nfocus=2 available=4000; run 1 60\n'))

    def test_client_in_front_waits_for_memory(self):
        self.assertEqual(['trim:0', 'back:50'], self.tick('run 0 0\nfocus=1 available=1800 target=896; run 1 49\navailable=2500; run 50 60\n'))

    def test_a_client_that_was_never_trimmed_reads_nothing_back(self):
        self.assertEqual([], self.tick('focus=1 available=4000; run 0 60\n'))

    def test_scan_keeps_the_running_game_and_ignores_steams_own_reaper(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        self.write_cmd(root, 10, ['/usr/lib/steam/reaper', 'SteamLaunch', 'AppId=769', '--'])
        self.write_cmd(root, 20, ['/usr/bin/FEX', '/usr/lib/steam/reaper', 'SteamLaunch', 'AppId=1245620', '--'])
        self.write_cmd(root, 30, ['/home/deck/.local/share/Steam/steamrtarm64/steam'])
        self.write_cmd(root, 40, ['/usr/bin/wine', 'game.exe'])
        self.write_cmd(root, 50, ['/usr/bin/FEXInterpreter', '/usr/bin/steamwebhelper'])
        scan = function_source('dd_zram_scan').replace('for _d in /proc/[0-9]*', 'for _d in "$FAKE"/[0-9]*')
        result = run_bash('FAKE=%s\n%s\ndd_zram_scan\necho "game=$_dd_game"\necho "apps=${_dd_apps# }"\necho "pids=${_dd_pids[*]}"\necho "others=${_dd_others[*]}"' % (root, scan))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['game=1', 'apps=1245620', 'pids=30 50', 'others=40'], result.stdout.splitlines())

    def test_focus_must_be_the_running_game(self):
        focus = function_source('dd_game_focused')
        script = '''
%s
check() {
  DD_DESKTOP=$1 DD_LAUNCH_DIR=$2 _dd_apps=$3
  dd_game_focused
  echo $?
}
check "" "$dir" " 413150"
check "" "$dir" " 1245620"
printf '%%s\\n' '{"focusedApp": 999}' > "$dir/agent/focus.json"
check "" "$dir" " 413150"
printf '%%s\\n' '{"focusedApp": 769}' > "$dir/agent/focus.json"
check "" "$dir" " 413150"
printf '%%s\\n' '{"t": 2}' > "$dir/agent/focus.json"
check "" "$dir" " 413150"
rm -f "$dir/agent/focus.json"
check "" "$dir" " 413150"
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
        self.assertEqual(['0', '1', '1', '1', '2', '2', '0', '0'], result.stdout.splitlines())

    @staticmethod
    def write_cmd(root, pid, args):
        proc = root / str(pid)
        proc.mkdir()
        (proc / 'cmdline').write_bytes(b'\0'.join(arg.encode() for arg in args) + b'\0')


if __name__ == '__main__':
    unittest.main()
