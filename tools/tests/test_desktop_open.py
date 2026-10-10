import os
from pathlib import Path
import runpy
import shutil
import struct
import tempfile
import threading
import time
import unittest
from unittest import mock
import zlib

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
OPEN = runpy.run_path(str(BIN / "droiddeck-open"))["main"].__globals__
RUN = runpy.run_path(str(BIN / "droiddeck-proton-run"))["main"].__globals__


def elf(machine, extra=b""):
    head = bytearray(64)
    head[0:4], head[4], head[5], head[18] = b"\x7fELF", 2, 1, machine
    head[8:8 + len(extra)] = extra
    return bytes(head)


class Temp(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def write(self, relative, data, mode=0o644):
        path = self.tmp / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data if isinstance(data, bytes) else data.encode())
        path.chmod(mode)
        return path


class KindTest(Temp):
    def test_each_file_is_told_by_its_content_then_its_name(self):
        cases = {
            "game.x86_64": (elf(62), "elf"),
            "Tool.AppImage": (elf(183, b"AI\x02"), "appimage"),
            "renamed": (elf(62, b"AI\x02"), "appimage"),
            "setup.exe": (b"MZ" + bytes(62), "windows"),
            "launcher.bat": (b"@echo off\r\n", "windows"),
            "no-extension": (b"MZ" + bytes(62), "windows"),
            "d3d11.dll": (b"MZ" + bytes(62), None),
            "start.sh": (b"echo hi\n", "script"),
            "run": (b"#!/usr/bin/env python3\n", "script"),
            "notes.txt": (b"hello\n", None),
        }
        for name, (data, expected) in cases.items():
            with self.subTest(name):
                self.assertEqual(OPEN["kind"](str(self.write(name, data))), expected)

    def test_a_script_names_its_interpreter_or_gets_bash(self):
        self.assertEqual(OPEN["interpreter"](str(self.write("a", b"#!/usr/bin/env  python3 -u\r\nx\n"))),
                         ["/usr/bin/env", "python3", "-u"])
        self.assertEqual(OPEN["interpreter"](str(self.write("b", b"echo\n"))), ["/bin/bash"])


class PlacementTest(Temp):
    def setUp(self):
        super().setUp()
        self.gpu = self.write("droiddeck-gpu", b"#!/bin/sh\n", 0o755)
        patcher = mock.patch.dict(OPEN, {"GPU": str(self.gpu), "unattended": lambda env: False})
        patcher.start()
        self.addCleanup(patcher.stop)
        self.desktop = {"BL_DESKTOP": "1"}

    def placed(self, what, mode, env=None):
        return OPEN["placed"](["cmd"], what, mode, self.desktop if env is None else env)

    def test_programs_from_the_desktop_go_to_the_gpu(self):
        for what in ("windows", "elf", "appimage", "x86 script", "folder"):
            with self.subTest(what):
                self.assertEqual(self.placed(what, "run"), [str(self.gpu), "cmd"])

    def test_a_plain_script_and_a_window_stay_on_the_desktop(self):
        self.assertEqual(self.placed("script", "run"), ["cmd"])
        self.assertEqual(self.placed("windows", "window"), ["cmd"])

    def test_away_from_the_desktop_nothing_moves(self):
        self.assertEqual(self.placed("windows", "run", {}), ["cmd"])
        self.assertEqual(self.placed("windows", "run", dict(self.desktop, GAMESCOPE_WAYLAND_DISPLAY="x")), ["cmd"])

    def test_konsole_takes_what_asks_for_a_terminal(self):
        with mock.patch.object(OPEN["shutil"], "which", return_value="/usr/bin/konsole"):
            self.assertEqual(self.placed("script", "terminal"), ["konsole", "--hold", "-e", "cmd"])
            self.assertEqual(self.placed("windows", "terminal"), [str(self.gpu), "cmd"])

    def test_an_exec_stays_where_its_output_is_read(self):
        self.assertEqual(self.placed("elf", "exec"), ["cmd"])
        self.assertEqual(self.placed("windows", "exec"), [str(self.gpu), "cmd"])
        with mock.patch.dict(OPEN, {"unattended": lambda env: True}):
            self.assertEqual(self.placed("elf", "exec"), [str(self.gpu), "cmd"])


