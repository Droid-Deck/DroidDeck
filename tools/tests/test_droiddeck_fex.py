import contextlib
import gzip
import io
import json
import os
import runpy
import shutil
import struct
import tempfile
import unittest
from pathlib import Path
from unittest import mock

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
FEX = runpy.run_path(str(BIN / "droiddeck-fex"))
G = FEX["main"].__globals__


def elf(machine, data=1, size=64):
    head = bytearray(size)
    head[0:4] = b"\x7fELF"
    head[4] = 2
    head[5] = data
    struct.pack_into("<H" if data == 1 else ">H", head, 18, machine)
    return bytes(head)


class FexTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.home = self.tmp / "home"
        self.steam = self.home / ".local/share/Steam"
        (self.steam / "steamapps/common").mkdir(parents=True)
        state = self.home / ".local/share/droiddeck-fex"
        patches = {
            "HOME": str(self.home),
            "STEAM_ROOT": str(self.steam),
            "LIBRARIES": (str(self.tmp / "sd"),),
            "STATE": str(state),
            "CONFIG_DIR": str(state / "config"),
            "DATA_DIR": str(state / "data"),
            "CACHE_DIR": str(self.home / ".cache/droiddeck-fex"),
            "STATUS_FILE": str(state / "status.json"),
            "WANTED": str(self.home / ".config/droiddeck/fex-wanted"),
            "PRELOADS": str(self.tmp / "preloads"),
            "HOST_PRELOAD": str(self.tmp / "ld.so.preload"),
        }
        patcher = mock.patch.dict(G, patches)
        patcher.start()
        self.addCleanup(patcher.stop)

    def write(self, path, data=b"", mode=0o644):
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data if isinstance(data, bytes) else data.encode())
        path.chmod(mode)
        return path

    def fex_tool(self, library=None, server=True, thunks=True):
        tool = Path(library or self.steam) / "steamapps/common/FEX-Emu"
        self.write(tool / "usr/bin/FEX", elf(183), 0o755)
        if server:
            self.write(tool / "usr/bin/FEXServer", elf(183), 0o755)
        if thunks:
            (tool / "usr/lib/aarch64-linux-gnu/fex-emu/HostThunks").mkdir(parents=True)
            (tool / "usr/share/fex-emu/GuestThunks").mkdir(parents=True)
            self.write(tool / "usr/share/fex-emu/ThunksDB.json", "{}")
        self.write(Path(library or self.steam) / "steamapps/appmanifest_3127680.acf", '"AppState" { "installdir" "FEX-Emu" }')
        return tool

    def runtime(self, version="3.0.20260805.254768", lines=None):
        runtime = self.steam / "steamapps/common/SteamLinuxRuntime_sniper"
        platform = runtime / ("sniper_platform_" + version)
        files = platform / "files"
        self.write(files / "lib/x86_64-linux-gnu/libc.so.6", elf(62))
        self.write(files / "ab/cdef-1.bin", b"blob")
        self.write(files / "share/ca/real.crt", b"cert")
        mtree = lines or [
            "#mtree",
            ". type=dir",
            "./bin type=dir",
            "./lib64 type=dir",
            "./lib64/ld-linux-x86-64.so.2 type=link link=/usr/lib/x86_64-linux-gnu/ld-2.31.so",
            "./lib/x86_64-linux-gnu/libc.so.6 type=file mode=644 size=64",
            "./bin/\\133 type=file mode=755 size=4 contents=./ab/cdef-1.bin",
            "./share/ca/Na\\075me.crt type=link link=real.crt",
            "./etc/ssl/cert.pem type=link link=/etc/ssl/certs/ca.pem",
        ]
        with gzip.open(platform / "usr-mtree.txt.gz", "wt") as out:
            out.write("\n".join(mtree) + "\n")
        self.write(self.steam / "steamapps/appmanifest_1628350.acf", '"AppState" { "installdir" "SteamLinuxRuntime_sniper" }')
        return platform

    def quiet(self, function, *args):
        with contextlib.redirect_stderr(io.StringIO()) as err:
            return function(*args), err.getvalue()


