import http.client
import io
import json
import os
import runpy
import shutil
import socket
import signal
import subprocess
import sys
import tarfile
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest import mock

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
EPIC = runpy.run_path(str(BIN / "droiddeck-epic"))
G = EPIC["main"].__globals__
STORE = runpy.run_path(str(BIN / "droiddeck-store-launch"))
S = STORE["main"].__globals__


def tar_add(archive, name, data=None, link=None, kind=None, mode=0o644):
    info = tarfile.TarInfo(name)
    if kind == "dir":
        info.type = tarfile.DIRTYPE
        info.mode = 0o755
        archive.addfile(info)
    elif link is not None:
        info.type = tarfile.SYMTYPE if kind != "hard" else tarfile.LNKTYPE
        info.linkname = link
        archive.addfile(info)
    else:
        info.size = len(data)
        info.mode = mode
        archive.addfile(info, io.BytesIO(data))


def ar(members):
    out = bytearray(b"!<arch>\n")
    for name, data in members:
        out += ("%-16s%-12s%-6s%-6s%-8s%-10d`\n" % (name + "/", 0, 0, 0, "100644", len(data))).encode()
        out += data
        if len(data) % 2:
            out += b"\n"
    return bytes(out)


class EpicTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.home = self.tmp / "home"
        state = self.home / ".local/share/droiddeck-epic"
        data = self.home / ".local/share/com.epicgames.eglv2"
        runtime = self.tmp / "run"
        runtime.mkdir()
        patches = {
            "HOME": str(self.home),
            "STATE": str(state),
            "CURRENT": str(state / "current.json"),
            "APPS": str(state / "app"),
            "RUNTIMES": str(state / "runtime"),
            "LOGS": str(state / "logs"),
            "SHIMS": str(state / "bin"),
            "FEX_CONFIG": str(state / "fex"),
            "FEX_CACHE": str(self.home / ".cache/droiddeck-epic"),
            "DATA": str(data),
            "SETTINGS": str(data / "settings/app-settings.json"),
            "API_FILE": str(data / "api.json"),
            "AUTH_FILE": str(data / "auth" / "auth.json"),
            "PROTON_ROOT": str(data / "compat/runtime"),
            "INSTALLATIONS": str(data / "installations.jsonl"),
            "V1_MANIFESTS": str(self.home / ".local/share/Epic/EpicGamesLauncher/Data/Manifests"),
            "AUTO_SIGN_IN": str(state / "auto-sign-in"),
            "SIGNED_OUT": str(state / "signed-out"),
            "WEB_COOKIES": str(data / "cookies"),
        }
        for name, value in patches.items():
            patcher = mock.patch.dict(G, {name: value})
            patcher.start()
            self.addCleanup(patcher.stop)
        env = mock.patch.dict(os.environ, {"XDG_RUNTIME_DIR": str(runtime)})
        env.start()
        self.addCleanup(env.stop)
        self.state = state
        self.data = data


class PreloadTest(EpicTestCase):
    def test_launcher_runtime_preloads_the_webkit_input_fix(self):
        source = self.tmp / "libwebkitinput.so"
        source.write_bytes(b"scroll")
        files = self.tmp / "files"
        with mock.patch.dict(G, {"WEBKIT_INPUT_SOURCE": str(source)}):
            G["install_preload"](str(files))
        self.assertEqual((files / "lib/x86_64-linux-gnu/droiddeck/libwebkitinput.so").read_bytes(), b"scroll")
        self.assertEqual((files / "etc/ld.so.preload").read_text(), "/usr/lib/x86_64-linux-gnu/droiddeck/libwebkitinput.so\n")
        with mock.patch.dict(G, {"WEBKIT_INPUT_SOURCE": str(self.tmp / "missing.so")}):
            G["install_preload"](str(files))
        self.assertFalse((files / "lib/x86_64-linux-gnu/droiddeck/libwebkitinput.so").exists())
        self.assertEqual((files / "etc/ld.so.preload").read_text(), "")


class RuntimeExtractTest(EpicTestCase):
    def test_runtime_extract_rewrites_links_and_skips_i386(self):
        tarball = self.tmp / "rt.tar.gz"
        with tarfile.open(tarball, "w:gz") as archive:
            tar_add(archive, "metadata", b"[Runtime]\n")
            tar_add(archive, "files", kind="dir")
            tar_add(archive, "files/lib/x86_64-linux-gnu/libc.so.6", b"libc", mode=0o755)
            tar_add(archive, "files/lib/ld-linux-x86-64.so.2", link="/usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2")
            tar_add(archive, "files/etc/alias", link="/etc/real")
            tar_add(archive, "files/bin/rel", link="../lib/x86_64-linux-gnu/libc.so.6")
            tar_add(archive, "files/lib/i386-linux-gnu/libc.so.6", b"i386")
            tar_add(archive, "files/share/doc/readme", b"doc")
            tar_add(archive, "files/lib/hard", link="files/lib/x86_64-linux-gnu/libc.so.6", kind="hard")
            tar_add(archive, "../escape", b"x")
            tar_add(archive, "other/file", b"x")
        staging, count = G["extract_runtime"](str(tarball), str(self.tmp / "rt"))
        files = Path(staging) / "files"
        self.assertEqual(b"libc", (files / "lib/x86_64-linux-gnu/libc.so.6").read_bytes())
        self.assertTrue(os.access(files / "lib/x86_64-linux-gnu/libc.so.6", os.X_OK))
        self.assertEqual("x86_64-linux-gnu/ld-linux-x86-64.so.2", os.readlink(files / "lib/ld-linux-x86-64.so.2"))
        self.assertEqual("real", os.readlink(files / "etc/alias"))
        self.assertEqual("../lib/x86_64-linux-gnu/libc.so.6", os.readlink(files / "bin/rel"))
        self.assertEqual(".", os.readlink(files / "usr"))
        self.assertEqual(b"libc", (files / "lib/hard").read_bytes())
        self.assertFalse((files / "lib/i386-linux-gnu").exists())
        self.assertFalse((files / "share/doc").exists())
        self.assertFalse((self.tmp / "escape").exists())
        self.assertFalse((Path(staging) / "other").exists())
        self.assertTrue((Path(staging) / "metadata").is_file())
        self.assertEqual(6, count)

    def test_runtime_without_libc_is_refused(self):
        tarball = self.tmp / "rt.tar.gz"
        with tarfile.open(tarball, "w:gz") as archive:
            tar_add(archive, "files/bin/sh", b"sh")
        with self.assertRaises(G["Failure"]):
            G["extract_runtime"](str(tarball), str(self.tmp / "rt"))
        self.assertFalse((self.tmp / "rt.staging").exists())

    def test_gles_comes_out_of_the_deb(self):
        data = io.BytesIO()
        with tarfile.open(fileobj=data, mode="w:xz") as archive:
            tar_add(archive, "./usr/lib/x86_64-linux-gnu/libGLESv2.so.2.1.0", b"gles")
            tar_add(archive, "./usr/lib/x86_64-linux-gnu/libGLESv2.so.2", link="libGLESv2.so.2.1.0")
            tar_add(archive, "./usr/share/doc/libgles2/copyright", b"c")
        deb = self.tmp / "gles.deb"
        deb.write_bytes(ar([("debian-binary", b"2.0\n"), ("control.tar.xz", b"x"), ("data.tar.xz", data.getvalue())]))
        files = self.tmp / "files"
        G["install_gles"](str(deb), str(files))
        lib = files / "lib/x86_64-linux-gnu"
        self.assertEqual(b"gles", (lib / "libGLESv2.so.2").read_bytes())
        self.assertEqual("libGLESv2.so.2.1.0", os.readlink(lib / "libGLESv2.so.2"))
        self.assertFalse((files / "share").exists())
        bad = self.tmp / "bad.deb"
        bad.write_bytes(b"not an archive")
        with self.assertRaises(G["Failure"]):
            G["install_gles"](str(bad), str(files))

    def test_install_marks_ready_and_prunes_old_versions(self):
        tarball = self.tmp / "rt.tar.gz"
        with tarfile.open(tarball, "w:gz") as archive:
            tar_add(archive, "files/lib/x86_64-linux-gnu/libc.so.6", b"libc")
        data = io.BytesIO()
        with tarfile.open(fileobj=data, mode="w:gz") as archive:
            tar_add(archive, "./usr/lib/x86_64-linux-gnu/libGLESv2.so.2", b"gles")
        deb = self.tmp / "gles.deb"
        deb.write_bytes(ar([("data.tar.gz", data.getvalue())]))
        old = self.state / "app/0.1"
        old.mkdir(parents=True)

        def extract_app(image, dest):
            os.makedirs(os.path.join(dest, "usr/bin"))
            Path(dest, "AppRun").write_text("run")
            Path(dest, "usr/bin/EpicGamesLauncher").write_text("bin")

        with mock.patch.dict(G, {"extract_app": extract_app, "fex_tool": lambda request=False: {"bin": "/fex", "server": "/s"}}), \
                mock.patch("sys.stdout", new_callable=io.StringIO) as out:
            image = self.tmp / "image"
            image.write_bytes(b"x")
            code = G["install"]({"app": str(image), "app-version": "0.95.2+g1", "runtime": str(tarball),
                                 "runtime-version": "4.0.1", "gles": str(deb)})
        self.assertEqual(0, code)
        report = json.loads(out.getvalue())
        self.assertTrue(report["installed"])
        self.assertEqual({"app": "0.95.2+g1", "runtime": "4.0.1"}, json.loads((self.state / "current.json").read_text()))
        self.assertFalse(old.exists())
        files = self.state / "runtime/4.0.1/files"
        self.assertEqual("", (files / "etc/ld.so.preload").read_text())
        self.assertTrue((self.state / "runtime/4.0.1/.droiddeck-ready").is_file())
        self.assertTrue((self.state / "app/0.95.2+g1/.droiddeck-ready").is_file())


