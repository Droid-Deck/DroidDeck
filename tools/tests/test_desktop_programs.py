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
SHORTCUT_IDS = PROGRAMS["shortcut_ids"]
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

    def test_steam_shortcuts_it_cannot_read_leave_the_menu_as_it_was(self):
        if os.geteuid() == 0:
            self.skipTest("file permissions do not apply to root")
        self.install(self.prefix(NEXON))
        vdf = self.steam / "userdata/123/config/shortcuts.vdf"
        vdf.parent.mkdir(parents=True)
        vdf.write_bytes(b"\0shortcuts\0\0" + b"0\0\x02appid\0" + struct.pack("<i", NEXON - 2 ** 32)
                        + b"\x01Exe\0\"C:\\\\launcher.exe\"\0\x08\x08\x08")
        with mock.patch.dict(PROGRAMS, {"shortcut_ids": SHORTCUT_IDS}):
            PROGRAMS["main"]()
            self.assertEqual([], self.entries(self.apps))
            vdf.chmod(0)
            try:
                PROGRAMS["main"]()
            finally:
                vdf.chmod(0o644)
            self.assertEqual([], self.entries(self.apps))
            vdf.write_bytes(vdf.read_bytes()[:20])
            PROGRAMS["main"]()
            self.assertEqual([], self.entries(self.apps))
            vdf.unlink()
            PROGRAMS["main"]()
            self.assertEqual(1, len(self.entries(self.apps)))

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

    def test_a_link_to_a_program_outside_the_prefix_gets_no_entry(self):
        outside = self.tmp / "Downloads/tool.exe"
        outside.parent.mkdir(parents=True)
        outside.write_bytes(b"MZ")
        self.install(self.prefix(NEXON), name="Tool", target="Z:" + str(outside).replace("/", "\\"))
        PROGRAMS["main"]()
        self.assertEqual([], self.entries(self.apps))

    def test_windows_arguments_follow_windows_quoting(self):
        cases = {
            "--profile=O'Brien": ["--profile=O'Brien"],
            '-mode "full screen" C:\\save': ["-mode", "full screen", "C:\\save"],
            '"a\\"b" \\\\server\\share': ['a"b', "\\\\server\\share"],
            '"x\\\\" y': ["x\\", "y"],
            "  ": [],
        }
        for text, expected in cases.items():
            with self.subTest(text):
                self.assertEqual(expected, PROGRAMS["windows_args"](text))

    def test_an_apostrophe_in_a_links_arguments_does_not_stop_the_refresh(self):
        exe = self.install(self.prefix(NEXON), args="--profile=O'Brien")
        PROGRAMS["main"]()
        [name] = self.entries(self.apps)
        self.assertIn('Exec="%s" "%s" "--profile=O\'Brien"\n' % (PROGRAMS["OPEN"], exe), (self.apps / name).read_text())

    def test_a_file_or_folder_it_cannot_read_leaves_the_menu_and_desktop_alone(self):
        if os.geteuid() == 0:
            self.skipTest("file permissions do not apply to root")
        pfx = self.prefix(NEXON)
        self.install(pfx, target="C:\\PROGRAM FILES (X86)\\Nexon\\Nexon Launcher\\nexon_launcher.exe")
        PROGRAMS["main"]()
        menu, desktop = self.entries(self.apps), self.entries(self.desktop)
        placed = Path(PROGRAMS["PLACED"]).read_text()
        drive = pfx / "drive_c"
        unreadable = {
            "compatdata": (self.steam / "steamapps/compatdata", 0),
            "proton_shortcuts": (drive / "proton_shortcuts", 0),
            "windows desktop": (drive / "users/Public/Desktop", 0),
            "shortcut file": (drive / "proton_shortcuts/Nexon Launcher.desktop", 0),
            "link file": (drive / "ProgramData/Microsoft/Windows/Start Menu/Programs/Nexon Launcher.lnk", 0),
            "folder above a differently cased part": (drive, 0o311),
        }
        for name, (path, mode) in unreadable.items():
            with self.subTest(name):
                before = path.stat().st_mode
                path.chmod(mode)
                try:
                    PROGRAMS["main"]()
                finally:
                    path.chmod(before)
                self.assertEqual(menu, self.entries(self.apps))
                self.assertEqual(desktop, self.entries(self.desktop))
                self.assertEqual(placed, Path(PROGRAMS["PLACED"]).read_text())

    def test_an_unreadable_placement_record_does_not_bring_back_a_deleted_icon(self):
        if os.geteuid() == 0:
            self.skipTest("file permissions do not apply to root")
        self.install(self.prefix(NEXON))
        PROGRAMS["main"]()
        [icon] = self.entries(self.desktop)
        (self.desktop / icon).unlink()
        record = Path(PROGRAMS["PLACED"])
        placed = record.read_text()
        record.chmod(0)
        try:
            PROGRAMS["main"]()
        finally:
            record.chmod(0o644)
        self.assertEqual([], self.entries(self.desktop))
        self.assertEqual(placed, record.read_text())

    def test_icons_written_before_a_failed_write_are_recorded(self):
        pfx = self.prefix(NEXON)
        self.install(pfx)
        self.install(pfx, name="Tool", folder="Tool", exe="tool.exe")
        PROGRAMS["main"]()
        first, second = self.entries(self.apps)
        for name in (first, second):
            (self.desktop / name).unlink()
        Path(PROGRAMS["PLACED"]).write_text("")
        (self.desktop / second / "blocked").mkdir(parents=True)
        PROGRAMS["main"]()
        self.assertTrue((self.desktop / first).is_file())
        self.assertEqual(first + "\n", Path(PROGRAMS["PLACED"]).read_text())
        self.assertEqual([], list(self.desktop.glob("*.tmp")))
        (self.desktop / first).unlink()
        shutil.rmtree(self.desktop / second)
        PROGRAMS["main"]()
        self.assertEqual([second], self.entries(self.desktop))

    def test_an_unreadable_placement_record_leaves_the_menu_as_it_was(self):
        if os.geteuid() == 0:
            self.skipTest("file permissions do not apply to root")
        pfx = self.prefix(NEXON)
        self.install(pfx)
        PROGRAMS["main"]()
        menu = self.entries(self.apps)
        self.install(pfx, name="Tool", folder="Tool", exe="tool.exe")
        record = Path(PROGRAMS["PLACED"])
        record.chmod(0)
        try:
            PROGRAMS["main"]()
        finally:
            record.chmod(0o644)
        self.assertEqual(menu, self.entries(self.apps))

    def test_a_program_behind_a_folder_it_cannot_reach_keeps_its_entry(self):
        if os.geteuid() == 0:
            self.skipTest("file permissions do not apply to root")
        exe = self.install(self.prefix(NEXON))
        PROGRAMS["main"]()
        menu, desktop = self.entries(self.apps), self.entries(self.desktop)
        hidden = self.tmp / "locked/nexon_launcher.exe"
        hidden.parent.mkdir()
        hidden.write_bytes(b"MZ")
        exe.unlink()
        exe.symlink_to(hidden)
        hidden.parent.chmod(0)
        try:
            PROGRAMS["main"]()
        finally:
            hidden.parent.chmod(0o755)
        self.assertEqual(menu, self.entries(self.apps))
        self.assertEqual(desktop, self.entries(self.desktop))

    def test_without_any_prefixes_the_entries_go(self):
        self.install(self.prefix(NEXON))
        PROGRAMS["main"]()
        shutil.rmtree(self.steam / "steamapps/compatdata")
        PROGRAMS["main"]()
        self.assertEqual([], self.entries(self.apps))

    def test_refreshes_take_turns(self):
        calls = []
        with mock.patch.object(PROGRAMS["fcntl"], "flock", lambda lock, op: calls.append(op)), \
                mock.patch.dict(PROGRAMS, {"refresh": lambda: calls.append("refresh")}):
            PROGRAMS["main"]()
        self.assertEqual([PROGRAMS["fcntl"].LOCK_EX, "refresh"], calls)

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
