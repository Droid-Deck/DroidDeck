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
        self.config_home = self.tmp
        self.cfg = self.config_home / "retroarch/retroarch.cfg"

    def run_script(self):
        env = {"HOME": str(self.tmp), "XDG_CONFIG_HOME": str(self.config_home), "PATH": os.environ["PATH"]}
        subprocess.run([sys.executable, "-I", str(self.script)], env=env, check=True, capture_output=True)
        values = {}
        for line in self.cfg.read_text().splitlines():
            key, sep, value = line.partition("=")
            if sep and not line.lstrip().startswith("#"):
                key = key.strip()
                self.assertNotIn(key, values, f"Duplicate setting: {key}")
                values[key] = value.strip().strip('"')
        return values

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
        self.assertEqual(values["libretro_directory"], "/usr/lib/aarch64-linux-gnu/libretro/")
        self.assertEqual(values["libretro_info_path"], "/usr/share/libretro/info/")
        self.assertEqual(values["menu_show_core_updater"], "true")
        self.assertEqual(values["input_joypad_driver"], "sdl2")

    def test_previous_seed_receives_missing_packaged_settings(self):
        self.cfg.parent.mkdir(parents=True)
        self.cfg.write_text('input_joypad_driver = "sdl2"\ninput_autodetect_enable = "true"\n')
        values = self.run_script()
        self.assertEqual(values["assets_directory"], "/usr/share/libretro/assets/")
        self.assertEqual(values["libretro_directory"], "/usr/lib/aarch64-linux-gnu/libretro/")
        self.assertEqual(values["libretro_info_path"], "/usr/share/libretro/info/")
        self.assertEqual(values["menu_show_core_updater"], "false")

    def test_previous_seed_keeps_downloaded_cores(self):
        self.cfg.parent.mkdir(parents=True)
        self.cfg.write_text('input_joypad_driver = "sdl2"\ninput_autodetect_enable = "true"\n')
        cores = self.tmp / "retroarch/cores"
        cores.mkdir()
        (cores / "snes9x_libretro.so").write_bytes(b"")
        values = self.run_script()
        self.assertNotIn("libretro_directory", values)

    def test_saved_home_core_directory_survives_a_different_xdg_config_home(self):
        self.config_home = self.tmp / "xdg"
        self.cfg = self.config_home / "retroarch/retroarch.cfg"
        self.cfg.parent.mkdir(parents=True)
        self.cfg.write_text(SAVED_DEFAULTS)
        cores = self.tmp / ".config/retroarch/cores"
        cores.mkdir(parents=True)
        (cores / "snes9x_libretro.so").write_bytes(b"")
        values = self.run_script()
        self.assertEqual(values["libretro_directory"], "~/.config/retroarch/cores")

    def test_empty_directory_settings_receive_packaged_paths(self):
        self.cfg.parent.mkdir(parents=True)
        self.cfg.write_text(
            'assets_directory = ""\n'
            'libretro_directory = ""\n'
            'libretro_info_path = ""\n'
        )
        values = self.run_script()
        self.assertEqual(values["assets_directory"], "/usr/share/libretro/assets/")
        self.assertEqual(values["libretro_directory"], "/usr/lib/aarch64-linux-gnu/libretro/")
        self.assertEqual(values["libretro_info_path"], "/usr/share/libretro/info/")

    def test_duplicate_empty_directory_settings_are_repaired(self):
        self.cfg.parent.mkdir(parents=True)
        paths = {
            "assets_directory": "/usr/share/libretro/assets/",
            "libretro_directory": "/usr/lib/aarch64-linux-gnu/libretro/",
            "libretro_info_path": "/usr/share/libretro/info/",
        }
        for empty_first in (True, False):
            with self.subTest(empty_first=empty_first):
                self.cfg.write_text("".join(
                    f'{key} = "{value}"\n'
                    for key, path in paths.items()
                    for value in (("", path) if empty_first else (path, ""))
                ))
                values = self.run_script()
                for key, path in paths.items():
                    self.assertEqual(values[key], path)
                before = self.cfg.read_bytes()
                self.run_script()
                self.assertEqual(self.cfg.read_bytes(), before)

    def test_duplicate_empty_settings_preserve_custom_paths(self):
        self.cfg.parent.mkdir(parents=True)
        paths = {
            "assets_directory": "/mnt/assets",
            "libretro_directory": "/mnt/cores",
            "libretro_info_path": "/mnt/info",
        }
        for empty_first in (True, False):
            with self.subTest(empty_first=empty_first):
                self.cfg.write_text("".join(
                    f'{key} = "{value}"\n'
                    for key, path in paths.items()
                    for value in (("", path) if empty_first else (path, ""))
                ) + 'menu_show_core_updater = "true"\nvideo_driver = "vulkan"\n')
                values = self.run_script()
                for key, path in paths.items():
                    self.assertEqual(values[key], path)
                self.assertEqual(values["menu_show_core_updater"], "true")
                self.assertEqual(values["video_driver"], "vulkan")

    def test_duplicate_empty_core_setting_preserves_installed_cores(self):
        self.cfg.parent.mkdir(parents=True)
        self.cfg.write_text(
            'libretro_directory = ""\n'
            'libretro_directory = "~/.config/retroarch/cores"\n'
        )
        cores = self.tmp / ".config/retroarch/cores"
        cores.mkdir(parents=True)
        core = cores / "snes9x_libretro.so"
        core.write_bytes(b"installed core")
        values = self.run_script()
        self.assertEqual(values["libretro_directory"], "~/.config/retroarch/cores")
        self.assertEqual(core.read_bytes(), b"installed core")

    def test_user_choices_are_kept(self):
        self.cfg.parent.mkdir(parents=True)
        self.cfg.write_text(
            'assets_directory = "/mnt/assets"\n'
            'libretro_directory = "~/.config/retroarch/cores"\n'
            'libretro_info_path = "/mnt/info"\n'
            'menu_show_core_updater = "true"\n'
        )
        (self.tmp / "retroarch/cores").mkdir()
        (self.tmp / "retroarch/cores/snes9x_libretro.so").write_bytes(b"")
        values = self.run_script()
        self.assertEqual(values["assets_directory"], "/mnt/assets")
        self.assertEqual(values["libretro_directory"], "~/.config/retroarch/cores")
        self.assertEqual(values["libretro_info_path"], "/mnt/info")
        self.assertEqual(values["menu_show_core_updater"], "true")

    def test_other_emulator_config_is_unchanged(self):
        config = self.tmp / "ppsspp/PSP/SYSTEM/controls.ini"
        config.parent.mkdir(parents=True)
        config.write_text("custom controls\n")
        self.run_script()
        self.assertEqual(config.read_text(), "custom controls\n")

    def test_without_a_system_config_nothing_changes(self):
        (self.tmp / "retroarch.system.cfg").unlink()
        self.cfg.parent.mkdir(parents=True)
        self.cfg.write_text(SAVED_DEFAULTS.replace("udev", "sdl2"))
        before = self.cfg.read_text()
        self.run_script()
        self.assertEqual(self.cfg.read_text(), before)


if __name__ == "__main__":
    unittest.main()