class ArgumentsTest(EpicTestCase):
    def test_client_arguments_win_and_paths_become_windows_paths(self):
        ovt = self.tmp / "ovt"
        ovt.mkdir()
        client = ["-AUTH_LOGIN=unused", "-AUTH_PASSWORD=abc", "-AUTH_TYPE=exchangecode", "-epicapp=Fig",
                  "-epicovt=%s/token.ovt" % ovt, '-epicusername="Some One"', "-nosplash"]
        own = ["-EpicPortal", "-epicapp=Fig", "-nosplash", "-windowed"]
        merged = G["merge_args"](client, own)
        self.assertIn('-epicovt="Z:%s\\token.ovt"' % str(ovt).replace("/", "\\"), merged)
        self.assertEqual(1, merged.count("-nosplash"))
        self.assertNotIn("-EpicPortal", merged)
        self.assertEqual("-windowed", merged[-1])
        self.assertEqual("-AUTH_PASSWORD=abc", merged[1])

    def test_command_line_escapes_cmd_specials_outside_quotes(self):
        line = G["command_line"](["-a=b&c", '-n="A & B"', "two words", "-p=50%", "^x", "-z=a|b>c<d"])
        self.assertEqual('-a=b^&c -n="A & B" "two words" -p=50% ^^x -z=a^|b^>c^<d', line)

    def test_a_stray_quote_cannot_unescape_the_rest_of_the_line(self):
        self.assertEqual('"-a=x &y" b^&c -n=A^&B', G["command_line"](['-a="x &y', "b&c", '-n=A&B"']))

    def test_args_file_is_written_with_crlf(self):
        folder = self.tmp / "game"
        folder.mkdir()
        line = G["write_args"](str(folder), {"args": ["-x"]}, ["-epicapp=Fig"])
        self.assertEqual("-epicapp=Fig -x", line)
        self.assertEqual(b"-epicapp=Fig -x\r\n", (folder / ".droiddeck-epic-args").read_bytes())
        self.assertEqual(0o600, (folder / ".droiddeck-epic-args").stat().st_mode & 0o777)
        self.assertEqual([".droiddeck-epic-args"], os.listdir(folder))

    def test_paths_convert_both_ways(self):
        self.assertEqual("Z:\\root\\a b\\c.exe", G["windows_path"]("/root/a b/c.exe"))
        self.assertEqual("/root/a b/c.exe", G["native_path"]("Z:\\root\\a b\\c.exe"))
        self.assertEqual("/root/x", G["native_path"]("z:\\root\\x"))

    def test_game_launches_are_told_apart_from_helpers(self):
        game = {"args": ["-EpicPortal", "-epicapp=Fig"]}
        self.assertTrue(G["is_game"](game))
        self.assertTrue(G["is_game"](game, "fig"))
        self.assertFalse(G["is_game"](game, "Other"))
        self.assertFalse(G["is_game"]({"args": ["/quiet"]}))

    def test_deep_link_quotes_the_app_id(self):
        self.assertEqual("com.epicgames.launcher://apps/ns%3Aitem%3AFig?action=launch&silent=true",
                         G["deep_link"]("ns", "item", "Fig"))


class SignInTest(EpicTestCase):
    AUTH = ("https://www.epicgames.com/id/authorize?client_id=c85f&response_type=code"
            "&redirect_uri=http%3A%2F%2F127.0.0.1%3A41234%2Flauncher%2Fauthorized&state=s")

    def test_only_epic_loopback_sign_ins_count(self):
        self.assertTrue(G["sign_in_url"](self.AUTH))
        self.assertFalse(G["sign_in_url"](self.AUTH.replace("www.epicgames.com", "example.com")))
        self.assertFalse(G["sign_in_url"](self.AUTH.replace("127.0.0.1%3A41234", "example.com%3A80")))
        self.assertFalse(G["sign_in_url"](self.AUTH.replace("%3A41234", "")))
        self.assertFalse(G["sign_in_url"]("https://store.epicgames.com/p/fig"))

    def test_xdg_open_hands_sign_in_and_links_to_the_app(self):
        launch = self.tmp / "launch"
        with mock.patch.dict(os.environ, {"BL_LAUNCH_DIR": str(launch)}):
            self.assertEqual(0, G["shim_open"]([self.AUTH]))
            self.assertEqual(0, G["shim_open"](["https://store.epicgames.com/p/fig"]))
            self.assertEqual(1, G["shim_open"](["mailto:x@example.com"]))
            self.assertEqual(0, G["shim_open"](["steam://rungameid/2379304779"]))
            self.assertEqual(1, G["shim_open"](["steam://rungameid/0"]))
        requests = [json.loads(p.read_text()) for p in (launch / "stores/req").glob("*.json")]
        self.assertEqual(["epic-sign-in", "open-url", "steam-run"], sorted(r["op"] for r in requests))
        self.assertEqual(["2379304779"], [r["id"] for r in requests if r["op"] == "steam-run"])

    def test_the_xdg_open_command_forwards_steam_links(self):
        launch = self.tmp / "cli-launch"
        env = dict(os.environ, HOME=str(self.home), BL_LAUNCH_DIR=str(launch))
        def xdg_open(url):
            return subprocess.run([sys.executable, str(BIN / "droiddeck-epic"), "xdg-open", url], env=env,
                                  stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30).returncode
        self.assertEqual(0, xdg_open("steam://rungameid/2379304779"))
        self.assertEqual(0, xdg_open("steam://run/606150"))
        self.assertEqual(1, xdg_open("steam://rungameid/0"))
        requests = [json.loads(p.read_text()) for p in (launch / "stores/req").glob("*.json")]
        self.assertEqual(["steam-run", "steam-run"], [r["op"] for r in requests])
        self.assertEqual(["2379304779", "606150"], sorted(r["id"] for r in requests))


