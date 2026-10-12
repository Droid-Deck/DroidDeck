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

# Red Dead Redemption 2's own script: Proton needs Khronos' vulkan-1.dll for it, which only the
# Vulkan runtime installer puts in the prefix.
VULKAN_SCRIPT = r'''"InstallScript"
{
	"Run Process"
	{
		"Vulkan111080"
		{
			"HasRunKey"		"HKEY_LOCAL_MACHINE\\Software\\Valve\\Steam\\Apps\\1174180"
			"process 1"		"%INSTALLDIR%\\Redistributables\\VulkanRT-1.1.108.0-Installer.exe"
			"command 1"		"/S"
			"NoCleanUp"		"0"
		}
		"RockstarLauncher"
		{
			"HasRunKey"		"HKEY_LOCAL_MACHINE\\Software\\Rockstar Games\\Steam\\Launcher"
			"process 1"		"%INSTALLDIR%\\Redistributables\\Rockstar-Games-Launcher.exe"
			"command 1"		"/s /t"
			"NoCleanUp"		"0"
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

    def test_an_evaluator_script_nested_deeper_is_read(self):
        script = self.tmp / "legacycompat/evaluatorscript_409710.vdf"
        script.parent.mkdir(parents=True)
        script.write_text('"evaluatorscript"\n{\n\t"1"\n' + "\n".join("\t" + line for line in GAME_SCRIPT.replace("VCRedist2022", "x64 14.99.1.0").splitlines()) + "\n}\n")
        env = dict(os.environ, DROIDDECK_LIBRARIES="", DROIDDECK_SEED_SCRIPTS=str(script))
        subprocess.run(["bash", str(SCRIPT), str(self.steam)], env=env, capture_output=True, text=True, check=True)
        self.assertIn('"x64 14.99.1.0"=dword:00000001', self.reg.read_text())

    def test_the_prefix_date_is_kept(self):
        os.utime(self.reg, (1_000_000_000, 1_000_000_000))
        self.run_seed()
        self.assertEqual(int(self.reg.stat().st_mtime), 1_000_000_000)

    def rdr2(self):
        path = self.steam / "steamapps/common/Red Dead Redemption 2/installscript.vdf"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(VULKAN_SCRIPT)
        (self.steam / "steamapps/appmanifest_1174180.acf").write_text('"AppState" { "installdir" "Red Dead Redemption 2" }')

    def test_a_vulkan_runtime_installer_is_left_to_run(self):
        self.rdr2()
        self.run_seed()
        reg = self.reg.read_text()
        self.assertNotIn("Vulkan111080", reg)
        self.assertIn('"RockstarLauncher"=dword:00000001', reg)
        self.assertNotIn("Vulkan111080", (self.reg.parent / ".droiddeck-redists").read_text())

    def test_a_vulkan_marker_an_earlier_seeding_wrote_comes_out_once(self):
        self.rdr2()
        marker = "HKEY_LOCAL_MACHINE\\\\Software\\\\Valve\\\\Steam\\\\Apps\\\\1174180\tVulkan111080"
        self.reg.write_text(
            "WINE REGISTRY Version 2\n"
            "\n[Software\\\\Valve\\\\Steam\\\\Apps\\\\1174180] 1791729070\n#time=1dd598d29161f72\n"
            '"Other"=dword:00000001\n"Vulkan111080"=dword:00000001\n'
            "\n[Software\\\\Wow6432Node\\\\Valve\\\\Steam\\\\Apps\\\\1174180] 1791729070\n"
            '"Vulkan111080"=dword:00000001\n'
            "\n[Software\\\\Valve\\\\Steam\\\\Apps\\\\22380] 1791311644\n"
            '"Vulkan111080"=dword:00000001\n')
        (self.reg.parent / ".droiddeck-redists").write_text(marker + "\n")
        os.utime(self.reg, (1_000_000_000, 1_000_000_000))
        self.run_seed()
        reg = self.reg.read_text()
        self.assertEqual(reg.count('"Vulkan111080"'), 1, "only the other app's value stays")
        self.assertIn('"Other"=dword:00000001', reg)
        self.assertNotIn("Vulkan111080", (self.reg.parent / ".droiddeck-redists").read_text())
        self.assertEqual(int(self.reg.stat().st_mtime), 1_000_000_000)
        # The client writes the marker itself once the installer has run; that one is left alone.
        with self.reg.open("a") as out:
            out.write('\n[Software\\\\Valve\\\\Steam\\\\Apps\\\\1174180] 1791730000\n"Vulkan111080"=dword:00000001\n')
        self.run_seed()
        self.assertEqual(self.reg.read_text().count('"Vulkan111080"'), 2)


if __name__ == "__main__":
    unittest.main()