class ArchitectureTest(FexTestCase):
    def test_elf_machines(self):
        self.assertEqual(FEX["elf_arch"](self.write(self.tmp / "a", elf(62))), "x86_64")
        self.assertEqual(FEX["elf_arch"](self.write(self.tmp / "b", elf(3))), "i386")
        self.assertEqual(FEX["elf_arch"](self.write(self.tmp / "c", elf(183))), "arm64")
        self.assertEqual(FEX["elf_arch"](self.write(self.tmp / "d", elf(62, data=2))), "x86_64")
        self.assertEqual(FEX["elf_arch"](self.write(self.tmp / "e", elf(8))), "other")
        self.assertIsNone(FEX["elf_arch"](self.write(self.tmp / "f", "#!/bin/sh\n")))
        self.assertIsNone(FEX["elf_arch"](self.tmp / "missing"))

    def test_a_script_follows_the_programs_beside_it(self):
        game = self.tmp / "game"
        script = self.write(game / "start.sh", "#!/bin/bash\n./bin/game\n")
        self.assertFalse(FEX["needs_fex"](str(script)))
        self.write(game / "bin/game.x86_64", elf(62), 0o755)
        self.assertTrue(FEX["needs_fex"](str(script)))
        self.assertTrue(FEX["needs_fex"](str(game)))
        self.write(game / "bin/game.arm64", elf(183), 0o755)
        self.assertFalse(FEX["needs_fex"](str(script)))
        self.assertTrue(FEX["needs_fex"](str(game / "bin/game.x86_64")))
        self.assertFalse(FEX["needs_fex"](str(game / "bin/game.arm64")))

    def test_the_scan_stops_at_its_depth(self):
        game = self.tmp / "deep"
        self.write(game / "a/b/c/d/e/game", elf(62), 0o755)
        self.assertFalse(FEX["needs_fex"](str(game)))
        self.write(game / "a/b/game", elf(62), 0o755)
        self.assertTrue(FEX["needs_fex"](str(game)))

    def test_appimage_offset_is_the_end_of_the_section_headers(self):
        head = bytearray(elf(62))
        struct.pack_into("<Q", head, 0x28, 1000)
        struct.pack_into("<HH", head, 0x3A, 64, 30)
        self.assertEqual(FEX["appimage_offset"](self.write(self.tmp / "x.AppImage", bytes(head))), 1000 + 64 * 30)
        head = bytearray(elf(3))
        head[4] = 1
        struct.pack_into("<I", head, 0x20, 500)
        struct.pack_into("<HH", head, 0x2E, 40, 10)
        self.assertEqual(FEX["appimage_offset"](self.write(self.tmp / "y.AppImage", bytes(head))), 900)

    def test_run_options(self):
        self.assertEqual(FEX["parse_run"](["--mode", "on", "--for", "/x", "--", "a", "--mode"]), ("on", "/x", ["a", "--mode"]))
        self.assertEqual(FEX["parse_run"](["a"]), ("auto", None, ["a"]))
        with self.assertRaises(ValueError):
            FEX["parse_run"](["--mode", "maybe", "a"])
        with self.assertRaises(ValueError):
            FEX["parse_run"](["--bogus", "a"])


class LocateTest(FexTestCase):
    def test_tools_are_found_in_any_library(self):
        self.assertIsNone(FEX["fex_install"]() if not os.access("/usr/bin/FEX", os.X_OK) else None)
        sd = self.tmp / "sd"
        (sd / "steamapps/common").mkdir(parents=True)
        tool = self.fex_tool(sd)
        fex = FEX["fex_install"]()
        self.assertEqual((fex["bin"], fex["server"], fex["portable"]), (str(tool / "usr/bin/FEX"), str(tool / "usr/bin/FEXServer"), True))
        self.assertTrue(fex["host_thunks"].endswith("HostThunks") and fex["guest_thunks"].endswith("GuestThunks") and fex["thunks_db"])

    def test_library_folders_are_read(self):
        other = self.tmp / "other"
        (other / "steamapps/common").mkdir(parents=True)
        self.write(self.steam / "steamapps/libraryfolders.vdf", '"libraryfolders" { "1" { "path" "%s" } }' % other)
        tool = self.fex_tool(other)
        self.assertEqual(FEX["app_dir"]("3127680"), str(tool))

    def test_the_newest_runtime_platform_wins(self):
        self.runtime("3.0.20260805.254768")
        newer = self.runtime("3.0.20261001.1")
        self.assertEqual(FEX["runtime_platform"](), str(newer))