class SignInHandoffTest(EpicTestCase):
    def requests(self, launch):
        return [json.loads(p.read_text()) for p in sorted((launch / "stores/req").glob("*.json"))]

    def test_the_app_answers_whether_it_has_an_epic_account(self):
        asked = []
        for reply, ready in (({"ok": True}, True), ({"ok": False, "reason": "no-account"}, False), (None, False)):
            with mock.patch.dict(G, {"ask_app": lambda request, timeout, reply=reply: asked.append(request) or reply}):
                self.assertEqual(ready, G["handoff_ready"]())
        self.assertEqual([{"op": "epic-client-handoff"}] * 3, asked)

    def test_carrying_the_sign_in_needs_a_signed_out_launcher_and_an_account(self):
        marker = Path(G["AUTO_SIGN_IN"])
        marker.parent.mkdir(parents=True)
        marker.write_text("1\n")
        with mock.patch.dict(G, {"signed_in": lambda: True, "handoff_ready": lambda: True}):
            self.assertFalse(G["carry_sign_in"]())
        self.assertFalse(marker.exists())
        with mock.patch.dict(G, {"signed_in": lambda: False, "handoff_ready": lambda: False}):
            self.assertFalse(G["carry_sign_in"]())
        with mock.patch.dict(G, {"signed_in": lambda: False, "handoff_ready": lambda: True}):
            self.assertTrue(G["carry_sign_in"]())

    def test_a_fresh_marker_makes_the_sign_in_automatic(self):
        launch = self.tmp / "launch"
        marker = Path(G["AUTO_SIGN_IN"])
        with mock.patch.dict(os.environ, {"BL_LAUNCH_DIR": str(launch)}):
            G["shim_open"]([SignInTest.AUTH])
            marker.parent.mkdir(parents=True)
            marker.write_text("1\n")
            G["shim_open"]([SignInTest.AUTH])
            old = time.time() - G["AUTO_SIGN_IN_FRESH"] - 5
            os.utime(marker, (old, old))
            G["shim_open"]([SignInTest.AUTH])
        self.assertEqual([None, True, None], [r.get("auto") for r in self.requests(launch)])

    def test_the_accessibility_bus_is_only_opened_for_a_carried_sign_in(self):
        base = {"NO_AT_BRIDGE": "1", "GTK_A11Y": "none"}
        with mock.patch.dict(G, {"KEPT_ENV": ("NO_AT_BRIDGE", "GTK_A11Y"), "CLIENT_ENV": {}, "fex_environment": lambda fex, files, base: {}}):
            plain = G["client_environment"](base, None, None, ":0", "bus", "broker")
            carried = G["client_environment"](base, None, None, ":0", "bus", "broker", "unix:abstract=a11y")
        self.assertEqual(("1", "none", None), (plain.get("NO_AT_BRIDGE"), plain.get("GTK_A11Y"), plain.get("AT_SPI_BUS_ADDRESS")))
        self.assertEqual((None, None, "unix:abstract=a11y"),
                         (carried.get("NO_AT_BRIDGE"), carried.get("GTK_A11Y"), carried.get("AT_SPI_BUS_ADDRESS")))

    def session(self):
        session = G["Session"]("visible")
        session.carried = True
        session.a11y = "unix:abstract=a11y"
        return session

    def test_the_welcome_screen_is_passed_then_sign_in_is_pressed_once(self):
        pressed = []
        screens = [{"Get started": (":1.2", "/a")}, {"Sign in": (":1.2", "/b")}, {"Sign in": (":1.2", "/b")}]
        session = self.session()
        with mock.patch.dict(G, {
            "signed_in": lambda: False,
            "a11y_buttons": lambda address: screens.pop(0),
            "a11y_press": lambda address, target: pressed.append(target) or True,
        }):
            for now in (10.0, 11.0, 12.5, 15.0):
                session.carry_on(now)
        self.assertEqual([("/a"), ("/b")], [target[1] for target in pressed])
        self.assertTrue(session.sign_in_pressed)
        self.assertTrue(Path(G["AUTO_SIGN_IN"]).exists())
        self.assertEqual(1, len(screens))

    def test_the_welcome_screen_is_pressed_a_limited_number_of_times(self):
        pressed = []
        session = self.session()
        with mock.patch.dict(G, {
            "signed_in": lambda: False,
            "a11y_buttons": lambda address: {"Get started": (":1.2", "/a")},
            "a11y_press": lambda address, target: pressed.append(target) or True,
        }):
            for step in range(10):
                session.carry_on(step * 3.0)
        self.assertEqual(G["FIRST_RUN_PRESSES"], len(pressed))

    def test_signing_in_ends_the_carry_and_drops_the_marker(self):
        marker = Path(G["AUTO_SIGN_IN"])
        marker.parent.mkdir(parents=True)
        marker.write_text("1\n")
        session = self.session()
        with mock.patch.dict(G, {"signed_in": lambda: True}):
            session.carry_on(1.0)
        self.assertFalse(session.carried)
        self.assertFalse(marker.exists())

    def test_no_accessibility_bus_means_no_buttons(self):
        self.assertEqual({}, G["a11y_buttons"](None))
        with mock.patch.dict(G, {"a11y_connect": lambda address: None}):
            self.assertEqual({}, G["a11y_buttons"]("unix:abstract=a11y"))
            self.assertFalse(G["a11y_press"]("unix:abstract=a11y", (":1.2", "/a")))


class AuthTest(EpicTestCase):
    def write_auth(self, value):
        path = Path(G["AUTH_FILE"])
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value))

    def test_only_a_prod_refresh_token_counts_as_signed_in(self):
        self.assertFalse(G["signed_in"]())
        for value in ({"foo": 1}, {"prod": "x"}, {"prod": {"refresh_token": ""}}, {"gamedev": {"refresh_token": "t"}}):
            self.write_auth(value)
            self.assertFalse(G["signed_in"](), value)
        self.write_auth({"prod": {"refresh_token": "t"}})
        self.assertTrue(G["signed_in"]())


class SignOutTest(EpicTestCase):
    def requests(self, launch):
        return [json.loads(p.read_text()) for p in sorted((launch / "stores/req").glob("*.json"))]

    def follow(self, states, times):
        launch = self.tmp / "launch"
        session = G["Session"]("visible")
        states = iter(states)
        with mock.patch.dict(os.environ, {"BL_LAUNCH_DIR": str(launch)}), \
             mock.patch.dict(G, {"signed_in": lambda: next(states)}):
            for now in times:
                session.follow_sign_out(now)
        return self.requests(launch) if (launch / "stores/req").is_dir() else []

    def test_signing_the_launcher_out_signs_droiddeck_out_after_the_grace(self):
        grace = G["SIGN_OUT_GRACE"]
        posted = self.follow([True, False, False, False, False], [0.0, 1.0, 1.0 + grace / 2, 1.0 + grace, 9.0 + grace])
        self.assertEqual([{"op": "epic-signed-out"}], posted)

    def test_a_short_token_gap_is_not_a_sign_out(self):
        grace = G["SIGN_OUT_GRACE"]
        self.assertEqual([], self.follow([True, False, True, False], [0.0, 1.0, 1.0 + grace / 2, 1.0 + grace]))

    def test_a_launcher_that_was_never_signed_in_posts_nothing(self):
        self.assertEqual([], self.follow([False] * 3, [0.0, 10.0, 20.0]))

    def write_signed_in(self):
        auth = Path(G["AUTH_FILE"])
        auth.parent.mkdir(parents=True, exist_ok=True)
        auth.write_text(json.dumps({"prod": {"refresh_token": "t"}}))
        Path(G["WEB_COOKIES"]).write_text("c")
        marker = Path(G["SIGNED_OUT"])
        marker.parent.mkdir(parents=True, exist_ok=True)
        marker.write_text("1\n")
        return auth, marker

    def test_droiddecks_sign_out_drops_the_launchers_sign_in_once(self):
        auth, marker = self.write_signed_in()
        self.assertTrue(G["forget_sign_in"]())
        self.assertFalse(auth.exists() or marker.exists() or Path(G["WEB_COOKIES"]).exists())
        auth.write_text(json.dumps({"prod": {"refresh_token": "t"}}))
        self.assertFalse(G["forget_sign_in"]())
        self.assertTrue(auth.exists())

    def test_the_open_launcher_restarts_signed_out(self):
        auth, marker = self.write_signed_in()
        session = G["Session"]("visible")
        session.broker = mock.Mock(closing=False, game=None, owner=None)
        session.client = mock.Mock(poll=mock.Mock(return_value=None))
        session.was_signed_in = True
        ended = []
        restarted = mock.Mock(poll=mock.Mock(return_value=0), returncode=0)
        with mock.patch.object(session, "end_client", side_effect=lambda: ended.append(auth.exists())), \
             mock.patch.object(session, "start_client", side_effect=lambda: setattr(session, "client", restarted)), \
             mock.patch.dict(G, {"signed_in": lambda: False}):
            self.assertEqual(0, session.watch())
        self.assertEqual([True], ended)
        self.assertFalse(auth.exists() or marker.exists())
        self.assertIs(restarted, session.client)
        self.assertFalse(session.was_signed_in)

    def test_the_open_launcher_waits_for_its_game_before_restarting(self):
        self.write_signed_in()
        session = G["Session"]("visible")
        session.broker = mock.Mock(closing=False, game=object(), owner=None)
        session.client = mock.Mock(poll=mock.Mock(side_effect=[None, None, 0]), returncode=0)
        with mock.patch.object(session, "end_client") as end, mock.patch.object(session, "start_client") as start, \
             mock.patch.dict(G, {"signed_in": lambda: True}):
            self.assertEqual(0, session.watch())
        end.assert_not_called()
        start.assert_not_called()
        self.assertTrue(Path(G["SIGNED_OUT"]).exists())

    def test_the_hidden_launcher_closes_and_forgets_the_sign_in(self):
        auth, marker = self.write_signed_in()
        session = G["Session"]("hidden")
        session.broker = mock.Mock(closing=False, game=None, owner=None, running=[])
        session.client = mock.Mock(poll=mock.Mock(return_value=None))
        with mock.patch.object(session, "end_client") as end:
            self.assertEqual(0, session.watch())
            self.assertTrue(auth.exists())
            session.stop()
        end.assert_called_once()
        self.assertFalse(auth.exists() or marker.exists())


