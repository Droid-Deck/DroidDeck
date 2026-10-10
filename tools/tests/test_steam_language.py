import json
from pathlib import Path
import runpy
import tempfile
import unittest


BIN = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin'
MODULE = runpy.run_path(str(BIN / 'droiddeck-steam-language'))
apply = MODULE['apply']
with_language = MODULE['with_language']
tokenize = MODULE['tokenize']
child = MODULE['child']
block_end = MODULE['block_end']
main = MODULE['main']


def value(text, *path):
    tokens = tokenize(text)
    start, end = 0, len(tokens)
    for name in path[:-1]:
        opening = child(tokens, start, end, name)
        if opening is None:
            return None
        start, end = opening + 1, block_end(tokens, opening)
    i = start
    while i < end:
        if i + 1 < end and tokens[i + 1] == '{':
            i = block_end(tokens, i + 1) + 1
            continue
        if tokens[i].lower() == '"%s"' % path[-1].lower():
            return tokens[i + 1].strip('"')
        i += 2
    return None


STEAM = ('Registry', 'HKCU', 'Software', 'Valve', 'Steam', 'language')
GLOBAL = ('Registry', 'HKCU', 'Software', 'Valve', 'Steamsteamglobal', 'language')

# As the client leaves it on a device (trimmed): both keys, other values beside them, and HKLM.
CLIENT = '''"Registry"
{
	"HKLM"
	{
		"Software"
		{
			"Valve"
			{
				"Steam"
				{
					"InstallPath"		"/root/.local/share/Steam"
				}
			}
		}
	}
	"HKCU"
	{
		"Software"
		{
			"Valve"
			{
				"Steam"
				{
					"language"		"english"
					"AutoLoginUser"		"someone"
					"Apps"
					{
						"language"		"not this one"
					}
				}
				"Steamsteamglobal"
				{
					"language"		"english"
				}
			}
		}
	}
}
'''


class SteamLanguageTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        (self.home / '.steam').mkdir()
        self.registry = self.home / '.steam/registry.vdf'

    def test_both_keys_change_and_everything_else_stays(self):
        text = with_language(CLIENT, 'japanese')
        self.assertEqual('japanese', value(text, *STEAM))
        self.assertEqual('japanese', value(text, *GLOBAL))
        self.assertEqual('someone', value(text, 'Registry', 'HKCU', 'Software', 'Valve', 'Steam', 'AutoLoginUser'))
        self.assertEqual('not this one', value(text, 'Registry', 'HKCU', 'Software', 'Valve', 'Steam', 'Apps', 'language'))
        self.assertEqual('/root/.local/share/Steam', value(text, 'Registry', 'HKLM', 'Software', 'Valve', 'Steam', 'InstallPath'))
        self.assertEqual(1, text.count('"HKCU"'))

    def test_a_first_run_gets_a_registry_the_client_reads(self):
        self.assertTrue(apply(str(self.home), 'koreana'))
        text = self.registry.read_text()
        self.assertTrue(text.startswith('"Registry"'))
        self.assertEqual('koreana', value(text, *STEAM))
        self.assertEqual('koreana', value(text, *GLOBAL))
        self.assertEqual({'language': 'koreana'}, json.loads((self.home / '.steam/exportedsettings.json').read_text()))

    def test_missing_keys_are_added_whatever_their_case(self):
        text = with_language('"registry"\n{\n\t"hkcu"\n\t{\n\t\t"software"\n\t\t{\n\t\t}\n\t}\n}\n', 'schinese')
        self.assertEqual('schinese', value(text, *STEAM))
        self.assertEqual('schinese', value(text, *GLOBAL))
        self.assertEqual(1, text.lower().count('"hkcu"'))
        self.assertEqual(1, text.lower().count('"software"'))

    def test_a_value_is_replaced_not_duplicated_whatever_its_key_case(self):
        text = with_language(CLIENT.replace('"language"\t\t"english"', '"Language"\t\t"english"'), 'latam')
        self.assertEqual(2, text.lower().count('"language"\t\t"latam"'))
        self.assertNotIn('english', text)

    def test_an_unchanged_language_writes_nothing(self):
        self.registry.write_text(CLIENT)
        apply(str(self.home), 'spanish')
        before = self.registry.stat().st_mtime_ns
        self.assertFalse(apply(str(self.home), 'spanish'))
        self.assertEqual(before, self.registry.stat().st_mtime_ns)

    def test_other_exported_settings_are_kept(self):
        (self.home / '.steam/exportedsettings.json').write_text('{"language": "english", "other": 1}')
        apply(str(self.home), 'tchinese')
        self.assertEqual({'language': 'tchinese', 'other': 1}, json.loads((self.home / '.steam/exportedsettings.json').read_text()))

    def test_only_the_clients_names_are_accepted(self):
        self.assertEqual(2, main(['x', str(self.home), 'korean']))
        self.assertEqual(2, main(['x', str(self.home), 'Spanish']))
        self.assertFalse(self.registry.exists())


if __name__ == '__main__':
    unittest.main()
