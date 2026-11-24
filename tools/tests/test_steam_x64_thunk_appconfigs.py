import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin/droiddeck-steam-x64'
SOURCE = SCRIPT.read_text()
FUNCTION = re.search(r'^x64_thunk_appconfigs\(\) \{\n.*?^\}\n', SOURCE, re.M | re.S).group()
CFG = '{"ThunksDB": {"GL": 1, "Vulkan": 1}}'


class SteamX64ThunkAppConfigsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.src = root / 'global'
        self.dst = root / 'local'
        self.src.mkdir()

    def write_global(self, name, data):
        (self.src / name).write_text(json.dumps(data))

    def run_fn(self, on):
        shell = FUNCTION + 'x64_thunk_appconfigs "$SRC" "$DST" "$CFG" "$ON"\n'
        env = dict(os.environ, SRC=str(self.src), DST=str(self.dst), CFG=CFG, ON=on)
        result = subprocess.run(['bash', '-eu', '-c', shell], env=env, capture_output=True, text=True, timeout=20)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_program_with_a_disabled_thunk_gets_the_session_thunks(self):
        self.write_global('anyprogram.json', {'Comment': 'x', 'ThunksDB': {'GL': 0}})
        self.run_fn('1')
        self.assertEqual(json.loads(CFG), json.loads((self.dst / 'anyprogram.json').read_text()))

    def test_disabled_vulkan_counts_too(self):
        self.write_global('other.json', {'ThunksDB': {'Vulkan': 0}})
        self.run_fn('1')
        self.assertTrue((self.dst / 'other.json').exists())

    def test_programs_without_a_thunk_opt_out_are_untouched(self):
        self.write_global('client.json', {'Config': {'HideHypervisorBit': '1'}})
        self.write_global('enabled.json', {'ThunksDB': {'GL': 1}})
        self.write_global('unrelated.json', {'ThunksDB': {'asound': 0}})
        self.run_fn('1')
        self.assertFalse(self.dst.exists())

    def test_user_override_is_kept(self):
        self.write_global('anyprogram.json', {'ThunksDB': {'GL': 0}})
        self.dst.mkdir()
        user = {'ThunksDB': {'GL': 0}, 'Config': {'Multiblock': '0'}}
        (self.dst / 'anyprogram.json').write_text(json.dumps(user))
        self.run_fn('1')
        self.assertEqual(user, json.loads((self.dst / 'anyprogram.json').read_text()))
        self.run_fn('0')
        self.assertEqual(user, json.loads((self.dst / 'anyprogram.json').read_text()))

    def test_software_mode_removes_only_the_written_file(self):
        self.write_global('anyprogram.json', {'ThunksDB': {'GL': 0}})
        self.run_fn('1')
        self.run_fn('0')
        self.assertFalse((self.dst / 'anyprogram.json').exists())

    def test_software_mode_writes_nothing(self):
        self.write_global('anyprogram.json', {'ThunksDB': {'GL': 0}})
        self.run_fn('0')
        self.assertFalse(self.dst.exists())

    def test_broken_global_configs_do_not_fail(self):
        (self.src / 'broken.json').write_text('{')
        self.write_global('list.json', [1, 2])
        self.run_fn('1')
        self.assertFalse(self.dst.exists())

    def test_missing_global_dir_does_not_fail(self):
        self.src = self.src / 'missing'
        self.run_fn('1')
        self.assertFalse(self.dst.exists())

    def test_launcher_applies_it_to_the_fex_app_config_dirs(self):
        self.assertIn('x64_thunk_appconfigs /usr/local/share/fex-emu/AppConfig "$X64_HOME/.config/fex-emu/AppConfig" "$thunk_cfg" "$client_thunks"', SOURCE)
        self.assertIn('''printf '%s\\n' "$thunk_cfg" > "$X64_HOME/thunks.json"''', SOURCE)


if __name__ == '__main__':
    unittest.main()