class DisplayTest(EpicTestCase):
    def test_hidden_display_comes_from_a_headless_gamescope(self):
        session = G["Session"]("hidden")
        session.rundir = str(self.tmp / "display-run")
        os.makedirs(session.rundir)
        spawned = []

        def spawn(command, env, log=None, group=False):
            spawned.append((command, env))
            marker = command[-1]
            Path(marker).write_text(":2")

        session.spawn = spawn
        display = session.start_display({"DISPLAY": ":0", "WAYLAND_DISPLAY": "gamescope-0", "HOME": "/root"}, "unix:path=/b")
        self.assertEqual(":2", display)
        command, env = spawned[0]
        self.assertEqual(["gamescope", "--backend", "headless"], command[:3])
        self.assertNotIn("DISPLAY", env)
        self.assertNotIn("WAYLAND_DISPLAY", env)
        self.assertEqual("unix:path=/b", env["DBUS_SESSION_BUS_ADDRESS"])


class InstallationsTest(EpicTestCase):
    def add_game(self, guid="G1", app="Fig", store="epic", extra=None):
        folder = self.tmp / ("game-" + app)
        folder.mkdir()
        (folder / ".droiddeck-store.json").write_text(json.dumps({"store": store, "id": app, "extra": extra or {}}))
        manifests = Path(G["V1_MANIFESTS"])
        manifests.mkdir(parents=True, exist_ok=True)
        (manifests / (guid + ".item")).write_text(json.dumps({
            "InstallationGuid": guid, "InstallLocation": str(folder), "CatalogNamespace": "ns", "CatalogItemId": "it",
            "AppName": app, "AppVersionString": "v1", "InstallTags": ["en"], "SidecarConfigRevision": 1,
            "SidecarDeploymentId": "dep", "LaunchExecutable": "Fig.exe", "bIsIncompleteInstall": False,
        }))
        return folder

    def lines(self):
        return [json.loads(line) for line in Path(G["INSTALLATIONS"]).read_text().splitlines()]

    def test_missing_droiddeck_install_is_registered_as_windows(self):
        folder = self.add_game()
        self.assertTrue(G["sync_installations"]())
        header, entry = self.lines()
        self.assertEqual(1, header["entries"])
        self.assertEqual("Windows", entry["platform"])
        self.assertEqual(str(folder), entry["dir"])
        self.assertEqual(["", "en"], entry["tags"])
        self.assertEqual({"revision": 1, "deploymentId": "dep"}, entry["sidecar"])
        self.assertFalse(G["sync_installations"]())

    def test_imported_linux_record_is_switched_to_windows_and_others_kept(self):
        folder = self.add_game()
        other = {"installationId": "X", "dir": "/elsewhere", "platform": "Linux", "artifactId": "Other"}
        mine = {"installationId": "G1", "dir": str(folder), "platform": "Linux", "artifactId": "Fig", "sidecar": {"revision": 0}}
        Path(G["INSTALLATIONS"]).parent.mkdir(parents=True, exist_ok=True)
        Path(G["INSTALLATIONS"]).write_text("\n".join(json.dumps(v) for v in (
            {"version": 1, "writtenAt": "t", "entries": 3}, mine, dict(mine, sidecar={"revision": 1}), other)) + "\n")
        self.assertTrue(G["sync_installations"]())
        header, first, second, kept = self.lines()
        self.assertEqual(3, header["entries"])
        self.assertEqual(["Windows", "Windows"], [first["platform"], second["platform"]])
        self.assertEqual({"revision": 1}, second["sidecar"])
        self.assertEqual(other, kept)

    def write_records(self, *records):
        Path(G["INSTALLATIONS"]).parent.mkdir(parents=True, exist_ok=True)
        Path(G["INSTALLATIONS"]).write_text("\n".join(json.dumps(v) for v in (
            {"version": 1, "writtenAt": "t", "entries": len(records)},) + records) + "\n")

    def test_imported_record_is_switched_after_the_launcher_consumed_the_item(self):
        folder = self.add_game()
        shutil.rmtree(G["V1_MANIFESTS"])
        self.write_records({"installationId": "G1", "dir": str(folder), "platform": "Linux", "artifactId": "Fig"})
        self.assertTrue(G["sync_installations"]())
        self.assertEqual("Windows", self.lines()[1]["platform"])
        self.assertFalse(G["sync_installations"]())

    def test_games_the_launcher_installed_keep_their_platform(self):
        folder = self.add_game(extra={"clientManaged": "true"})
        record = {"installationId": "G1", "dir": str(folder), "platform": "Linux", "artifactId": "Fig"}
        self.write_records(record)
        self.assertFalse(G["sync_installations"]())
        self.assertEqual(record, self.lines()[1])

    def test_a_moved_game_points_the_launcher_at_its_new_folder(self):
        folder = self.add_game()
        old = {"installationId": "G1", "dir": "/gone/Fig", "platform": "Windows", "artifactId": "Fig"}
        self.write_records(old, dict(old, version="v2"))
        self.assertTrue(G["sync_installations"]())
        header, first, second = self.lines()
        self.assertEqual(2, header["entries"])
        self.assertEqual([str(folder), str(folder)], [first["dir"], second["dir"]])
        self.assertEqual("v2", second["version"])
        self.assertFalse(G["sync_installations"]())

    def test_an_imported_record_under_another_id_follows_the_move_and_platform(self):
        folder = self.add_game()
        self.write_records({"installationId": "L1", "dir": "/gone/Fig", "platform": "Linux", "artifactId": "Fig"})
        self.assertTrue(G["sync_installations"]())
        entry = self.lines()[1]
        self.assertEqual(("L1", str(folder), "Windows"), (entry["installationId"], entry["dir"], entry["platform"]))

    def test_a_record_whose_folder_still_exists_is_not_moved(self):
        folder = self.add_game()
        other = self.tmp / "copy"
        other.mkdir()
        (other / ".droiddeck-store.json").write_text(json.dumps({"store": "epic", "id": "Fig"}))
        record = {"installationId": "G1", "dir": str(other), "platform": "Windows", "artifactId": "Fig"}
        self.write_records(record)
        self.assertFalse(G["sync_installations"]())
        self.assertNotEqual(str(folder), self.lines()[1]["dir"])

    def test_a_launcher_install_of_the_same_game_is_not_redirected(self):
        self.add_game()
        own = self.tmp / "launcher-Fig"
        own.mkdir()
        record = {"installationId": "L1", "dir": str(own), "platform": "Windows", "artifactId": "Fig"}
        self.write_records(record)
        G["sync_installations"]()
        self.assertEqual(record, self.lines()[1])

    def test_games_not_installed_by_droiddeck_are_left_alone(self):
        self.add_game(store="gog")
        self.assertFalse(G["sync_installations"]())
        self.assertFalse(Path(G["INSTALLATIONS"]).exists())