class UnattendedTest(Temp):
    def with_stdout(self, path, env):
        saved = os.dup(1)
        try:
            with open(path, "w") as target:
                os.dup2(target.fileno(), 1)
                return OPEN["unattended"](env)
        finally:
            os.dup2(saved, 1)
            os.close(saved)

    def test_only_output_nobody_reads_counts(self):
        log = self.write("session/desktop.log", b"")
        env = {"BL_DEBUG_DIR": str(log.parent)}
        self.assertTrue(self.with_stdout(os.devnull, env))
        self.assertTrue(self.with_stdout(str(log), env))
        self.assertFalse(self.with_stdout(str(self.tmp / "out.txt"), env))
        self.assertFalse(self.with_stdout(str(log), {}))
        read, write = os.pipe()
        saved = os.dup(1)
        try:
            os.dup2(write, 1)
            self.assertFalse(OPEN["unattended"](env))
        finally:
            os.dup2(saved, 1)
            for fd in (saved, read, write):
                os.close(fd)


class CommandTest(Temp):
    def test_each_kind_gets_its_runner(self):
        exe, script = str(self.tmp / "a.exe"), str(self.tmp / "go.sh")
        self.write("go.sh", b"#!/bin/sh\n")
        self.assertEqual(OPEN["command"](exe, ["-x"], "windows"), ([OPEN["PROTON_RUN"], exe, "-x"], str(self.tmp)))
        self.assertEqual(OPEN["command"](exe, [], "windows", True)[0], [OPEN["PROTON_RUN"], "--window", exe])
        self.assertEqual(OPEN["command"](script, ["1"], "script")[0], ["/bin/sh", script, "1"])
        self.assertEqual(OPEN["command"](script, [], "x86 script")[0],
                         [OPEN["FEX"], "run", "--mode", "on", "--for", str(self.tmp), "--", "/bin/sh", script])

    def test_a_program_on_noexec_storage_runs_from_a_copy(self):
        program = self.write("storage/tool", elf(62))
        with mock.patch.dict(OPEN, {"CACHE": str(self.tmp / "cache"), "noexec": lambda path: True}):
            copy = OPEN["runnable"](str(program))
            self.assertNotEqual(copy, str(program))
            self.assertEqual(Path(copy).read_bytes(), elf(62))
            self.assertTrue(os.access(copy, os.X_OK))
            self.assertEqual(OPEN["runnable"](str(program)), copy)
        with mock.patch.dict(OPEN, {"noexec": lambda path: False}):
            self.assertEqual(OPEN["runnable"](str(program)), str(program))


class InstallTest(Temp):
    def test_the_run_entries_and_defaults_are_written_once(self):
        OPEN["install"](str(self.tmp))
        entry = (self.tmp / "droiddeck-open.desktop").read_text()
        self.assertIn("Exec=/usr/local/bin/droiddeck-open %f", entry)
        self.assertIn("application/x-ms-dos-executable", entry)
        self.assertIn("Exec=/usr/local/bin/droiddeck-open --terminal %f",
                      (self.tmp / "droiddeck-open-terminal.desktop").read_text())
        defaults = (self.tmp / "mimeapps.list").read_text()
        self.assertIn("application/x-shellscript=droiddeck-open.desktop\n", defaults)
        self.assertIn("application/x-msi=droiddeck-open.desktop;droiddeck-open-window.desktop;\n", defaults)
        stamp = (self.tmp / "mimeapps.list").stat().st_mtime_ns
        time.sleep(0.01)
        OPEN["install"](str(self.tmp))
        self.assertEqual((self.tmp / "mimeapps.list").stat().st_mtime_ns, stamp)


