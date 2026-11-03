from pathlib import Path
import unittest

from tools.playstore.payloads import load_manifest, validate_play_engines


ROOT = Path(__file__).resolve().parents[2]


class PlayManifestTest(unittest.TestCase):
    def setUp(self):
        self.manifest = load_manifest(ROOT / 'tools/playstore/components.lock.json')
        self.entries = {url: entry for entry in self.manifest['entries'] for url in entry['urls']}

    def test_release_inventory_satisfies_engine_and_delivery_restrictions(self):
        validate_play_engines(self.manifest)
        self.assertTrue(any(entry.get('delivery') == 'windows-guest' for entry in self.manifest['entries']))
        for entry in self.manifest['entries']:
            if entry.get('delivery') == 'windows-guest':
                self.assertTrue(entry['id'].startswith('windows-'))
                self.assertTrue(all(feature.startswith('windows:') for feature in entry['provides']))

    def test_every_advertised_native_release_asset_is_available_with_the_same_identity(self):
        def visit(value):
            if isinstance(value, dict):
                if 'browser_download_url' in value:
                    entry = self.entries[value['browser_download_url']]
                    self.assertEqual(value['size'], entry['size'])
                    if value.get('digest'):
                        self.assertEqual(value['digest'], 'sha256:' + entry['sha256'])
                for item in value.values():
                    visit(item)
            elif isinstance(value, list):
                for item in value:
                    visit(item)
        for entry in self.manifest['entries']:
            if 'content' in entry:
                visit(entry['content'])

    def test_catalog_endpoints_used_by_android_and_the_guest_are_present(self):
        repositories = ['The412Banner/Banners-Turnip', 'WinNative-Emu/Drivers', 'Droid-Deck/Drivers-CI']
        urls = ['https://api.github.com/repos/' + repo + '/releases?per_page=15' for repo in repositories]
        urls += ['https://api.github.com/repos/Droid-Deck/DroidDeck-Proton/releases?per_page=15',
                 'https://api.github.com/repos/Droid-Deck/DroidDeck-Proton/releases',
                 'https://api.github.com/repos/SteamDeckHomebrew/decky-loader/releases?per_page=30']
        for url in urls:
            self.assertIsInstance(self.entries[url]['content'], list)
        proton = self.entries[urls[-3]]['content']
        self.assertTrue(any(asset['browser_download_url'].startswith('droiddeck-bundle://')
                            for release in proton for asset in release['assets']))


if __name__ == '__main__':
    unittest.main()