class SettingsTest(EpicTestCase):
    def test_settings_are_seeded_without_losing_the_rest(self):
        settings = self.data / "settings/app-settings.json"
        settings.parent.mkdir(parents=True)
        settings.write_text(json.dumps({"locale": "de", "cloudSavesEnabled": True}))
        self.assertTrue(G["seed_settings"]())
        saved = json.loads(settings.read_text())
        self.assertEqual("de", saved["locale"])
        self.assertFalse(saved["cloudSavesEnabled"])
        self.assertFalse(saved["minimizeToTray"])
        self.assertFalse(saved["automaticDownloadsEnabled"])
        self.assertFalse(G["seed_settings"]())

    def test_proton_shim_replaces_every_ge_proton(self):
        real = self.data / "compat/runtime/GE-Proton10-40"
        real.mkdir(parents=True)
        (real / "proton").write_text("#!/usr/bin/env python3\n")
        G["install_shims"]()
        for name in ("GE-Proton10-34", "GE-Proton10-40"):
            shim = self.data / "compat/runtime" / name / "proton"
            self.assertIn("droiddeck-epic", shim.read_text())
            self.assertIn(" proton ", shim.read_text())
            self.assertTrue(os.access(shim, os.X_OK))
        self.assertIn("xdg-open", (self.state / "bin/xdg-open").read_text())