class PrepareTest(FexTestCase):
    def test_materialize_links_the_tree_in_place(self):
        platform = self.runtime()
        files = platform / "files"
        self.assertTrue(FEX["materialize"](str(platform)))
        self.assertEqual(os.readlink(files / "lib64/ld-linux-x86-64.so.2"), "../lib/x86_64-linux-gnu/ld-2.31.so")
        self.assertEqual(os.readlink(files / "bin/["), "../ab/cdef-1.bin")
        self.assertEqual((files / "bin/[").read_bytes(), b"blob")
        self.assertEqual((files / "share/ca/Na=me.crt").read_bytes(), b"cert")
        self.assertEqual(os.readlink(files / "etc/ssl/cert.pem"), "certs/ca.pem")
        self.assertEqual(os.readlink(files / "usr"), ".")
        self.assertEqual((files / "usr/lib/x86_64-linux-gnu/libc.so.6").read_bytes()[:4], b"\x7fELF")
        self.assertFalse(FEX["materialize"](str(platform)))
        os.unlink(files / "bin/[")
        os.utime(platform / "usr-mtree.txt.gz", ns=(1, 1))
        self.assertTrue(FEX["materialize"](str(platform)))
        self.assertTrue(os.path.islink(files / "bin/["))

    def test_materialize_ignores_paths_outside_the_tree(self):
        platform = self.runtime(lines=["./../escape type=link link=x", "/abs type=dir", "./ok type=link link=x"])
        FEX["materialize"](str(platform))
        self.assertFalse((platform / "escape").exists() or os.path.islink(platform / "escape"))
        self.assertTrue(os.path.islink(platform / "files/ok"))

    def test_preloads_mirror_the_host_list(self):
        root = self.tmp / "root"
        for arch in ("x86_64", "i386"):
            for name in ("libblsession.so", "libfakeinput.so"):
                self.write(self.tmp / "preloads" / arch / name, arch.encode() + name.encode())
        self.write(self.tmp / "preloads/x86_64/libthunkaudit.so", b"audit")
        self.write(self.tmp / "preloads/x86_64/libvulkan-thunk.so", b"pin")
        self.write(self.tmp / "ld.so.preload", "/usr/local/lib/libblsession.so\n/usr/local/lib/libfakeinput.so\n")
        FEX["install_preloads"](str(root))
        self.assertEqual((root / "lib/x86_64-linux-gnu/droiddeck/libthunkaudit.so").read_bytes(), b"audit")
        self.assertEqual((root / "lib/x86_64-linux-gnu/droiddeck/libvulkan-thunk.so").read_bytes(), b"pin")
        self.assertEqual((root / "etc/ld.so.preload").read_text(),
                         "/usr/$LIB/droiddeck/libblsession.so\n/usr/$LIB/droiddeck/libfakeinput.so\n")
        self.assertEqual((root / "lib/i386-linux-gnu/droiddeck/libfakeinput.so").read_bytes(), b"i386libfakeinput.so")
        self.write(self.tmp / "ld.so.preload", "/usr/local/lib/libblsession.so\n")
        FEX["install_preloads"](str(root))
        self.assertEqual((root / "etc/ld.so.preload").read_text(), "/usr/$LIB/droiddeck/libblsession.so\n")

    def test_only_x86_loaders_preload_the_fault_report(self):
        root = self.tmp / "root"
        self.write(self.tmp / "preloads/x86_64/libfaultreport.so", b"report64")
        FEX["install_preloads"](str(root))
        self.assertNotIn("libfaultreport.so", (root / "etc/ld.so.preload").read_text())
        self.write(self.tmp / "preloads/i386/libfaultreport.so", b"report32")
        FEX["install_preloads"](str(root))
        self.assertEqual((root / "lib/x86_64-linux-gnu/droiddeck/libfaultreport.so").read_bytes(), b"report64")
        self.assertEqual((root / "lib/i386-linux-gnu/droiddeck/libfaultreport.so").read_bytes(), b"report32")
        self.assertEqual((root / "etc/ld.so.preload").read_text(), "/usr/$LIB/droiddeck/libfaultreport.so\n")

    def test_config_turns_thunks_on_only_when_complete(self):
        FEX["write_config"]({"host_thunks": "/h", "guest_thunks": "/g", "thunks_db": "/db"}, "/root")
        config = json.loads((self.home / ".local/share/droiddeck-fex/config/Config.json").read_text())
        self.assertEqual(config, {"Config": {"RootFS": "/root", "ThunkHostLibs": "/h", "ThunkGuestLibs": "/g"}, "ThunksDB": {"GL": 1, "Vulkan": 1}})
        FEX["write_config"]({"host_thunks": None, "guest_thunks": "/g", "thunks_db": "/db"}, "/root")
        config = json.loads((self.home / ".local/share/droiddeck-fex/config/Config.json").read_text())
        self.assertEqual(config, {"Config": {"RootFS": "/root"}})

    def test_missing_pieces_are_asked_for(self):
        with mock.patch.dict(G, {"fex_install": lambda: None, "steam_running": lambda: False}):
            fex, said = self.quiet(FEX["prepare"])
        self.assertIsNone(fex)
        self.assertIn("FEX (Steam app 3127680)", said)
        self.assertIn("Steam Linux Runtime 3.0", said)
        self.assertTrue(said.startswith("droiddeck-fex: x86 Linux programs need "))
        self.assertTrue((self.home / ".config/droiddeck/fex-wanted").is_file())
        status = json.loads((self.home / ".local/share/droiddeck-fex/status.json").read_text())
        self.assertFalse(status["ready"])

    def test_a_ready_install_is_prepared(self):
        tool = self.fex_tool()
        platform = self.runtime()
        self.write(self.home / ".config/droiddeck/fex-wanted", "1\n")
        fex, said = self.quiet(FEX["prepare"])
        self.assertEqual(fex["bin"], str(tool / "usr/bin/FEX"))
        self.assertIn("prepared sniper_platform_", said)
        self.assertTrue(os.path.islink(platform / "files/usr"))
        status = json.loads((self.home / ".local/share/droiddeck-fex/status.json").read_text())
        self.assertEqual((status["ready"], status["runtime"], status["thunks"]), (True, str(platform), True))
        self.assertFalse((self.home / ".config/droiddeck/fex-wanted").exists())
        self.assertEqual(self.quiet(FEX["prepare"])[1], "")

    def test_status_refreshes_what_the_app_reads_once_steam_installed_fex(self):
        with mock.patch.dict(G, {"steam_running": lambda: False}):
            self.quiet(FEX["prepare"])
        status_file = self.home / ".local/share/droiddeck-fex/status.json"
        self.assertFalse(json.loads(status_file.read_text())["ready"])
        self.fex_tool()
        self.runtime()
        with contextlib.redirect_stdout(io.StringIO()) as out:
            self.assertEqual(0, FEX["main"](["status"]))
        self.assertTrue(json.loads(out.getvalue())["ready"])
        self.assertTrue(json.loads(status_file.read_text())["ready"])

    def test_fex_is_found_at_the_tool_root_too(self):
        tool = self.steam / "steamapps/common/FEX-Emu"
        self.write(tool / "bin/FEX", elf(183), 0o755)
        self.write(tool / "bin/FEXServer", elf(183), 0o755)
        self.write(self.steam / "steamapps/appmanifest_3127680.acf", '"AppState" { "installdir" "FEX-Emu" }')
        fex = FEX["fex_install"]()
        self.assertEqual((fex["bin"], fex["server"], fex["portable"]), (str(tool / "bin/FEX"), str(tool / "bin/FEXServer"), True))

    def test_an_installed_tool_without_fex_says_what_it_holds(self):
        tool = self.steam / "steamapps/common/FEX-Emu"
        self.write(tool / "toolmanifest.vdf", "")
        self.write(self.steam / "steamapps/appmanifest_3127680.acf", '"AppState" { "installdir" "FEX-Emu" }')
        with mock.patch.dict(G, {"steam_running": lambda: False}), mock.patch("shutil.which", return_value=None), \
                mock.patch("os.access", lambda path, mode: False):
            fex, said = self.quiet(FEX["prepare"])
        self.assertIsNone(fex)
        self.assertIn("holds no FEX or FEXInterpreter", said)
        self.assertIn("toolmanifest.vdf", said)

    def test_a_missing_server_is_not_ready(self):
        self.fex_tool(server=False)
        self.runtime()
        with mock.patch.dict(G, {"steam_running": lambda: False}), mock.patch("shutil.which", return_value=None):
            fex, said = self.quiet(FEX["prepare"])
        self.assertIsNone(fex)
        self.assertIn("FEXServer beside", said)


