import os
from pathlib import Path
import runpy
import subprocess
import sys
import tempfile
import unittest

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
SCRIPT = BIN / "bannerlator-steam-compat"
COMPAT = runpy.run_path(str(SCRIPT))
TOOL = COMPAT["TOOL"]

# What the client leaves after installing Dead Cells under its own Proton 11.0 (ARM64), with a
# title the user moved to an adopted third-party Proton beside it.
CONFIG_VDF = """"InstallConfigStore"
{
	"Software"
	{
		"Valve"
		{
			"Steam"
			{
				"CompatToolMapping"
				{
					"0"
					{
						"name"		"%s"
						"config"		""
						"priority"		"75"
					}
					"588650"
					{
						"name"		"proton_11_arm64"
						"config"		""
						"priority"		"250"
					}
					"1145360"
					{
						"name"		"GE-Proton10-1"
						"config"		""
						"priority"		"250"
					}
				}
			}
		}
	}
}
""" % TOOL


def mapping(config):
    tokens = COMPAT["tokenize"](config.read_text())
    block = COMPAT["find_block"](tokens, ["InstallConfigStore", "Software", "Valve", "Steam", "CompatToolMapping"])
    names, app = {}, None
    for i in range(block + 1, COMPAT["block_end"](tokens, block)):
        if tokens[i + 1] == "{":
            app = tokens[i].strip('"')
        elif tokens[i] == '"name"':
            names[app] = tokens[i + 1].strip('"')
    return names


class RegisterDefaultTest(unittest.TestCase):
    def test_reports_the_titles_it_repointed_or_added(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = Path(tmp, "config.vdf")
            config.write_text(CONFIG_VDF)
            changed = COMPAT["register_default"](str(config), ["588650", "1145360", "620"], protect=["GE-Proton10-1"])
            self.assertEqual(changed, {"588650", "620"})
            self.assertEqual(mapping(config), {"0": TOOL, "588650": TOOL, "1145360": "GE-Proton10-1", "620": TOOL})
            # The session's beat runs it again on an unchanged file: nothing more to restart for.
            self.assertEqual(COMPAT["register_default"](str(config), ["588650", "1145360", "620"], protect=["GE-Proton10-1"]), set())

    def test_the_default_alone_is_not_a_title(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = Path(tmp, "config.vdf")
            config.write_text(CONFIG_VDF.replace('"name"\t\t"%s"' % TOOL, '"name"\t\t"proton_experimental"', 1))
            changed = COMPAT["register_default"](str(config), ["588650"], protect=["GE-Proton10-1"])
            self.assertEqual(changed, {"588650"})
            self.assertEqual(mapping(config)["0"], TOOL)


class LiveRunTest(unittest.TestCase):
    def run_compat(self, home, *args):
        steam = Path(home, "Steam")
        result = subprocess.run([sys.executable, str(SCRIPT), *args, str(steam)],
                                env={"PATH": os.defpath, "HOME": home}, text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        return Path(home, COMPAT["RESTART_MARKER"])

    def steam(self, home):
        steam = Path(home, "Steam")
        (steam / "config").mkdir(parents=True)
        (steam / "config/config.vdf").write_text(CONFIG_VDF)
        (steam / "steamapps").mkdir()
        (steam / "steamapps/appmanifest_588650.acf").write_text("")
        return steam

    def test_live_run_asks_for_a_restart_when_a_title_changed(self):
        with tempfile.TemporaryDirectory() as home:
            self.steam(home)
            marker = self.run_compat(home, "--live")
            self.assertTrue(marker.exists())
            marker.unlink()
            # Already repointed: a later beat leaves the client alone.
            self.assertFalse(self.run_compat(home, "--live").exists())

    def test_run_before_the_client_starts_never_asks(self):
        with tempfile.TemporaryDirectory() as home:
            steam = self.steam(home)
            self.assertFalse(self.run_compat(home).exists())
            self.assertEqual(mapping(steam / "config/config.vdf")["588650"], TOOL)


if __name__ == "__main__":
    unittest.main()