class ImportTest(Temp):
    def test_the_app_unpacks_what_the_desktop_cannot(self):
        launch = self.tmp / "launch"
        launch.mkdir()
        folder = self.tmp / "imported/eden"
        (folder / "app").mkdir(parents=True)

        def app():
            request = launch / "desktop-import-0123456789abcdef.request"
            while not request.exists():
                time.sleep(0.05)
            text = request.read_text()
            request.unlink()
            self.assertEqual(text, "id=0123456789abcdef\nkind=appimage\npath=/root/Storage/Eden.AppImage\n")
            (launch / "desktop-import-0123456789abcdef.result").write_text("ok %s\n" % folder)

        worker = threading.Thread(target=app)
        worker.start()
        with mock.patch.dict(os.environ, {"BL_LAUNCH_DIR": str(launch)}), mock.patch.dict(OPEN, {"notify": lambda *a: None}):
            self.assertEqual(OPEN["imported"]("appimage", "/root/Storage/Eden.AppImage", "0123456789abcdef", "Eden"), str(folder))
        worker.join()
        self.assertEqual(list(launch.iterdir()), [])

    def test_the_apps_answer_is_the_error(self):
        launch = self.tmp / "launch"
        launch.mkdir()
        (launch / "desktop-import-0123456789abcdef.result").write_text("error Not enough space\n")
        with mock.patch.dict(os.environ, {"BL_LAUNCH_DIR": str(launch)}), mock.patch.dict(OPEN, {"notify": lambda *a: None}):
            with self.assertRaisesRegex(RuntimeError, "^Not enough space$"):
                OPEN["imported"]("appimage", "/x.AppImage", "0123456789abcdef", "x")

    def test_outside_a_session_it_says_how_to_add_one(self):
        with mock.patch.dict(os.environ, {"BL_LAUNCH_DIR": ""}):
            with self.assertRaisesRegex(RuntimeError, "Add an app"):
                OPEN["imported"]("folder", "/x/start.sh", "0123456789abcdef", "x")

    def test_starts_of_one_image_unpack_it_once(self):
        image = self.write("Tool.AppImage", elf(183, b"AI\x02"))
        unpacks = []

        def extract(cmd, cwd, stdout):
            unpacks.append(cwd)
            time.sleep(0.2)
            os.makedirs(os.path.join(cwd, "squashfs-root"))
            open(os.path.join(cwd, "squashfs-root", "AppRun"), "w").close()
            return 0

        found = []
        none = {what: (str(self.tmp / "none"), made) for what, (_, made) in OPEN["IMPORTS"].items()}
        with mock.patch.dict(OPEN, {"CACHE": str(self.tmp / "cache"), "IMPORTS": none, "notify": lambda *a: None}), \
                mock.patch.object(OPEN["subprocess"], "call", extract):
            starts = [threading.Thread(target=lambda: found.append(OPEN["appimage"](str(image)))) for _ in range(3)]
            for start in starts:
                start.start()
            for start in starts:
                start.join()
        self.assertEqual(len(unpacks), 1)
        self.assertEqual(len(set(found)), 1)
        self.assertTrue(os.path.isfile(os.path.join(found[0], "app", "AppRun")))
        self.assertEqual(Path(found[0], "arch").read_text(), "arm64\n")

    def test_an_earlier_import_is_found_by_its_key(self):
        self.write("user/eden/desktop-import", "0123456789abcdef\n/root/Storage/Eden.AppImage")
        (self.tmp / "user/eden/app").mkdir()
        self.write("user/other/desktop-import", "fedcba9876543210\n/root/Storage/Other.AppImage")
        self.write("apps/game/desktop-import", "fedcba9876543210\n/root/Storage/Game/start.sh")
        self.write("apps/game/entry", "start.sh")
        imports = {"appimage": (str(self.tmp / "user"), "app"), "folder": (str(self.tmp / "apps"), "entry")}
        with mock.patch.dict(OPEN, {"IMPORTS": imports}):
            self.assertEqual(OPEN["import_of"]("0123456789abcdef", "appimage"), str(self.tmp / "user/eden"))
            self.assertIsNone(OPEN["import_of"]("fedcba9876543210", "appimage"))
            self.assertEqual(OPEN["import_of"]("fedcba9876543210", "folder"), str(self.tmp / "apps/game"))


