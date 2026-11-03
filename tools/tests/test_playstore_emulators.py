import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin/droiddeck-emulators'


class PlayEmulatorsTest(unittest.TestCase):
    def test_guest_play_install_cannot_reach_external_package_managers(self):
        with tempfile.TemporaryDirectory() as directory:
            marker = Path(directory) / 'package-manager-called'
            environment = dict(os.environ, SCRIPT=str(SCRIPT), MARKER=str(marker))
            result = subprocess.run(['bash', '-c', '''
compgen() { return 0; }
droiddeck-root() { printf called > "$MARKER"; return 1; }
flatpak() { printf called > "$MARKER"; return 1; }
export -f compgen droiddeck-root flatpak
exec bash "$SCRIPT" install
'''], env=environment, capture_output=True, text=True)
            self.assertEqual(2, result.returncode)
            self.assertFalse(marker.exists())


if __name__ == '__main__':
    unittest.main()