class ExtractTest(FexTestCase):
    def setUp(self):
        super().setUp()
        self.work = self.tmp / "work"
        self.work.mkdir()
        self.image = self.write(self.work / "image.AppImage", elf(62) + b"payload", 0o755)
        chdir = contextlib.chdir(self.work)
        chdir.__enter__()
        self.addCleanup(chdir.__exit__, None, None, None)

    def uruntime(self, body):
        return str(self.write(self.tmp / "uruntime", "#!/bin/sh\n" + body, 0o755))

    def test_uruntime_unpacks_an_x86_image_without_fex(self):
        tool = self.uruntime('[ "$1" = --appimage-extract ] && [ "$TARGET_APPIMAGE" = "%s" ] || exit 9\nmkdir -p AppDir && touch AppDir/AppRun\n' % self.image)
        with mock.patch.dict(G, {"URUNTIME": tool, "fex_install": lambda: None, "steam_running": lambda: False}):
            status, said = self.quiet(FEX["extract"], "image.AppImage")
        self.assertEqual((status, said), (0, ""))
        self.assertEqual(os.readlink("squashfs-root"), "AppDir")
        self.assertTrue(Path("squashfs-root/AppRun").is_file())
        self.assertTrue((self.home / ".config/droiddeck/fex-wanted").is_file())
        self.assertFalse(json.loads((self.home / ".local/share/droiddeck-fex/status.json").read_text())["ready"])

    def test_an_arm64_image_does_not_ask_for_fex(self):
        self.write(self.image, elf(183) + b"payload", 0o755)
        tool = self.uruntime("mkdir -p squashfs-root\n")
        with mock.patch.dict(G, {"URUNTIME": tool}):
            status, said = self.quiet(FEX["extract"], "image.AppImage")
        self.assertEqual((status, said), (0, ""))
        self.assertFalse((self.home / ".config/droiddeck/fex-wanted").exists())

    def test_nothing_to_unpack_with_says_so(self):
        tool = self.uruntime("mkdir -p squashfs-root\nexit 1\n")
        with mock.patch.dict(G, {"URUNTIME": tool, "fex_install": lambda: None, "steam_running": lambda: False}), \
                mock.patch("shutil.which", return_value=None):
            status, said = self.quiet(FEX["extract"], "image.AppImage")
        self.assertEqual(status, 1)
        self.assertIn("uruntime could not extract it (status 1)", said)
        self.assertIn("no AppImage runtime for ARM64, no FEX yet and no unsquashfs", said)
        self.assertFalse(os.path.lexists("squashfs-root"))