class GameFolderTest(Temp):
    def setUp(self):
        super().setUp()
        self.storage = self.tmp / "storage"
        patcher = mock.patch.dict(OPEN, {"noexec": lambda path: True, "storage_root": lambda folder: str(self.storage)})
        patcher.start()
        self.addCleanup(patcher.stop)

    def folder(self, path):
        return OPEN["game_folder"](str(self.storage / path))

    def test_a_games_folder_on_noexec_storage_is_copied_in(self):
        self.write("storage/Download/Game/start.sh", b"#!/bin/sh\n./bin/game.x86_64\n")
        self.write("storage/Download/Game/bin/game.x86_64", elf(62))
        self.write("storage/Download/Unity/Game.x86_64", elf(62))
        self.write("storage/Download/Unity/UnityPlayer.so", elf(62))
        self.write("storage/MyGame/start.sh", b"./game\n")
        self.write("storage/MyGame/game", elf(183))
        self.assertEqual(self.folder("Download/Game/start.sh"), str(self.storage / "Download/Game"))
        self.assertEqual(self.folder("Download/Unity/Game.x86_64"), str(self.storage / "Download/Unity"))
        self.assertEqual(self.folder("MyGame/start.sh"), str(self.storage / "MyGame"))

    def test_what_runs_where_it_lies_is_left_there(self):
        self.write("storage/Download/Scripts/hello.sh", b"echo hi\n")
        self.write("storage/Download/Tool/tool", elf(62))
        self.write("storage/Download/start.sh", b"./Tool/tool\n")
        self.write("storage/start.sh", b"./Download/Tool/tool\n")
        self.write("storage/Download/Deep/a/b/c/d/lib.so", elf(62))
        self.write("storage/Download/Deep/start.sh", b"echo\n")
        for path in ("Download/Scripts/hello.sh", "Download/Tool/tool", "Download/start.sh", "start.sh", "Download/Deep/start.sh"):
            with self.subTest(path):
                self.assertIsNone(self.folder(path))
        self.write("internal/Game/start.sh", b"./game\n")
        self.write("internal/Game/game", elf(62))
        with mock.patch.dict(OPEN, {"noexec": lambda path: False}):
            self.assertIsNone(OPEN["game_folder"](str(self.tmp / "internal/Game/start.sh")))

    def test_the_app_copies_the_folder_in_once(self):
        script = self.write("storage/Download/Game/start.sh", b"./game\n")
        launch = self.tmp / "launch"
        launch.mkdir()
        copy = self.tmp / "apps/game"
        copy.mkdir(parents=True)
        (copy / "entry").write_text("start.sh")
        requests = []

        def app():
            while not requests:
                for request in launch.glob("desktop-import-*.request"):
                    requests.append(request.read_text())
                    request.unlink()
                    (launch / request.name.replace(".request", ".result")).write_text("ok %s\n" % copy)
                time.sleep(0.05)

        worker = threading.Thread(target=app)
        worker.start()
        imports = {what: (str(self.tmp / "none"), made) for what, (_, made) in OPEN["IMPORTS"].items()}
        with mock.patch.dict(os.environ, {"BL_LAUNCH_DIR": str(launch)}), \
                mock.patch.dict(OPEN, {"CACHE": str(self.tmp / "cache"), "IMPORTS": imports, "notify": lambda *a: None}):
            self.assertEqual(OPEN["command"](str(script), ["-x"], "folder"), ([OPEN["SCRIPT_RUN"], str(copy), "-x"], str(script.parent)))
        worker.join()
        self.assertRegex(requests[0], "^id=[0-9a-f]{16}\nkind=folder\npath=%s\n$" % script)


def appinfo(apps):
    """appinfo.vdf as the client writes it (format 29: keys in a string table)."""
    strings = []

    def key(name):
        if name not in strings:
            strings.append(name)
        return struct.pack("<I", strings.index(name))

    def section(items):
        out = b""
        for name, value in items.items():
            if isinstance(value, dict):
                out += b"\x00" + key(name) + section(value)
            else:
                out += b"\x01" + key(name) + value.encode() + b"\0"
        return out + b"\x08"

    body = b""
    for app, info in apps.items():
        data = section({"appinfo": dict(info, appid=str(app))})
        header = struct.pack("<IIQ", 2, 0, 0) + bytes(20) + struct.pack("<I", 1) + bytes(20)
        body += struct.pack("<II", app, len(header) + len(data)) + header + data
    body += struct.pack("<I", 0)
    table = struct.pack("<I", len(strings)) + b"".join(s.encode() + b"\0" for s in strings)
    return struct.pack("<IIq", 0x07564429, 1, 16 + len(body)) + body + table


STARDEW = {"common": {"type": "Game", "oslist": "windows,macos,linux"}, "config": {"launch": {
    "0": {"executable": "Stardew Valley.exe", "type": "default", "config": {"oslist": "windows"}},
    "1": {"executable": "StardewValley", "type": "default", "config": {"oslist": "linux"}},
    "2": {"executable": ".\\Tools\\Setup.exe", "type": "option1"},
}}}