class BrokerTest(EpicTestCase):
    def broker(self, mode="hidden"):
        path = os.path.join(G["run_dir"](), "broker.sock")
        broker = G["Broker"](path, mode, dict(os.environ))
        self.addCleanup(broker.shutdown)
        return broker, path

    def shim(self, path, args, result):
        def run():
            with mock.patch.dict(os.environ, {"DROIDDECK_EPIC_BROKER": path}):
                result.append(G["shim_proton"](["waitforexitandrun", "/games/Fig/Fig.exe"] + args))
        thread = threading.Thread(target=run, daemon=True)
        thread.start()
        return thread

    def test_stale_broker_socket_is_removed_before_bind(self):
        path = os.path.join(G["run_dir"](), "broker.sock")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        stale = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        stale.bind(path)
        stale.close()
        broker, path = self.broker()
        self.assertEqual("hidden", G["ask_broker"]({"op": "status"}, path=path)["mode"])

    def test_hidden_launch_hands_args_and_waits_for_the_game(self):
        broker, path = self.broker()
        conn = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        conn.connect(path)
        G["send"](conn, {"op": "await", "app": "Fig", "timeout": 10})
        time.sleep(0.2)
        helper = []
        self.shim(path, ["/quiet"], helper).join(5)
        self.assertEqual([0], helper)
        result = []
        thread = self.shim(path, ["-epicapp=Fig", "-AUTH_PASSWORD=abc"], result)
        reply = G["receive"](conn, 10)
        conn.close()
        self.assertTrue(reply["ok"])
        self.assertEqual(["-epicapp=Fig", "-AUTH_PASSWORD=abc"], reply["args"])
        time.sleep(0.3)
        self.assertEqual([], result)
        self.assertEqual({"ok": True}, G["ask_broker"]({"op": "exited", "code": 3}, path=path))
        thread.join(5)
        self.assertEqual([3], result)
        self.assertIsNotNone(broker.exited_at)

    def test_await_times_out_without_a_launch(self):
        broker, path = self.broker()
        conn = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        conn.connect(path)
        G["send"](conn, {"op": "await", "app": "Fig", "timeout": 0.5})
        self.assertEqual({"ok": False, "reason": "timeout"}, G["receive"](conn, 5))
        conn.close()

    def test_broker_survives_malformed_requests(self):
        broker, path = self.broker()
        for raw in (b"[1, 2]\n", b'{"op": "exited", "code": [1]}\n', b'{"op": "exited", "code": "x"}\n',
                    b'{"op": "launch", "args": 5}\n'):
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as conn:
                conn.connect(path)
                conn.sendall(raw)
                conn.settimeout(5)
                conn.recv(4096)
        self.assertEqual("hidden", G["ask_broker"]({"op": "status"}, path=path)["mode"])

    def test_shim_survives_a_malformed_reply(self):
        path = os.path.join(G["run_dir"](), "fake.sock")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.addCleanup(server.close)
        server.bind(path)
        server.listen(4)
        replies = [b"[1]\n", b'{"code": "x"}\n']

        def answer():
            for reply in replies:
                conn, _ = server.accept()
                with conn:
                    G["receive"](conn, 5)
                    conn.sendall(reply)

        threading.Thread(target=answer, daemon=True).start()
        with mock.patch.dict(os.environ, {"DROIDDECK_EPIC_BROKER": path}):
            for _ in replies:
                self.assertEqual(1, G["shim_proton"](["waitforexitandrun", "/x.exe", "-epicapp=Fig"]))

    def test_shim_without_broker_fails(self):
        with mock.patch.dict(os.environ, {"DROIDDECK_EPIC_BROKER": str(self.tmp / "missing.sock")}):
            self.assertEqual(1, G["shim_proton"](["waitforexitandrun", "/x.exe", "-epicapp=Fig"]))
        self.assertEqual(0, G["shim_proton"](["getcompatpath", "/x"]))

    def test_visible_mode_runs_store_games_through_their_launcher(self):
        folder = self.tmp / "Fig"
        folder.mkdir()
        (folder / ".droiddeck-store.json").write_text(json.dumps({"store": "epic", "id": "Fig", "args": ["-x"]}))
        (folder / ".droiddeck-launch.bat").write_text('if exist "%~dp0.droiddeck-epic-args" goto client\r\n')
        calls = []

        def popen(command, env=None, cwd=None, start_new_session=False):
            calls.append((command, env.get("DROIDDECK_EPIC_HANDLED"), start_new_session))
            return mock.Mock(wait=mock.Mock(return_value=7))

        running = []
        with mock.patch.object(subprocess, "Popen", popen):
            code = G["run_game"]({"exe": str(folder / "Fig.exe"), "args": ["-epicapp=Fig"]}, {"PATH": "/usr/bin"}, running)
        self.assertEqual(7, code)
        self.assertEqual(([G["PROTON_RUN"], str(folder / ".droiddeck-launch.bat")], "1", True), calls[0])
        self.assertEqual([], running)
        self.assertEqual(b"-epicapp=Fig -x\r\n", (folder / ".droiddeck-epic-args").read_bytes())

    def test_visible_games_are_marked_as_playing_while_they_run(self):
        broker, path = self.broker("visible")
        launch = self.tmp / "launch"
        marker = launch / G["PLAYING"]
        seen = []

        def run_game(request, env, running=None):
            seen.append(marker.is_file())
            return 5

        result = []
        with mock.patch.dict(os.environ, {"BL_LAUNCH_DIR": str(launch)}), mock.patch.dict(G, {"run_game": run_game}):
            self.shim(path, ["-epicapp=Fig"], result).join(10)
        self.assertEqual([True], seen)
        self.assertEqual([5], result)
        self.assertFalse(marker.exists())

    def steam_takes(self, path, app, replies):
        def take():
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as conn:
                conn.connect(path)
                G["send"](conn, {"op": "await", "app": app, "timeout": 10})
                replies.append(G["receive"](conn, 10))
            G["ask_broker"]({"op": "exited", "code": 6}, path=path)
        threading.Thread(target=take, daemon=True).start()

    def test_a_game_started_in_the_open_launcher_goes_through_steam(self):
        broker, path = self.broker("visible")
        asked, ran, result, taken = [], [], [], []

        def ask_app(request, timeout):
            asked.append(request)
            self.steam_takes(path, "Fig", taken)
            return {"ok": True}

        with mock.patch.dict(G, {
            "find_store_game": lambda exe: (str(self.tmp), {"store": "epic", "id": "Fig"}),
            "ask_app": ask_app,
            "run_game": lambda *a, **k: ran.append(a) or 3,
        }):
            self.shim(path, ["-epicapp=Fig", "-AUTH_PASSWORD=x"], result).join(10)
        self.assertEqual([{"op": "epic-play-in-steam", "id": "Fig"}], asked)
        self.assertEqual([], ran)
        self.assertEqual([6], result)
        self.assertEqual(["-epicapp=Fig", "-AUTH_PASSWORD=x"], taken[0]["args"])
        self.assertEqual([], broker.launches)

    def test_a_game_steam_never_takes_is_dropped_after_the_wait(self):
        broker, path = self.broker("visible")
        result = []
        with mock.patch.dict(G, {
            "find_store_game": lambda exe: (str(self.tmp), {"store": "epic", "id": "Fig"}),
            "ask_app": lambda request, timeout: {"ok": True},
            "run_game": lambda *a, **k: 3,
            "STEAM_CLAIM_WAIT": 0.3,
        }):
            self.shim(path, ["-epicapp=Fig"], result).join(10)
        self.assertEqual([0], result)
        self.assertEqual([], broker.launches)

    def test_a_launch_steam_already_waits_for_is_handed_straight_to_it(self):
        broker, path = self.broker("visible")
        taken, result, asked = [], [], []
        self.steam_takes(path, "Fig", taken)
        self.assertTrue(G["wait_for"](lambda: broker.awaiting.get("fig"), 5, 0.05))
        with mock.patch.dict(G, {"ask_app": lambda request, timeout: asked.append(request) or {"ok": True},
                                 "run_game": lambda *a, **k: 3}):
            self.shim(path, ["-epicapp=Fig"], result).join(10)
        self.assertEqual([], asked)
        self.assertEqual([6], result)
        self.assertEqual(["-epicapp=Fig"], taken[0]["args"])
        self.assertEqual({}, dict(broker.awaiting))

    def test_steam_takes_the_launch_the_open_launcher_already_started(self):
        broker, path = self.broker("visible")
        item = G["Launch"]({"exe": "/games/Fig/Fig.exe", "args": ["-epicapp=Fig", "-x"]})
        broker.launches.append(item)
        info = {"pid": os.getpid(), "broker": path, "mode": "visible", "bus": "unix:path=/nonexistent"}
        with mock.patch.dict(G, {"forward": mock.Mock(side_effect=AssertionError("no deep link"))}):
            reply = G["hand_off"](info, None, "Fig", "ns", "it", "1", time.monotonic() + 10)
        self.assertTrue(reply["ok"])
        self.assertEqual(["-epicapp=Fig", "-x"], reply["args"])
        self.assertTrue(item.claimed)
        self.assertIs(item, broker.game)

    def test_a_visible_launcher_that_started_nothing_gets_the_deep_link(self):
        broker, path = self.broker("visible")
        info = {"pid": os.getpid(), "broker": path, "mode": "visible", "bus": "unix:path=/x"}
        sent, result = [], []

        def forward(bus, url):
            sent.append(url)
            self.shim(path, ["-epicapp=Fig"], result)
            return True

        with mock.patch.dict(G, {"api": lambda: {"port": 1}, "installed": lambda current, app: True,
                                 "signed_in": lambda: True, "forward": forward,
                                 "ask_app": mock.Mock(side_effect=AssertionError("offered to Steam"))}):
            reply = G["hand_off"](info, None, "Fig", "ns", "it", "1", time.monotonic() + 10)
        self.assertTrue(reply["ok"])
        self.assertEqual(["-epicapp=Fig"], reply["args"])
        self.assertEqual(1, len(sent))
        G["ask_broker"]({"op": "exited", "code": 2}, path=path)
        self.assertTrue(G["wait_for"](lambda: result, 5, 0.05))
        self.assertEqual([2], result)

    def test_the_hidden_launcher_is_closed_before_the_visible_one_opens(self):
        broker, path = self.broker()
        proc = subprocess.Popen(["sleep", "30"])
        self.addCleanup(proc.kill)
        info = {"pid": proc.pid, "mode": "hidden", "broker": path}
        stops = []

        def ask(message, timeout=3.0, path=None):
            stops.append((message, path))
            proc.kill()
            proc.wait()
            return {"ok": True}

        with mock.patch.dict(G, {"read_json": lambda p: info, "ask_broker": ask}):
            G["retire_hidden"]()
        self.assertEqual([({"op": "stop"}, path)], stops)
        stops.clear()
        with mock.patch.dict(G, {"read_json": lambda p: dict(info, mode="visible", pid=os.getpid()), "ask_broker": ask}):
            G["retire_hidden"]()
        self.assertEqual([], stops)

    def test_the_open_mark_carries_the_steam_app_id(self):
        launch = self.tmp / "launch"
        with mock.patch.dict(os.environ, {"BL_LAUNCH_DIR": str(launch)}):
            with G["marked"](G["OPEN"], "3000000001"):
                self.assertEqual("3000000001\n", (launch / G["OPEN"]).read_text())
        self.assertFalse((launch / G["OPEN"]).exists())

    def test_the_steam_app_id_comes_from_the_reaper_above_the_launcher(self):
        reaper = subprocess.Popen(["sh", "-c", "sleep 30; :", "SteamLaunch", "AppId=3000000001", "--"])
        self.addCleanup(reaper.kill)
        children = Path("/proc/%d/task/%d/children" % (reaper.pid, reaper.pid))
        self.assertTrue(G["wait_for"](lambda: children.is_file() and children.read_text().split(), 5, 0.05))
        child = int(children.read_text().split()[0])
        self.assertEqual(reaper.pid, G["parent_pid"](child))
        self.assertEqual("3000000001", G["steam_app_id"](child))
        self.assertEqual("3000000001", G["steam_app_id"](reaper.pid))
        self.assertEqual("", G["steam_app_id"](os.getpid()))
        self.assertEqual(0, G["parent_pid"](self.dead_pid()))
        bad = subprocess.Popen(["sh", "-c", "sleep 30; :", "SteamLaunch", "AppId=x"])
        self.addCleanup(bad.kill)
        self.assertEqual("", G["steam_app_id"](bad.pid))

    def test_a_game_run_in_the_launcher_gets_steams_overlay_preload_back(self):
        folder = self.tmp / "Fig"
        folder.mkdir()
        envs = []

        def popen(command, env=None, cwd=None, start_new_session=False):
            envs.append(env)
            return mock.Mock(wait=mock.Mock(return_value=0))

        with mock.patch.object(subprocess, "Popen", popen):
            G["run_game"]({"exe": str(folder / "Fig.exe"), "args": []}, {"PATH": "/usr/bin", G["STEAM_PRELOAD"]: "/s/overlay.so"})
            G["run_game"]({"exe": str(folder / "Fig.exe"), "args": []}, {"PATH": "/usr/bin"})
        self.assertEqual("/s/overlay.so", envs[0]["LD_PRELOAD"])
        self.assertNotIn(G["STEAM_PRELOAD"], envs[0])
        self.assertNotIn("LD_PRELOAD", envs[1])

    def test_an_owner_gone_ends_a_game_the_open_launcher_handed_to_steam(self):
        broker, _ = self.broker("visible")
        session = G["Session"]("visible")
        session.broker = broker
        polls = iter([None, None, None, 0])
        session.client = mock.Mock(poll=mock.Mock(side_effect=lambda: next(polls, 0)), returncode=0)
        game = G["Launch"]({"args": ["-epicapp=Fig"]})
        broker.game = game
        broker.owner = self.dead_pid()
        result = []
        thread = threading.Thread(target=lambda: result.append(session.watch()), daemon=True)
        thread.start()
        thread.join(10)
        self.assertEqual([0], result)
        self.assertTrue(game.done.is_set())
        self.assertIsNone(broker.game)

    def test_a_game_steam_cannot_take_runs_in_the_open_launcher(self):
        broker, path = self.broker("visible")
        result = []
        for reply in ({"ok": False, "reason": "not-registered"}, None, [1]):
            with mock.patch.dict(G, {
                "find_store_game": lambda exe: (str(self.tmp), {"store": "epic", "id": "Fig"}),
                "ask_app": lambda request, timeout: reply,
                "run_game": lambda *a, **k: 4,
            }):
                self.shim(path, ["-epicapp=Fig"], result).join(10)
        self.assertEqual([4, 4, 4], result)

    def test_only_registered_epic_games_are_offered_to_steam(self):
        asked = []
        with mock.patch.dict(G, {"ask_app": lambda request, timeout: asked.append(request) or {"ok": True}}):
            with mock.patch.dict(G, {"find_store_game": lambda exe: None}):
                self.assertFalse(G["play_in_steam"]({"exe": "/games/Fig/Fig.exe"}))
            with mock.patch.dict(G, {"find_store_game": lambda exe: ("/games/Fig", {"store": "gog", "id": "1"})}):
                self.assertFalse(G["play_in_steam"]({"exe": "/games/Fig/Fig.exe"}))
        self.assertEqual([], asked)

    def test_a_game_the_launcher_just_installed_is_offered_to_steam_by_its_install_record(self):
        records = Path(G["INSTALLATIONS"])
        records.parent.mkdir(parents=True, exist_ok=True)
        lines = [{"version": 1, "entries": 3},
                 {"installationId": "A", "dir": "/games/Fig", "artifactId": "Fig"},
                 {"installationId": "B", "dir": "/games/Old", "artifactId": "Old"},
                 {"installationId": "B", "deleted": True}]
        records.write_text("\n".join(json.dumps(line) for line in lines) + "\n")
        asked = []
        with mock.patch.dict(G, {"find_store_game": lambda exe: None,
                                 "ask_app": lambda request, timeout: asked.append(request) or {"ok": True}}):
            self.assertTrue(G["play_in_steam"]({"exe": "/games/Fig/bin/Fig.exe"}))
            self.assertFalse(G["play_in_steam"]({"exe": "/games/Old/Old.exe"}))
            self.assertFalse(G["play_in_steam"]({"exe": "/games/Figment/Fig.exe"}))
        self.assertEqual([{"op": "epic-play-in-steam", "id": "Fig"}], asked)

    def test_the_playing_mark_goes_away_when_the_game_fails(self):
        launch = self.tmp / "launch"
        with mock.patch.dict(os.environ, {"BL_LAUNCH_DIR": str(launch)}):
            with self.assertRaises(OSError), G["playing"]():
                self.assertTrue((launch / G["PLAYING"]).is_file())
                raise OSError("boom")
        self.assertFalse((launch / G["PLAYING"]).exists())

    def test_a_stopped_session_leaves_no_playing_mark_for_the_next_launcher(self):
        launch = self.tmp / "launch"
        launch.mkdir()
        (launch / G["PLAYING"]).write_text("1\n")
        with mock.patch.dict(os.environ, {"BL_LAUNCH_DIR": str(launch)}):
            G["clear_playing"]()
            G["clear_playing"]()
        self.assertFalse((launch / G["PLAYING"]).exists())

    def test_a_stopped_visible_session_ends_the_game_and_its_mark(self):
        broker, _ = self.broker("visible")
        launch = self.tmp / "launch"
        launch.mkdir()
        (launch / G["PLAYING"]).write_text("1\n")
        game = subprocess.Popen(["sleep", "60"], start_new_session=True)
        self.addCleanup(game.kill)
        broker.running.append(game)
        session = G["Session"]("visible")
        session.broker = broker
        with mock.patch.dict(os.environ, {"BL_LAUNCH_DIR": str(launch)}):
            session.stop()
        self.assertIsNotNone(game.poll())
        self.assertFalse((launch / G["PLAYING"]).exists())

    def dead_pid(self):
        proc = subprocess.Popen(["true"])
        proc.wait()
        return proc.pid

    def test_hidden_session_closes_once_the_game_and_its_owner_are_gone(self):
        broker, _ = self.broker()
        session = G["Session"]("hidden")
        session.broker = broker
        session.client = mock.Mock(poll=mock.Mock(return_value=None))
        game = G["Launch"]({"args": ["-epicapp=Fig"]})
        broker.game = game
        broker.owner = self.dead_pid()
        result = []
        with mock.patch.dict(G, {"EXIT_GRACE": 0.3}), mock.patch.object(broker, "exited", wraps=broker.exited) as exited:
            thread = threading.Thread(target=lambda: result.append(session.watch()), daemon=True)
            thread.start()
            thread.join(5)
        self.assertEqual([0], result)
        self.assertTrue(game.done.is_set())
        self.assertIsNone(broker.game)
        self.assertEqual(1, exited.call_count)

    def test_exited_without_a_game_changes_nothing(self):
        broker, _ = self.broker()
        broker.exited(0)
        self.assertIsNone(broker.exited_at)

    def test_hand_off_stops_waiting_when_the_client_dies(self):
        broker, path = self.broker()
        info = {"pid": self.dead_pid(), "broker": path, "bus": "unix:path=/nonexistent"}
        started = time.monotonic()
        reply = G["hand_off"](info, None, "Fig", "ns", "it", "1", time.monotonic() + 30)
        self.assertEqual({"ok": False, "reason": "client-exited"}, reply)
        self.assertLess(time.monotonic() - started, 5)

    def test_forward_survives_a_hung_dbus_send(self):
        with mock.patch.object(subprocess, "call", side_effect=subprocess.TimeoutExpired("dbus-send", 15)):
            self.assertFalse(G["forward"]("unix:path=/x", "com.epicgames.launcher://apps/x"))


