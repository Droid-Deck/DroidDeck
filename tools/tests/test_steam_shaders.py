from pathlib import Path
import runpy
import tempfile
import unittest


BIN = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin'
MODULE = runpy.run_path(str(BIN / 'droiddeck-steam-shaders'))
apply = MODULE['apply']
set_text = MODULE['set_text']
parse = runpy.run_path(str(BIN / 'droiddeck-steam-ui-scale'))['parse']

THOR = '''"InstallConfigStore"
{
\t"Software"
\t{
\t\t"Valve"
\t\t{
\t\t\t"Steam"
\t\t\t{
\t\t\t\t"ShaderCacheManager"
\t\t\t\t{
\t\t\t\t\t"HasCurrentBucket"\t\t"1"
\t\t\t\t\t"ProcessingQueue"\t\t"207140;848350;"
\t\t\t\t\t"App"
\t\t\t\t\t{
\t\t\t\t\t}
\t\t\t\t}
\t\t\t}
\t\t}
\t}
}
'''


def disabled(text):
    steam = parse(text).get('InstallConfigStore').get('Software').get('Valve').get('Steam')
    return steam.get('ShaderCacheManager').get('DisableShaderCache')


class SteamShadersTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.config = self.root / 'config/config.vdf'
        self.config.parent.mkdir()

    def test_off_adds_the_key_the_client_writes(self):
        text, changed = set_text(THOR, False)
        self.assertTrue(changed)
        self.assertEqual('1', disabled(text))
        self.assertIn('\t\t\t\t\t"DisableShaderCache"\t\t"1"\n\t\t\t\t}', text)
        self.assertIn('"ProcessingQueue"\t\t"207140;848350;"', text)

    def test_existing_value_is_replaced_in_place(self):
        off, _ = set_text(THOR, False)
        on, changed = set_text(off, True)
        self.assertTrue(changed)
        self.assertEqual('0', disabled(on))
        self.assertEqual(off.replace('"DisableShaderCache"\t\t"1"', '"DisableShaderCache"\t\t"0"'), on)

    def test_matching_value_leaves_the_file_alone(self):
        off, _ = set_text(THOR, False)
        self.assertEqual((off, False), set_text(off, False))

    def test_missing_sections_are_created(self):
        text, changed = set_text('"InstallConfigStore"\n{\n}\n', False)
        self.assertTrue(changed)
        self.assertEqual('1', disabled(text))

    def test_apply_writes_config_and_creates_a_fresh_one(self):
        self.config.write_text(THOR)
        self.assertTrue(apply(self.root, False))
        self.assertEqual('1', disabled(self.config.read_text()))
        self.assertFalse(apply(self.root, False))
        fresh = Path(self.temp.name) / 'fresh'
        fresh.mkdir()
        self.assertTrue(apply(fresh, True))
        self.assertEqual('0', disabled((fresh / 'config/config.vdf').read_text()))

    def test_invalid_config_is_rejected(self):
        with self.assertRaises(ValueError):
            set_text('"InstallConfigStore" {', False)


if __name__ == '__main__':
    unittest.main()