TOOL_VDF = '''"compatibilitytools"
{
  "compat_tools"
  {
    "%s"
    {
      "install_path" "."
      "display_name" "%s"
      "from_oslist" "%s"
      "to_oslist" "linux"
    }
  }
}
'''

CONFIG = '''"InstallConfigStore"
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
            "name" "droiddeck-proton-arm64"
            "config" ""
            "priority" "75"
          }
          "413150"
          {
            "name" "droiddeck-proton-11-arm64"
            "config" ""
            "priority" "250"
          }
        }
      }
    }
  }
}
'''


class ProtonRunTest(Temp):
    def setUp(self):
        super().setUp()
        self.root = self.tmp / "Steam"
        for name, oslist in (("droiddeck-proton-arm64", "windows"), ("droiddeck-proton-11-arm64", "windows"),
                             ("droiddeck-fex", "linux")):
            folder = "compatibilitytools.d/%s" % name
            self.write("Steam/%s/compatibilitytool.vdf" % folder, TOOL_VDF % (name, name, oslist))
            self.write("Steam/%s/toolmanifest.vdf" % folder, '"manifest"\n{\n  "commandline" "/droiddeck-proton %verb%"\n}\n')
            self.write("Steam/%s/droiddeck-proton" % folder, b"#!/bin/sh\n", 0o755)
        self.write("Steam/config/config.vdf", CONFIG)
        patcher = mock.patch.dict(RUN, {"EXTRA_LIBRARIES": (), "SYSTEM_TOOLS": (), "STATE": str(self.tmp / "state.json")})
        patcher.start()
        self.addCleanup(patcher.stop)

    def game(self, appid, installdir, relative):
        self.write("Steam/steamapps/appmanifest_%s.acf" % appid, '"AppState"\n{\n  "appid" "%s"\n  "installdir" "%s"\n}\n'
                   % (appid, installdir))
        return self.write("Steam/steamapps/common/%s/%s" % (installdir, relative), b"MZ")

    def test_only_windows_tools_that_can_start_are_offered(self):
        found = RUN["tools"](str(self.root))
        self.assertEqual(sorted(found), ["droiddeck-proton-11-arm64", "droiddeck-proton-arm64"])
        self.assertEqual(found["droiddeck-proton-arm64"],
                         (str(self.root / "compatibilitytools.d/droiddeck-proton-arm64"), "droiddeck-proton"))

    def test_steams_choice_for_the_title_then_its_default(self):
        available = RUN["tools"](str(self.root))
        current = RUN["mapping"](str(self.root))
        self.assertEqual(RUN["choose_tool"]("413150", current, available), ("droiddeck-proton-11-arm64", "Steam's choice for this title"))
        self.assertEqual(RUN["choose_tool"]("70", current, available), ("droiddeck-proton-arm64", "Steam's default"))
        self.assertEqual(RUN["choose_tool"]("70", {"0": "GE-Proton"}, available), ("droiddeck-proton-arm64", "the default ARM64 Proton"))
        self.assertEqual(RUN["choose_tool"]("70", {}, {}), (None, None))

    def test_a_steam_games_program_uses_its_prefix(self):
        program = self.game("413150", "Stardew Valley", "Stardew Valley.exe")
        target = RUN["locate"](str(program), str(self.root))
        self.assertEqual((target.appid, target.kind, target.compatdata, target.install),
                         ("413150", "steam", str(self.root / "steamapps/compatdata/413150"),
                          str(self.root / "steamapps/common/Stardew Valley")))

    def test_a_tool_in_the_library_is_not_a_game(self):
        program = self.game("1493710", "Proton - Experimental", "files/bin/wine.exe")
        self.assertEqual(RUN["locate"](str(program), str(self.root)).kind, "own")

    def test_a_program_in_a_prefix_uses_that_prefix(self):
        program = self.write("Steam/steamapps/compatdata/2870/pfx/drive_c/Setup/setup.exe", b"MZ")
        target = RUN["locate"](str(program), str(self.root))
        self.assertEqual((target.appid, target.kind, target.compatdata),
                         ("2870", "prefix", str(self.root / "steamapps/compatdata/2870")))

    def test_any_other_program_gets_the_id_steam_would_give_its_shortcut(self):
        program = str(self.write("Download/winemine.exe", b"MZ"))
        target = RUN["locate"](program, str(self.root))
        appid = zlib.crc32(('"%s"winemine' % program).encode()) | 0x80000000
        self.assertEqual((target.appid, target.kind), (str(appid), "own"))
        env = RUN["environment"]({}, target, "/tool", str(self.root), window=True)
        self.assertEqual((env["SteamAppId"], env["STEAM_COMPAT_APP_ID"], env["SteamGameId"], env["PROTON_USE_WINED3D"]),
                         ("0", "0", str(appid << 32 | 0x02000000), "1"))
        self.assertEqual(env["STEAM_COMPAT_DATA_PATH"], str(self.root / ("steamapps/compatdata/%d" % appid)))

    def test_a_steam_game_keeps_its_own_id(self):
        target = RUN["Target"]("413150", "/c", "/i", str(self.root), "steam")
        env = RUN["environment"]({"PROTON_USE_WINED3D": "0"}, target, "/tool", str(self.root), window=True)
        self.assertEqual((env["SteamAppId"], env["SteamGameId"], env["PROTON_USE_WINED3D"]), ("413150", "413150", "0"))

    def test_an_installer_package_goes_to_msiexec(self):
        self.assertEqual(RUN["arguments"]("/root/x/setup.msi", ["/qn"]), ["msiexec", "/i", "Z:\\root\\x\\setup.msi", "/qn"])
        self.assertEqual(RUN["arguments"]("/root/x/a.exe", ["-w"]), ["/root/x/a.exe", "-w"])

    def test_a_games_own_program_is_left_to_steam(self):
        self.write("Steam/appcache/appinfo.vdf", appinfo({413150: STARDEW}))
        self.assertEqual(RUN["compat"]()["launch_programs"](STARDEW["config"]), ["stardew valley.exe", "tools/setup.exe"])
        root = str(self.root)
        for relative, expected in (("Stardew Valley.exe", True), ("Tools/Setup.exe", True), ("createdump.exe", False)):
            with self.subTest(relative):
                program = str(self.game("413150", "Stardew Valley", relative))
                self.assertEqual(RUN["steam_launches"](RUN["locate"](program, root), program, root), expected)
        own = str(self.write("Download/Stardew Valley.exe", b"MZ"))
        self.assertFalse(RUN["steam_launches"](RUN["locate"](own, root), own, root))

    def test_the_desktop_hands_a_steam_game_to_steam(self):
        self.write("Steam/appcache/appinfo.vdf", appinfo({413150: STARDEW}))
        program = str(self.game("413150", "Stardew Valley", "Stardew Valley.exe"))
        with mock.patch.dict(os.environ, {"STEAM_COMPAT_CLIENT_INSTALL_PATH": str(self.root)}), \
                mock.patch.dict(OPEN, {"PROTON_RUN": str(BIN / "droiddeck-proton-run")}):
            appid = OPEN["steam_title"](program)
            self.assertEqual(appid, "413150")
            self.assertEqual(OPEN["command"](program, [], "steam", appid=appid)[0], [OPEN["STEAM_LAUNCH"], "desktop", "413150"])
            self.assertEqual(OPEN["placed"](["launch"], "steam", "run", {"BL_DESKTOP": "1"}), ["launch"])
            self.assertIsNone(OPEN["steam_title"](str(self.write("Download/winemine.exe", b"MZ"))))

    def test_the_tool_is_started_as_steam_starts_it(self):
        program = self.game("413150", "Stardew Valley", "Stardew Valley.exe")
        calls = []
        with mock.patch.dict(os.environ, {"STEAM_COMPAT_CLIENT_INSTALL_PATH": str(self.root)}), \
                mock.patch.object(RUN["os"], "execve", lambda *a: calls.append(a)), \
                mock.patch.object(RUN["os"], "chdir"), mock.patch.dict(RUN, {"say": lambda message: None}):
            RUN["main"]([str(program), "-fullscreen"])
        entry, argv, env = calls[0]
        self.assertEqual(entry, str(self.root / "compatibilitytools.d/droiddeck-proton-11-arm64/droiddeck-proton"))
        self.assertEqual(argv, [entry, "waitforexitandrun", str(program), "-fullscreen"])
        self.assertEqual(env["SteamAppId"], "413150")
        self.assertTrue((self.root / "steamapps/compatdata/413150").is_dir())


if __name__ == "__main__":
    unittest.main()