class LaunchTest(EpicTestCase):
    def test_launch_refuses_before_the_client_is_ready(self):
        folder = self.tmp / "Fig"
        folder.mkdir()
        (folder / ".droiddeck-store.json").write_text(json.dumps({"store": "epic", "id": "Fig"}))
        options = {"app": "Fig", "namespace": "ns", "item": "it", "folder": str(folder)}
        with mock.patch("sys.stdout", new_callable=io.StringIO) as out:
            self.assertEqual(1, G["launch"](dict(options)))
        self.assertEqual("old-launcher", json.loads(out.getvalue())["reason"])
        (folder / ".droiddeck-launch.bat").write_text("goto client .droiddeck-epic-args")
        with mock.patch("sys.stdout", new_callable=io.StringIO) as out, \
                mock.patch.dict(G, {"fex_tool": lambda request=False: None}):
            self.assertEqual(1, G["launch"](dict(options)))
        self.assertEqual("not-installed", json.loads(out.getvalue())["reason"])

    def test_a_start_error_is_reported_and_cleaned_up(self):
        stopped = []
        with mock.patch.object(G["Session"], "run", side_effect=OSError("no dbus-daemon")), \
                mock.patch.object(signal, "signal") as handlers, \
                mock.patch.object(G["Session"], "stop", lambda session: stopped.append(handlers.call_args_list[-2:])):
            self.assertEqual(2, G["serve"]("hidden"))
        self.assertEqual([[mock.call(signal.SIGTERM, signal.SIG_IGN), mock.call(signal.SIGHUP, signal.SIG_IGN)]], stopped)

    def test_a_failed_spawn_closes_its_log_pipe(self):
        session = G["Session"]("hidden")
        closed = []
        real_close = os.close
        with mock.patch.dict(G, {"capped_log": lambda path: 99999}), \
                mock.patch.object(subprocess, "Popen", side_effect=FileNotFoundError("gamescope")), \
                mock.patch.object(os, "close", lambda fd: closed.append(fd) if fd == 99999 else real_close(fd)):
            with self.assertRaises(OSError):
                session.spawn(["gamescope"], {}, str(self.tmp / "logs/g.log"))
        self.assertEqual([99999], closed)
        self.assertEqual([], session.procs)

    def test_a_broken_reply_from_the_launcher_api_means_not_known_yet(self):
        with mock.patch.dict(G, {"api_get": mock.Mock(side_effect=http.client.IncompleteRead(b""))}):
            self.assertIsNone(G["installed"]({"port": 1, "machineSecret": "s"}, "Fig"))

    def test_a_bad_timeout_falls_back_to_the_default(self):
        with mock.patch("sys.stdout", new_callable=io.StringIO) as out:
            self.assertEqual(1, G["launch"]({"timeout": "soon"}))
        self.assertEqual("missing-game", json.loads(out.getvalue())["reason"])

    def test_a_spawned_client_is_stopped_through_its_own_broker(self):
        asked = []
        proc = mock.Mock(pid=4321, wait=mock.Mock(return_value=0))
        with mock.patch.dict(G, {"ask_broker": lambda message, timeout=5.0, path=None: asked.append(path) or {"ok": True}}):
            G["stop_spawned"](proc, {"pid": 4321, "broker": "/run/spawned.sock"})
        self.assertEqual(["/run/spawned.sock"], asked)

    def test_a_spawned_client_that_will_not_stop_is_killed(self):
        proc = subprocess.Popen(["python3", "-c", "import signal, time; signal.signal(signal.SIGTERM, signal.SIG_IGN); "
                                 "print(1, flush=True); time.sleep(60)"], stdout=subprocess.PIPE, start_new_session=True)
        self.addCleanup(proc.stdout.close)
        self.addCleanup(proc.kill)
        proc.stdout.readline()
        with mock.patch.dict(G, {"STOP_WAIT": 0.2}):
            G["stop_spawned"](proc, {"pid": proc.pid, "broker": str(self.tmp / "missing.sock")})
        self.assertEqual(-signal.SIGKILL, proc.poll())

    def test_installations_listing_is_searched_for_the_app(self):
        listing = {"installations": [{"sandboxId": "ns", "artifact": {"id": "Fig"}}]}
        self.assertTrue(G["holds"](listing, "Fig"))
        self.assertFalse(G["holds"](listing, "Other"))

    def test_run_command_opens_the_launcher(self):
        with mock.patch.dict(G, {"serve": lambda mode: mode}):
            self.assertEqual("visible", G["main"](["run"]))

    def test_launch_command_reaches_the_store_launch(self):
        with mock.patch.dict(G, {"launch": lambda options: options.get("app")}):
            self.assertEqual("Fig", G["main"](["launch", "--app", "Fig", "--namespace", "ns", "--item", "it"]))


class StoreLaunchClientTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.folder = self.tmp / "Fig"
        self.folder.mkdir()
        self.sidecar = {"store": "epic", "id": "Fig", "extra": {"namespace": "ns", "catalogItemId": "it"}}
        (self.folder / ".droiddeck-store.json").write_text(json.dumps(self.sidecar))

    def test_client_is_wanted_once_installed(self):
        state = self.tmp / ".local" / "share" / "droiddeck-epic"
        with mock.patch.dict(os.environ, {"HOME": str(self.tmp)}), mock.patch("os.access", return_value=True):
            self.assertFalse(S["client_wanted"]())
            state.mkdir(parents=True)
            (state / "current.json").write_text("{}")
            self.assertTrue(S["client_wanted"]())
            with mock.patch("os.access", return_value=False):
                self.assertFalse(S["client_wanted"]())

    def test_offline_game_skips_the_client(self):
        calls = []
        sidecar = dict(self.sidecar, epic={"offline": True})
        (self.folder / ".droiddeck-store.json").write_text(json.dumps(sidecar))
        with mock.patch.dict(os.environ, {"BL_LAUNCH_DIR": str(self.tmp / "launch"), "STEAM_COMPAT_DATA_PATH": ""}), \
                mock.patch.dict(S, {"client_wanted": lambda: calls.append("wanted") or True,
                                    "ask": lambda channel, request, timeout=0: calls.append(request["op"]) or {"ok": True}}):
            S["main"]([str(self.folder / ".droiddeck-launch.bat")])
        self.assertNotIn("wanted", calls)
        self.assertNotIn("epic-code", calls)

    def test_through_client_needs_the_args_file(self):
        answers = [b'{"ok": true}\n', b'{"ok": false, "reason": "signed-out"}\n']

        def run(command, **kwargs):
            self.assertEqual(["launch", "--app", "Fig", "--namespace", "ns", "--item", "it"], command[1:8])
            return subprocess.CompletedProcess(command, 0, stdout=answers.pop(0))

        with mock.patch.object(subprocess, "run", run):
            self.assertEqual("failed", S["through_client"](self.folder, self.sidecar))
            self.assertEqual("signed-out", S["through_client"](self.folder, self.sidecar))
        self.assertEqual("no-catalog-ids", S["through_client"](self.folder, {"id": "Fig"}))

    def test_epic_launch_falls_back_to_an_exchange_code(self):
        calls = []
        env = {"BL_LAUNCH_DIR": str(self.tmp / "launch"), "STEAM_COMPAT_DATA_PATH": ""}
        with mock.patch.dict(os.environ, env), \
                mock.patch.dict(S, {"client_wanted": lambda: True,
                                    "through_client": lambda folder, sidecar, timeout=0: calls.append("client") or "timeout",
                                    "ask": lambda channel, request, timeout=0: calls.append(request["op"]) or {"ok": True, "code": True}}):
            S["main"]([str(self.folder / ".droiddeck-launch.bat")])
        self.assertEqual(["client", "epic-code", "cloud-down"], calls)

    def test_epic_launch_through_client_skips_the_code(self):
        calls = []

        def through(folder, sidecar, timeout=0):
            calls.append("client")
            (folder / ".droiddeck-epic-args").write_text("-epicapp=Fig\r\n")
            return "ok"

        (self.folder / ".droiddeck-epic-code").write_text("stale")
        with mock.patch.dict(os.environ, {"BL_LAUNCH_DIR": str(self.tmp / "launch"), "STEAM_COMPAT_DATA_PATH": ""}), \
                mock.patch.dict(S, {"client_wanted": lambda: True, "through_client": through,
                                    "ask": lambda channel, request, timeout=0: calls.append(request["op"]) or {"ok": True}}):
            S["main"]([str(self.folder / ".droiddeck-launch.bat")])
        self.assertEqual(["client", "cloud-down"], calls)
        self.assertFalse((self.folder / ".droiddeck-epic-code").exists())
        self.assertTrue((self.folder / ".droiddeck-epic-args").exists())

    def test_handled_launch_keeps_the_clients_args(self):
        calls = []
        (self.folder / ".droiddeck-epic-args").write_text("-epicapp=Fig\r\n")
        with mock.patch.dict(os.environ, {"BL_LAUNCH_DIR": str(self.tmp / "launch"), "DROIDDECK_EPIC_HANDLED": "1",
                                          "STEAM_COMPAT_DATA_PATH": ""}), \
                mock.patch.dict(S, {"client_wanted": lambda: calls.append("wanted") or True,
                                    "ask": lambda channel, request, timeout=0: calls.append(request["op"]) or {"ok": True}}):
            S["main"]([str(self.folder / ".droiddeck-launch.bat")])
        self.assertEqual(["cloud-down"], calls)
        self.assertTrue((self.folder / ".droiddeck-epic-args").exists())

    def test_the_games_exit_code_reaches_the_epic_client(self):
        runs = []
        bat = str(self.folder / ".droiddeck-launch.bat")
        with mock.patch.dict(os.environ, {"BL_LAUNCH_DIR": ""}), mock.patch.dict(os.environ), \
                mock.patch("os.access", return_value=True), \
                mock.patch.object(subprocess, "run", lambda command, **kwargs: runs.append(command)):
            os.environ.pop("DROIDDECK_EPIC_HANDLED", None)
            self.assertEqual(0, S["main"](["--exited", "--code", "7", bat]))
            self.assertEqual(0, S["main"](["--exited", bat]))
        self.assertEqual([S["EPIC_CLIENT"], "exited", "--code", "7"], runs[0])
        self.assertEqual("0", runs[1][-1])


if __name__ == "__main__":
    unittest.main()
