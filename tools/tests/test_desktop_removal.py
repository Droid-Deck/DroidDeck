import gzip
from pathlib import Path
import unittest


ASSETS = Path(__file__).resolve().parents[2] / 'app/src/main/assets/desktop-removal'


class DesktopRemovalInventoryTest(unittest.TestCase):
    def test_legacy_inventory_removes_the_shell_and_retains_gamescope_dependencies(self):
        records = gzip.decompress((ASSETS / 'lxqt-r1.tsv.gzip').read_bytes()).decode().splitlines()
        paths = {record.split('\t', 3)[3] for record in records}
        self.assertIn('usr/bin/lxqt-session', paths)
        self.assertIn('usr/bin/labwc', paths)
        self.assertIn('usr/local/bin/steamdeck-desktop', paths)
        # The runtime pacman database did not protect libseat on the Thor. Removing it
        # made gamescope and every Steam/native session exit 127 after desktop cleanup.
        self.assertNotIn('usr/lib/libseat.so.1', paths)
        self.assertNotIn('usr/lib/libseat.so', paths)
        self.assertFalse(any(p.startswith('usr/lib/libSDL2') for p in paths))
        self.assertFalse(any(p.startswith(('root/', 'opt/', 'var/')) for p in paths))


if __name__ == '__main__':
    unittest.main()
