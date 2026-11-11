import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / "tools/linuxfs/overlay/usr/local/bin/droiddeck-pad-defaults"

SYSTEM_CFG = """# menu_show_core_updater = true
menu_driver = "xmb"
menu_show_core_updater = false
assets_directory = "/usr/share/libretro/assets/"
libretro_directory = "/usr/lib/aarch64-linux-gnu/libretro/"
libretro_info_path = "/usr/share/libretro/info/"
"""

SAVED_DEFAULTS = """assets_directory = "~/.config/retroarch/assets"
input_joypad_driver = "udev"
libretro_directory = "~/.config/retroarch/cores"
libretro_info_path = "~/.config/retroarch/cores"
menu_show_core_updater = "true"
"""


class RetroArchDefaults(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        system = self.tmp / "retroarch.system.cfg"
        system.write_text(SYSTEM_CFG)
        script = self.tmp / "pad-defaults.py"
        script.write_text(SCRIPT.read_text().replace('"/etc/retroarch.cfg"', repr(str(system))))
        self.script = script
        self.cfg = self.tmp / "retroarch/retroarch.cfg"

    def run_script(self):
        env = {"HOME": str(self.tmp), "XDG_CONFIG_HOME": str(self.tmp), "PATH": os.environ["PATH"]}
        subprocess.run([sys.executable, "-I", str(self.script)], env=env, check=True, capture_output=True)
        return dict(
            (k.strip(), v.strip().strip('"'))
            for k, _, v in (l.partition("=") for l in self.cfg.read_text().splitlines())
        )

    def test_new_config_uses_packaged_paths(self):
        values = self.run_script()
        self.assertEqual(values["assets_directory"], "/usr/share/libretro/assets/")
        self.assertEqual(values["libretro_directory"], "/usr/lib/aarch64-linux-gnu/libretro/")
        self.assertEqual(values["libretro_info_path"], "/usr/share/libretro/info/")
        self.assertEqual(values["menu_show_core_updater"], "false")
        self.assertEqual(values["input_joypad_driver"], "sdl2")
        self.assertNotIn("menu_driver", values)

    def test_saved_defaults_move_to_packaged_paths(self):
        self.cfg.parent.mkdir(parents=True)
        self.cfg.write_text(SAVED_DEFAULTS)
        values = self.run_script()
        self.assertEqual(values["assets_directory"], "/usr/share/libretro/assets/")
        self.assertEqual(values["libretro_info_path"], "/usr/share/libretro/info/")
        self.assertEqual(values["menu_show_core_updater"], "false")
        self.assertEqual(values["input_joypad_driver"], "sdl2")

    def test_user_choices_are_kept(self):
        self.cfg.parent.mkdir(parents=True)
        self.cfg.write_text(
            'assets_directory = "/mnt/assets"\nlibretro_directory = "~/.config/retroarch/cores"\n'
        )
        (self.tmp / "retroarch/cores").mkdir()
        (self.tmp / "retroarch/cores/snes9x_libretro.so").write_bytes(b"")
        values = self.run_script()
        self.assertEqual(values["assets_directory"], "/mnt/assets")
        self.assertEqual(values["libretro_directory"], "~/.config/retroarch/cores")

    def test_without_a_system_config_nothing_changes(self):
        (self.tmp / "retroarch.system.cfg").unlink()
        self.cfg.parent.mkdir(parents=True)
        self.cfg.write_text(SAVED_DEFAULTS.replace("udev", "sdl2"))
        before = self.cfg.read_text()
        self.run_script()
        self.assertEqual(self.cfg.read_text(), before)


if __name__ == "__main__":
    unittest.main()