class LaunchTest(FexTestCase):
    def test_the_guest_environment(self):
        arm = self.write(self.tmp / "arm.so", elf(183))
        x86 = self.write(self.tmp / "x86.so", elf(62))
        env = {"LD_PRELOAD": "%s:%s %s" % (arm, x86, self.tmp / "gone.so"),
               "LD_LIBRARY_PATH": "/steam/steamrtarm64:/usr/lib/aarch64-linux-gnu:/game/lib",
               "FEX_ROOTFS": "/usr/share/guestos/fex-mesa", "FEX_TSOENABLED": "1"}
        guest = FEX["guest_environment"]({"portable": True}, env)
        self.assertEqual((guest["LD_PRELOAD"], guest["LD_LIBRARY_PATH"], guest["FEX_PORTABLE"]), (str(x86), "/game/lib", "1"))
        self.assertNotIn("FEX_ROOTFS", guest)
        self.assertEqual(guest["FEX_TSOENABLED"], "1")
        self.assertTrue(guest["FEX_APP_CONFIG_LOCATION"].endswith("/config/"))
        self.assertEqual((guest["FEX_SILENTLOG"], guest["FEX_OUTPUTLOG"]), ("0", "stderr"))
        guest = FEX["guest_environment"]({"portable": False}, {"LD_PRELOAD": str(arm), "FEX_PORTABLE": "1", "FEX_OUTPUTLOG": "/tmp/fex.log"})
        self.assertEqual(guest["FEX_OUTPUTLOG"], "/tmp/fex.log")
        self.assertNotIn("LD_PRELOAD", guest)
        self.assertNotIn("FEX_PORTABLE", guest)

    def test_the_server_socket_stays_under_the_runtime_directory(self):
        runtime = self.tmp / "run"
        runtime.mkdir()
        guest = FEX["guest_environment"]({"portable": True}, {"XDG_RUNTIME_DIR": str(runtime)})
        self.assertEqual(guest["FEX_APP_DATA_LOCATION"], str(runtime / "fex") + "/")
        self.assertTrue((runtime / "fex").is_dir())
        guest = FEX["guest_environment"]({"portable": True}, {"XDG_RUNTIME_DIR": str(self.tmp / "missing")})
        self.assertEqual(guest["FEX_APP_DATA_LOCATION"], G["DATA_DIR"] + "/")

    def test_the_runtime_and_thunks_are_passed_to_fex(self):
        fex = {"portable": True, "rootfs": "/rt/files", "host_thunks": "/h", "guest_thunks": "/g", "thunks_db": "/db"}
        guest = FEX["guest_environment"](fex, {})
        self.assertEqual((guest["FEX_ROOTFS"], guest["FEX_THUNKHOSTLIBS"], guest["FEX_THUNKGUESTLIBS"]), ("/rt/files", "/h", "/g"))
        self.assertTrue(guest["FEX_THUNKCONFIG"].endswith("/config/ThunksConfig.json"))
        guest = FEX["guest_environment"](dict(fex, host_thunks=None), {})
        self.assertEqual(guest["FEX_ROOTFS"], "/rt/files")
        self.assertNotIn("FEX_THUNKCONFIG", guest)
        FEX["write_config"](fex, "/rt/files")
        thunks = json.loads((self.home / ".local/share/droiddeck-fex/config/ThunksConfig.json").read_text())
        self.assertEqual(thunks, {"ThunksDB": {"GL": 1, "Vulkan": 1}})

    def test_native_programs_run_directly(self):
        program = self.write(self.tmp / "native", elf(183), 0o755)
        with mock.patch("os.execvpe") as native, mock.patch("os.execve") as emulated:
            FEX["launch"]([str(program), "--flag"], {"PATH": "/usr/bin"}, "auto")
        native.assert_called_once_with(str(program), [str(program), "--flag"], {"PATH": "/usr/bin"})
        emulated.assert_not_called()

    def test_x86_programs_run_under_fex(self):
        tool = self.fex_tool()
        self.runtime()
        program = self.write(self.tmp / "game.x86_64", elf(62), 0o755)
        with mock.patch("os.execvpe") as native, mock.patch("os.execve") as emulated:
            self.quiet(FEX["launch"], [str(program), "-w"], {"PATH": "/usr/bin"}, "auto")
        native.assert_not_called()
        binary, argv, env = emulated.call_args[0]
        self.assertEqual((binary, argv), (str(tool / "usr/bin/FEX"), [str(tool / "usr/bin/FEX"), str(program), "-w"]))
        self.assertEqual(env["FEX_PORTABLE"], "1")
        self.assertNotIn("LD_PRELOAD", env)
        self.assertEqual(env["FEX_ROOTFS"], str(self.steam / "steamapps/common/SteamLinuxRuntime_sniper/sniper_platform_3.0.20260805.254768/files"))
        for arch in ("x86_64", "i386"):
            self.write(self.tmp / "preloads" / arch / "libfaultreport.so", elf(62 if arch == "x86_64" else 3))
        x86 = self.write(self.tmp / "x86.so", elf(62))
        with mock.patch("os.execve") as emulated:
            self.quiet(FEX["launch"], [str(program)], {"LD_PRELOAD": str(x86)}, "auto")
        self.assertEqual(emulated.call_args[0][2]["LD_PRELOAD"], str(x86))
        old = self.write(self.tmp / "game.i386", elf(3), 0o755)
        with mock.patch("os.execve") as emulated:
            self.quiet(FEX["launch"], [str(old)], {}, "auto")
        self.assertNotIn("LD_PRELOAD", emulated.call_args[0][2])
        with mock.patch("os.execvpe") as native, mock.patch("os.execve") as emulated:
            self.quiet(FEX["launch"], [str(program)], {}, "off")
        native.assert_called_once()
        emulated.assert_not_called()

    def test_programs_that_bring_a_new_enough_runtime_get_the_vulkan_thunk(self):
        tool = self.fex_tool()
        self.runtime()
        self.write(tool / "usr/share/fex-emu/GuestThunks/libvulkan-guest.so",
                   elf(62) + b"\0libstdc++.so.6\0GLIBCXX_3.4\0GLIBCXX_3.4.29\0libc.so.6\0GLIBC_2.2.5\0GLIBC_2.34\0")
        self.write(self.tmp / "preloads/x86_64/libthunkaudit.so", elf(62))
        pin = self.write(self.tmp / "preloads/x86_64/libvulkan-thunk.so", elf(62))
        audit = "/usr/lib/x86_64-linux-gnu/droiddeck/libthunkaudit.so"

        def app(name, glibc=b"GLIBC_2.34\0GLIBC_2.44", glibcxx=b"GLIBCXX_3.4.29\0GLIBCXX_3.4.36", bundled=True):
            run = self.write(self.tmp / name / "AppRun", elf(62), 0o755)
            if bundled:
                self.write(self.tmp / name / "shared/lib/libc.so.6", elf(62) + b"\0GLIBC_2.2.5\0" + glibc + b"\0")
                self.write(self.tmp / name / "shared/lib/libstdc++.so.6",
                           elf(62) + b"\0GLIBCXX_3.4\0" + glibcxx + b"\0GLIBC_2.34\0")
            return run

        def started(program, env=None, decide=None):
            with mock.patch("os.execve") as emulated:
                _, said = self.quiet(FEX["launch"], [str(program)], dict(env or {}), "on", decide)
            return emulated.call_args[0][2], said

        env, said = started(app("new"))
        self.assertEqual(env["LD_AUDIT"], audit)
        self.assertIn("thunks on, its own Vulkan loader too", said)
        env, _ = started(app("kept"), {"LD_AUDIT": "/opt/audit.so"})
        self.assertEqual(env["LD_AUDIT"], audit + ":/opt/audit.so")
        env, _ = started(self.write(self.tmp / "elsewhere/run.sh", "#!/bin/sh\n", 0o755), decide=str(self.tmp / "new"))
        self.assertEqual(env["LD_AUDIT"], audit)
        env, said = started(app("old-cxx", glibcxx=b"GLIBCXX_3.4.28"))
        self.assertNotIn("LD_AUDIT", env)
        self.assertIn("thunks on)", said)
        self.assertNotIn("LD_AUDIT", started(app("old-libc", glibc=b"GLIBC_2.31"))[0])
        self.assertNotIn("LD_AUDIT", started(app("runtime-only", bundled=False))[0])
        i386 = self.write(self.tmp / "new/game.i386", elf(3), 0o755)
        self.assertNotIn("LD_AUDIT", started(i386)[0])
        pin.unlink()
        self.assertNotIn("LD_AUDIT", started(app("no-pin"))[0])
        self.write(pin, elf(62))
        (tool / "usr/share/fex-emu/GuestThunks/libvulkan-guest.so").unlink()
        self.assertNotIn("LD_AUDIT", started(app("no-thunk"))[0])

    def test_steam_verbs(self):
        with mock.patch.dict(G, {"launch": mock.Mock(return_value=0)}):
            self.assertEqual(FEX["main"](["steam", "getcompatpath", "/x"]), 0)
            G["launch"].assert_not_called()
            FEX["main"](["steam", "waitforexitandrun", "/game/run.sh", "-a"])
            command, env, mode = G["launch"].call_args[0]
            self.assertEqual((command, mode), (["/game/run.sh", "-a"], "auto"))


