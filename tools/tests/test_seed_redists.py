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
        self.user = self.reg.with_name("user.reg")
        self.user.write_text('WINE REGISTRY Version 2\n\n[Software\\\\Unrelated] 1\n"Keep"="untouched"\n')

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

    def test_current_user_and_machine_markers_go_to_their_own_registries(self):
        self.game(script=GAME_SCRIPT + GAME_SCRIPT.replace("HKEY_LOCAL_MACHINE", "HKEY_CURRENT_USER").replace("VCRedist2022", "UserRedist"))
        with self.reg.open("a") as out:
            out.write('\n[Software\\\\Unrelated] 1\n"Keep"="machine"\n')
        for reg in (self.reg, self.user):
            os.utime(reg, ns=(1_000_000_000_123456789, 1_000_000_000_123456789))
        self.run_seed()
        system, user = self.reg.read_text(), self.user.read_text()
        self.assertEqual(system.count('"VCRedist2022"=dword:00000001'), 2)
        self.assertNotIn("UserRedist", system)
        self.assertEqual(user.count('"UserRedist"=dword:00000001'), 1)
        self.assertNotIn("Wow6432Node", user)
        self.assertNotIn("VCRedist2022", user)
        self.assertIn('"Keep"="machine"', system)
        self.assertIn('"Keep"="untouched"', user)
        self.run_seed()
        self.assertEqual(system, self.reg.read_text())
        self.assertEqual(user, self.user.read_text())
        for reg in (self.reg, self.user):
            self.assertEqual(reg.stat().st_mtime_ns, 1_000_000_000_123456789)

    def test_a_user_marker_with_the_same_machine_path_and_value_is_separate(self):
        self.game(script=GAME_SCRIPT + GAME_SCRIPT.replace("HKEY_LOCAL_MACHINE", "HKEY_CURRENT_USER"))
        self.run_seed()
        system = self.reg.read_text()
        self.assertEqual(self.user.read_text().count('"VCRedist2022"=dword:00000001'), 1)
        self.user.write_text("WINE REGISTRY Version 2\n")
        self.run_seed()
        self.assertEqual(self.user.read_text().count('"VCRedist2022"=dword:00000001'), 1)
        self.assertEqual(system, self.reg.read_text())

    def test_a_legacy_user_ledger_entry_is_checked_in_user_reg(self):
        self.game(script=GAME_SCRIPT.replace("HKEY_LOCAL_MACHINE", "HKEY_CURRENT_USER"))
        self.run_seed()
        system = self.reg.read_bytes()
        self.user.write_text("WINE REGISTRY Version 2\n")
        (self.reg.parent / ".droiddeck-redists-checked").write_text(subprocess.check_output(["stat", "-c", "%i %s %.9Y", str(self.reg)], text=True))
        self.run_seed()
        self.assertEqual(self.user.read_text().count('"VCRedist2022"=dword:00000001'), 1)
        self.assertEqual(system, self.reg.read_bytes())

    def test_an_absent_user_registry_is_not_created_or_claimed(self):
        self.game(script=GAME_SCRIPT + GAME_SCRIPT.replace("HKEY_LOCAL_MACHINE", "HKEY_CURRENT_USER").replace("VCRedist2022", "UserRedist"))
        self.user.unlink()
        self.run_seed()
        self.assertFalse(self.user.exists())
        self.assertNotIn("UserRedist", (self.reg.parent / ".droiddeck-redists").read_text())
        self.assertEqual(self.reg.read_text().count('"VCRedist2022"=dword:00000001'), 2)
        system = self.reg.read_bytes()
        self.user.write_text("WINE REGISTRY Version 2\n")
        self.run_seed()
        self.assertIn('"UserRedist"=dword:00000001', self.user.read_text())
        self.run_seed()
        self.user.unlink()
        self.run_seed()
        self.assertFalse(self.user.exists())
        self.assertNotIn("UserRedist", (self.reg.parent / ".droiddeck-redists").read_text())
        self.assertEqual(system, self.reg.read_bytes())

    @unittest.skipIf(os.name == "posix" and os.geteuid() == 0, "root can write through read-only mode bits")
    def test_an_unwritable_user_registry_does_not_claim_a_missing_marker(self):
        self.game(script=GAME_SCRIPT + GAME_SCRIPT.replace("HKEY_LOCAL_MACHINE", "HKEY_CURRENT_USER").replace("VCRedist2022", "UserRedist"))
        ledger = self.reg.parent / ".droiddeck-redists"
        ledger.write_text("HKEY_CURRENT_USER\\\\Software\\\\Valve\\\\Steam\\\\Apps\\\\CommonRedist\\\\vcredist\\\\2022\tUserRedist\n")
        user = self.user.read_bytes()
        self.user.chmod(0o444)
        try:
            self.run_seed()
            self.assertEqual(user, self.user.read_bytes())
            self.assertEqual(self.user.stat().st_mode & 0o777, 0o444)
            self.assertNotIn("UserRedist", ledger.read_text())
            self.assertEqual(self.reg.read_text().count('"VCRedist2022"=dword:00000001'), 2)
        finally:
            self.user.chmod(0o644)

    def test_user_markers_in_a_default_prefix_and_another_library_are_seeded(self):
        default = self.steam / "steamapps/common/Proton Test/files/share/default_pfx"
        default.mkdir(parents=True)
        for name in ("system.reg", "user.reg"):
            (default / name).write_text("WINE REGISTRY Version 2\n")
        sd = self.tmp / "sd"
        self.game(library=sd, script=GAME_SCRIPT.replace("HKEY_LOCAL_MACHINE", "HKEY_CURRENT_USER"))
        self.run_seed(str(sd))
        self.assertIn('"VCRedist2022"=dword:00000001', (default / "user.reg").read_text())
        self.assertNotIn('"VCRedist2022"', (default / "system.reg").read_text())
        self.assertIn('"VCRedist2022"=dword:00000001', self.user.read_text())

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

    def test_markers_a_replaced_registry_lost_are_seeded_again(self):
        self.game()
        stock = self.reg.read_text()
        self.run_seed()
        ledger = (self.reg.parent / ".droiddeck-redists").read_text()
        self.assertIn("VCRedist2022", ledger)
        self.reg.write_text(stock)
        self.run_seed()
        reg = self.reg.read_text()
        self.assertEqual(reg.count('"VCRedist2022"=dword:00000001'), 2, "both views again")
        self.assertIn("[Software\\\\Microsoft\\\\VisualStudio\\\\14.0\\\\VC\\\\Runtimes\\\\x86]", reg)
        self.assertEqual(sorted((self.reg.parent / ".droiddeck-redists").read_text().splitlines()), sorted(ledger.splitlines()))
        self.run_seed()
        self.assertEqual(reg, self.reg.read_text())

    def test_a_marker_kept_in_only_one_view_is_seeded_again(self):
        self.game()
        self.run_seed()
        wow = "[Software\\\\Wow6432Node\\\\Valve\\\\Steam\\\\Apps\\\\CommonRedist\\\\vcredist\\\\2022]"
        blocks = self.reg.read_text().split("\n\n")
        self.reg.write_text("\n\n".join(b for b in blocks if not (b.startswith(wow) and '"VCRedist2022"' in b)))
        self.assertEqual(self.reg.read_text().count('"VCRedist2022"'), 1)
        self.run_seed()
        reg = self.reg.read_text()
        self.assertEqual(reg.count('"VCRedist2022"'), 3, "the one left plus both views again")
        self.assertTrue(any(b.startswith(wow) and '"VCRedist2022"' in b for b in reg.split("\n\n")))

    def test_a_vc_runtime_another_architecture_lost_is_seeded_again(self):
        self.run_seed()
        arm64 = "[Software\\\\Wow6432Node\\\\Microsoft\\\\VisualStudio\\\\14.0\\\\VC\\\\Runtimes\\\\arm64]"
        blocks = self.reg.read_text().split("\n\n")
        self.reg.write_text("\n\n".join(b for b in blocks if not b.startswith(arm64)))
        self.assertNotIn(arm64, self.reg.read_text())
        self.run_seed()
        reg = self.reg.read_text()
        self.assertIn(arm64, reg)
        self.assertEqual(reg.count("[Software\\\\Microsoft\\\\VisualStudio\\\\14.0\\\\VC\\\\Runtimes\\\\x86]"), 2)

    def test_a_marker_written_in_one_view_only_is_kept_as_it_is(self):
        self.game(script=GAME_SCRIPT.replace("Software\\\\Valve\\\\Steam\\\\Apps\\\\CommonRedist\\\\vcredist\\\\2022", "SOFTWARE\\\\AGEIA Technologies").replace("VCRedist2022", "PhysX Version"))
        self.run_seed()
        once = self.reg.read_text()
        self.assertEqual(once.count('"PhysX Version"=dword:00000001'), 1)
        result = self.run_seed()
        self.assertEqual(once, self.reg.read_text())
        self.assertNotIn("gone", result.stdout)

    def test_an_evaluator_script_nested_deeper_is_read(self):
        script = self.tmp / "legacycompat/evaluatorscript_409710.vdf"
        script.parent.mkdir(parents=True)
        script.write_text('"evaluatorscript"\n{\n\t"1"\n' + "\n".join("\t" + line for line in GAME_SCRIPT.replace("VCRedist2022", "x64 14.99.1.0").splitlines()) + "\n}\n")
        env = dict(os.environ, DROIDDECK_LIBRARIES="", DROIDDECK_SEED_SCRIPTS=str(script))
        subprocess.run(["bash", str(SCRIPT), str(self.steam)], env=env, capture_output=True, text=True, check=True)
        self.assertIn('"x64 14.99.1.0"=dword:00000001', self.reg.read_text())

    def test_an_unchanged_registry_is_not_read_again(self):
        self.game(script=GAME_SCRIPT + GAME_SCRIPT.replace("HKEY_LOCAL_MACHINE", "HKEY_CURRENT_USER").replace("VCRedist2022", "UserRedist"))
        self.run_seed()
        self.run_seed()
        shim = self.tmp / "bin"
        shim.mkdir()
        log = self.tmp / "awk.log"
        (shim / "awk").write_text('#!/bin/sh\nprintf "%%s\\n" "$*" >> "%s"\nexec %s "$@"\n' % (log, shutil.which("awk")))
        (shim / "awk").chmod(0o755)
        env = dict(os.environ, DROIDDECK_LIBRARIES="", PATH="%s:%s" % (shim, os.environ["PATH"]))
        once = self.reg.read_text()
        user = self.user.read_text()
        subprocess.run(["bash", str(SCRIPT), str(self.steam)], env=env, capture_output=True, text=True, check=True)
        self.assertEqual(once, self.reg.read_text())
        self.assertEqual(user, self.user.read_text())
        self.assertNotIn(str(self.reg), log.read_text() if log.exists() else "")
        self.assertNotIn(str(self.user), log.read_text() if log.exists() else "")
        replaced = self.user.with_name("user.reg.new")
        replaced.write_text(user.replace('"UserRedist"', '"OtherValue"'))
        os.utime(replaced, ns=(self.user.stat().st_atime_ns, self.user.stat().st_mtime_ns))
        self.assertEqual(replaced.stat().st_size, self.user.stat().st_size)
        os.replace(replaced, self.user)
        subprocess.run(["bash", str(SCRIPT), str(self.steam)], env=env, capture_output=True, text=True, check=True)
        self.assertEqual(self.user.read_text().count('"UserRedist"=dword:00000001'), 1)
        self.assertIn('"OtherValue"=dword:00000001', self.user.read_text())
        self.assertEqual(once, self.reg.read_text())
        replaced = self.reg.with_name("system.reg.new")
        replaced.write_text("WINE REGISTRY Version 2\n")
        os.replace(replaced, self.reg)
        subprocess.run(["bash", str(SCRIPT), str(self.steam)], env=env, capture_output=True, text=True, check=True)
        self.assertEqual(self.reg.read_text().count('"VCRedist2022"=dword:00000001'), 2)

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

    def test_a_current_user_vulkan_installer_is_left_to_run(self):
        self.game(script=VULKAN_SCRIPT.replace("HKEY_LOCAL_MACHINE", "HKEY_CURRENT_USER"))
        self.run_seed()
        self.assertIn('"RockstarLauncher"=dword:00000001', self.user.read_text())
        self.assertNotIn("Vulkan111080", self.user.read_text())
        self.assertNotIn("Vulkan111080", self.reg.read_text())
        self.assertNotIn("Vulkan111080", (self.reg.parent / ".droiddeck-redists").read_text())

    def test_a_current_user_vulkan_marker_comes_out_of_only_its_own_hive(self):
        self.game(script=VULKAN_SCRIPT.replace("HKEY_LOCAL_MACHINE", "HKEY_CURRENT_USER"))
        self.run_seed()
        system = self.reg.read_bytes() + b'\n[Software\\\\Valve\\\\Steam\\\\Apps\\\\1174180] 1\n"Vulkan111080"=dword:00000001\n'
        self.reg.write_bytes(system)
        with self.user.open("a") as out:
            out.write('\n[Software\\\\Valve\\\\Steam\\\\Apps\\\\1174180] 1\n"Other"="keep"\n"Vulkan111080"=dword:00000001\n'
                      '\n[Software\\\\Wow6432Node\\\\Valve\\\\Steam\\\\Apps\\\\1174180] 1\n"Vulkan111080"=dword:00000001\n'
                      '\n[Software\\\\Valve\\\\Steam\\\\Apps\\\\22380] 1\n"Vulkan111080"=dword:00000001\n')
        with (self.reg.parent / ".droiddeck-redists").open("a") as out:
            out.write("HKEY_CURRENT_USER\\\\Software\\\\Valve\\\\Steam\\\\Apps\\\\1174180\tVulkan111080\n")
        os.utime(self.user, ns=(1_000_000_000_123456789, 1_000_000_000_123456789))
        self.run_seed()
        user = self.user.read_text()
        self.assertEqual(user.count('"Vulkan111080"'), 2, "the other path and the unseeded user view stay")
        self.assertIn('"Other"="keep"', user)
        self.assertEqual(system, self.reg.read_bytes())
        self.assertNotIn("Vulkan111080", (self.reg.parent / ".droiddeck-redists").read_text())
        self.assertEqual(self.user.stat().st_mtime_ns, 1_000_000_000_123456789)
        with self.user.open("a") as out:
            out.write('\n[Software\\\\Valve\\\\Steam\\\\Apps\\\\1174180] 1\n"Vulkan111080"=dword:00000001\n')
        user = self.user.read_bytes()
        self.run_seed()
        self.assertEqual(user, self.user.read_bytes())

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
        with self.reg.open("a") as out:
            out.write('\n[Software\\\\Valve\\\\Steam\\\\Apps\\\\1174180] 1791730000\n"Vulkan111080"=dword:00000001\n')
        self.run_seed()
        self.assertEqual(self.reg.read_text().count('"Vulkan111080"'), 2)


if __name__ == "__main__":
    unittest.main()
