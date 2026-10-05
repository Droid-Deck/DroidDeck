import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-seed-redists"

GAME_SCRIPT = r'''"InstallScript"
{
	"Run Process"
	{
		"VCRedist2022"
		{
			"HasRunKey"		"HKEY_LOCAL_MACHINE\\Software\\Valve\\Steam\\Apps\\CommonRedist\\vcredist\\2022"
			"process 1"		"%INSTALLDIR%\\_CommonRedist\\vcredist\\2022\\VC_redist.x64.exe"
			"command 1"		"/q /norestart"
			"NoCleanUp"		"1"
		}
	}
}
'''


class SeedRedistsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.steam = self.tmp / "Steam"
        self.reg = self.steam / "steamapps/compatdata/1234/pfx/system.reg"
        self.reg.parent.mkdir(parents=True)
        self.reg.write_text("WINE REGISTRY Version 2\n")

    def game(self, name="Some Game", script=GAME_SCRIPT, library=None):
        root = Path(library or self.steam)
        path = root / "steamapps/common" / name / "_CommonRedist/vcredist/2022/installscript.vdf"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(script)
        manifest = root / "steamapps" / ("appmanifest_%d.acf" % (abs(hash(name)) % 100000))
        manifest.write_text('"AppState" { "installdir" "%s" }' % name)
        return path

    def run_seed(self, libraries=""):
        env = dict(os.environ, DROIDDECK_LIBRARIES=libraries)
        return subprocess.run(["bash", str(SCRIPT), str(self.steam)], env=env, capture_output=True, text=True, check=True)

    def test_a_games_own_install_script_is_marked_as_run(self):
        self.game()
        self.run_seed()
        reg = self.reg.read_text()
        self.assertIn("[Software\\\\Valve\\\\Steam\\\\Apps\\\\CommonRedist\\\\vcredist\\\\2022]", reg)
        self.assertIn("[Software\\\\Wow6432Node\\\\Valve\\\\Steam\\\\Apps\\\\CommonRedist\\\\vcredist\\\\2022]", reg)
        self.assertIn('"VCRedist2022"=dword:00000001', reg)

    def test_games_in_other_libraries_are_read(self):
        sd = self.tmp / "sd"
        self.game(library=sd)
        self.run_seed(str(sd))
        self.assertIn('"VCRedist2022"=dword:00000001', self.reg.read_text())

    def test_the_vc_runtime_is_recorded_for_every_architecture_and_view(self):
        self.run_seed()
        reg = self.reg.read_text()
        for view in ("Software", "Software\\\\Wow6432Node"):
            for arch in ("x86", "x64", "arm64"):
                self.assertIn("[%s\\\\Microsoft\\\\VisualStudio\\\\14.0\\\\VC\\\\Runtimes\\\\%s]" % (view, arch), reg)
        self.assertIn('"Installed"=dword:00000001', reg)
        self.assertIn('"Major"=dword:0000000e', reg)
        self.assertIn('"Version"="v14.51.36247.00"', reg)

    def test_a_seeded_prefix_is_left_alone_until_a_game_changes(self):
        self.run_seed()
        once = self.reg.read_text()
        self.run_seed()
        self.assertEqual(once, self.reg.read_text())
        self.game()
        self.run_seed()
        self.assertIn('"VCRedist2022"=dword:00000001', self.reg.read_text())

    def test_the_prefix_date_is_kept(self):
        os.utime(self.reg, (1_000_000_000, 1_000_000_000))
        self.run_seed()
        self.assertEqual(int(self.reg.stat().st_mtime), 1_000_000_000)


if __name__ == "__main__":
    unittest.main()
