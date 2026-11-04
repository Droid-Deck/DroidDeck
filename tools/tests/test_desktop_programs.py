import contextlib
import os
from pathlib import Path
import runpy
import shutil
import stat
import struct
import sys
import tempfile
import unittest
from unittest import mock

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
PROGRAMS = runpy.run_path(str(BIN / "droiddeck-desktop-programs"))["main"].__globals__
OPEN = runpy.run_path(str(BIN / "droiddeck-open"))["main"].__globals__

NEXON = 3908901178


def lnk(target, args="", wide_info=False):
    flags = 0x2 | 0x80 | (0x20 if args else 0)
    header = bytearray(76)
    struct.pack_into("<I", header, 0, 76)
    struct.pack_into("<I", header, 0x14, flags)
    local = target.encode("cp1252") + b"\0"
    if wide_info:
        wide = target.encode("utf-16-le") + b"\0\0"
        size = 36 + len(local) + len(wide) + 2
        info = struct.pack("<9I", size, 36, 1, 0, 36, 0, 36 + len(local) - 1, 36 + len(local), size - 2) + local + wide + b"\0\0"
    else:
        info = struct.pack("<7I", 28 + len(local), 28, 1, 0, 28, 0, 28 + len(local) - 1) + local
    strings = b""
    if args:
        strings = struct.pack("<H", len(args) + 1) + (args + "\0").encode("utf-16-le")
    return bytes(header) + info + strings


class ProgramsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.home = self.tmp / "home"
        self.steam = self.home / ".local/share/Steam"
        self.apps = self.home / ".local/share/applications"
        self.desktop = self.home / "Desktop"
        self.shortcuts = set()
        patch = mock.patch.dict(PROGRAMS, {
            "STEAM_ROOT": str(self.steam), "APPS_DIR": str(self.apps),
            "PLACED": str(self.home / ".local/share/droiddeck/desktop-programs-placed"),
            "desktop_dir": lambda: str(self.desktop), "shortcut_ids": lambda root: self.shortcuts,
            "print": lambda *a: None,
        })
        patch.start()
        self.addCleanup(patch.stop)

    def prefix(self, appid):
        pfx = self.steam / "steamapps/compatdata" / str(appid) / "pfx"
        (pfx / "drive_c").mkdir(parents=True)
        (pfx / "dosdevices").mkdir()
        os.symlink("../drive_c", pfx / "dosdevices/c:")
        os.symlink("/", pfx / "dosdevices/z:")
        return pfx

    def install(self, pfx, name="Nexon Launcher", folder="Program Files (x86)/Nexon/Nexon Launcher",
                exe="nexon_launcher.exe", on_desktop=True, target=None, args=""):
        program = pfx / "drive_c" / folder / exe
        program.parent.mkdir(parents=True, exist_ok=True)
        program.write_bytes(b"MZ")
        windows = target or "C:\\" + folder.replace("/", "\\") + "\\" + exe
        menu = "ProgramData\\Microsoft\\Windows\\Start Menu\\Programs\\" + name + ".lnk"
        link = pfx / "drive_c" / menu.replace("\\", "/")
        link.parent.mkdir(parents=True, exist_ok=True)
        link.write_bytes(lnk(windows, args))
        shortcuts = pfx / "drive_c/proton_shortcuts"
        for size in ("16x16", "256x256"):
            icon = shortcuts / "icons" / size / "apps/6D6F_logo.0.png"
            icon.parent.mkdir(parents=True, exist_ok=True)
            icon.write_bytes(b"png")
        (shortcuts / (name + ".desktop")).write_text(
            "[Desktop Entry]\nName=%s\nExec=\"%s\"\nType=Application\nStartupNotify=true\nIcon=6D6F_logo.0\n"
            "StartupWMClass=%s\n" % (name, ("C:\\" + menu).replace("\\", "\\\\\\\\"), exe))
        if on_desktop:
            desktop = pfx / "drive_c/users/Public/Desktop" / (name + ".lnk")
            desktop.parent.mkdir(parents=True, exist_ok=True)
            desktop.write_bytes(lnk(windows, args))
        return program

    def entries(self, folder):
        return sorted(p.name for p in folder.glob(PROGRAMS["PREFIX"] + "*.desktop")) if folder.is_dir() else []

    def test_an_installed_program_gets_a_menu_entry_that_opens_it_in_its_prefix(self):
        pfx = self.prefix(NEXON)
        exe = self.install(pfx)
        PROGRAMS["main"]()
        [name] = self.entries(self.apps)
        self.assertTrue(name.startswith("droiddeck-program-%d-" % NEXON))
        text = (self.apps / name).read_text()
        self.assertIn("Name=Nexon Launcher\n", text)
        self.assertIn('Exec="%s" "%s"\n' % (PROGRAMS["OPEN"], exe), text)
        self.assertIn("Icon=%s\n" % (pfx / "drive_c/proton_shortcuts/icons/256x256/apps/6D6F_logo.0.png"), text)
        self.assertIn("StartupWMClass=nexon_launcher.exe\n", text)

    def test_it_lands_on_the_desktop_once_when_its_installer_put_it_there(self):
        pfx = self.prefix(NEXON)
        self.install(pfx)
        self.install(pfx, name="Tool", folder="Tool", exe="tool.exe", on_desktop=False)
        PROGRAMS["main"]()
        self.assertEqual(2, len(self.entries(self.apps)))
        [name] = self.entries(self.desktop)
        self.assertEqual((self.apps / name).read_text(), (self.desktop / name).read_text())
        self.assertTrue((self.desktop / name).stat().st_mode & stat.S_IXUSR)
        (self.desktop / name).unlink()
        PROGRAMS["main"]()
        self.assertEqual([], self.entries(self.desktop))
        self.assertEqual(2, len(self.entries(self.apps)))

    def test_an_uninstalled_program_leaves_the_menu_and_the_desktop(self):
        exe = self.install(self.prefix(NEXON))
        PROGRAMS["main"]()
        exe.unlink()
        PROGRAMS["main"]()
        self.assertEqual([], self.entries(self.apps))
        self.assertEqual([], self.entries(self.desktop))
        self.assertEqual("", Path(PROGRAMS["PLACED"]).read_text())

    def test_steam_games_and_shortcuts_keep_their_own_entries(self):
        self.install(self.prefix(1238080))
        self.install(self.prefix(2243774769))
        self.shortcuts = {2243774769}
        PROGRAMS["main"]()
        self.assertEqual([], self.entries(self.apps))

    def test_uninstallers_and_links_to_other_files_get_no_entry(self):
        pfx = self.prefix(NEXON)
        self.install(pfx, name="Uninstall", exe="unins000.exe")
        self.install(pfx, name="Read me", exe="readme.txt")
        PROGRAMS["main"]()
        self.assertEqual([], self.entries(self.apps))

    def test_a_link_in_other_letter_case_with_arguments_still_starts_the_program(self):
        pfx = self.prefix(NEXON)
        exe = self.install(pfx, target="C:\\PROGRAM FILES (X86)\\NEXON\\nexon launcher\\NEXON_LAUNCHER.EXE",
                           args='-mode "full screen" C:\\save')
        PROGRAMS["main"]()
        [name] = self.entries(self.apps)
        self.assertIn('Exec="%s" "%s" "-mode" "full screen" "C:\\\\\\\\save"\n' % (PROGRAMS["OPEN"], exe),
                      (self.apps / name).read_text())

    def test_a_link_with_a_unicode_path_block_is_read_too(self):
        path = self.tmp / "game.lnk"
        path.write_bytes(lnk("C:\\Games\\Ünïcode\\game.exe", wide_info=True))
        self.assertEqual(("C:\\Games\\Ünïcode\\game.exe", ""), PROGRAMS["link_target"](str(path)))


class OpenTest(unittest.TestCase):
    def run_program(self, name, data):
        folder = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, folder, True)
        program = folder / name
        program.write_bytes(data)
        ran = []
        with mock.patch.dict(OPEN, {"steam_title": lambda path: None, "placed": lambda cmd, *a: cmd,
                                    "say": lambda *a: None, "notify": lambda *a: None, "game_folder": lambda path: None,
                                    "for_x86": lambda path: False}), \
                mock.patch.object(OPEN["subprocess"], "call", lambda cmd: ran.append(cmd) or 0), contextlib.chdir(folder):
            self.assertEqual(0, OPEN["main"]([str(program)]))
        return ran

    def test_a_windows_run_brings_the_menu_of_installed_programs_up_to_date(self):
        ran = self.run_program("NexonLauncherSetup.exe", b"MZ" + bytes(62))
        self.assertEqual([sys.executable, OPEN["PROGRAMS"]], ran[-1])
        self.assertEqual(2, len(ran))

    def test_other_programs_leave_the_menu_alone(self):
        ran = self.run_program("start.sh", b"echo hi\n")
        self.assertEqual(1, len(ran))


if __name__ == "__main__":
    unittest.main()