class AppInfoTest(unittest.TestCase):
    COMPAT = runpy.run_path(str(BIN / "steam-compatibility"))

    @staticmethod
    def appinfo(apps, magic=0x07564429):
        strings = []

        def key(name):
            if magic != 0x07564429:
                return name.encode() + b"\0"
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
        for app, entry in apps.items():
            if isinstance(entry, dict):
                data = section({"appinfo": dict(entry, appid=str(app))})
            else:
                oslist, osarch, kind = entry
                data = section({"appinfo": {"appid": str(app), "common": {"type": kind, "oslist": oslist, "osarch": osarch}}})
            header = struct.pack("<IIQ", 2, 0, 0) + b"\0" * 20 + struct.pack("<I", 1) + (b"\0" * 20 if magic >= 0x07564428 else b"")
            body += struct.pack("<II", app, len(header) + len(data)) + header + data
        body += struct.pack("<I", 0)
        if magic != 0x07564429:
            return struct.pack("<II", magic, 1) + body
        table_at = 16 + len(body)
        table = struct.pack("<I", len(strings)) + b"".join(s.encode() + b"\0" for s in strings)
        return struct.pack("<IIq", magic, 1, table_at) + body + table

    def test_linux_only_titles_are_sent_to_fex(self):
        for magic in (0x07564427, 0x07564428, 0x07564429):
            with tempfile.TemporaryDirectory() as tmp:
                steam = Path(tmp)
                (steam / "appcache").mkdir()
                (steam / "appcache/appinfo.vdf").write_bytes(self.appinfo({
                    10: ("windows", "", "Game"), 20: ("linux", "64", "Game"), 30: ("windows,linux,macos", "", "game"),
                    40: ("linux", "arm64", "game"), 50: ("linux", "", "Demo"), 70: ("linux", "", "Tool"), 80: ("linux", "", "config"),
                    90: ("linux", "", ""),
                }, magic))
                self.COMPAT["APPINFO_CACHE"].clear()
                targets = self.COMPAT["native_targets"](str(steam), ["10", "20", "30", "40", "50", "70", "80", "90", "1628350", "60"])
                self.assertEqual(targets, {"20": "droiddeck-fex", "50": "droiddeck-fex"}, hex(magic))

    def test_titles_with_a_valve_pick_are_found_installed_or_not(self):
        def deck(oslist, runtime, kind="game"):
            common = {"type": kind, "oslist": oslist, "steam_deck_compatibility": {"configuration": {"recommended_runtime": runtime}}}
            if oslist is None:
                del common["oslist"]
            return {"common": common}
        for magic in (0x07564427, 0x07564428, 0x07564429):
            with tempfile.TemporaryDirectory() as tmp:
                steam = Path(tmp)
                (steam / "appcache").mkdir()
                (steam / "appcache/appinfo.vdf").write_bytes(self.appinfo({
                    10: deck("windows", "proton-stable"), 11: deck("windows,linux", "native"), 12: deck(None, "proton-7.0-5"),
                    13: deck("linux", "native"), 14: deck("windows", "proton-stable", "Tool"), 15: ("windows", "", "game"),
                    16: ("windows", "", "game"), 1628350: deck("linux", "native", "game"),
                    891390: {"common": {"type": "Config"}, "extended": {"app_mappings": {
                        "16": {"appid": "16", "tool": "proton-stable"}, "17": {"appid": "17", "tool": ""}}}},
                }, magic))
                self.COMPAT["APPINFO_CACHE"].clear()
                self.assertEqual(self.COMPAT["valve_picks"](str(steam)), ["10", "11", "12", "16"], hex(magic))

    def test_titles_with_a_valve_pick_follow_the_default_until_the_user_picks(self):
        tool = self.COMPAT["TOOL"]
        changes, auto = self.COMPAT["plan_mapping"](
            {"0": tool, "31": "GE-Proton11-7", "32": "proton_11_arm64", "33": "steamlinuxruntime"},
            ["40"], [tool, "GE-Proton11-7"], tool, {}, {}, ["30", "31", "32", "33"])
        self.assertEqual(changes, {"30": tool, "32": tool, "40": tool})
        self.assertEqual(auto, {"30": tool, "32": tool, "40": tool})
        changes, auto = self.COMPAT["plan_mapping"](
            {"0": "GE-Proton11-7", "30": tool, "31": "GE-Proton11-7", "32": tool}, [], [tool, "GE-Proton11-7"], "GE-Proton11-7", auto, {}, ["30", "31", "32"])
        self.assertEqual(changes, {"30": "GE-Proton11-7", "32": "GE-Proton11-7"})
        self.assertEqual(auto, {"30": "GE-Proton11-7", "32": "GE-Proton11-7"})

    def test_the_mapping_sends_native_titles_to_fex_and_keeps_picks(self):
        tool, fex = self.COMPAT["TOOL"], self.COMPAT["FEX_TOOL"]
        changes, auto = self.COMPAT["plan_mapping"](
            {"0": tool, "20": "fex", "21": "steamlinuxruntime_sniper", "22": "GE-Proton11-7"},
            ["20", "21", "22", "23", "24"], [tool, "GE-Proton11-7"], tool, {},
            {"20": fex, "21": fex, "22": fex, "23": fex})
        self.assertEqual(changes, {"20": fex, "21": fex, "23": fex, "24": tool})
        self.assertEqual(auto, {"20": fex, "21": fex, "23": fex, "24": tool})

    def test_the_tool_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            tool = Path(tmp) / "droiddeck-fex"
            self.COMPAT["build_fex_tool"](str(tool))
            self.assertEqual(sorted(os.listdir(tool)), sorted(self.COMPAT["FEX_FILES"]))
            self.assertIn('"commandline" "/droiddeck-fex-tool %verb%"', (tool / "toolmanifest.vdf").read_text())
            self.assertTrue(os.access(tool / "droiddeck-fex-tool", os.X_OK))
            vdf = (tool / "compatibilitytool.vdf").read_text()
            self.assertIn('"from_oslist" "linux"', vdf)
            self.assertIn('"to_oslist" "linux"', vdf)


if __name__ == "__main__":
    unittest.main()
