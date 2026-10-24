import os
from pathlib import Path
import re
import runpy
import shutil
import tempfile
import unittest
from unittest import mock

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / "tools/linuxfs/overlay/usr/local/bin/droiddeck-desktop-requests"
REQ = runpy.run_path(str(SCRIPT))["main"].__globals__
KOTLIN = REPO / "app/src/main/java/com/droiddeck/launcher/runtime/DesktopRequests.kt"
BATTLENET = REPO / "app/src/main/java/com/droiddeck/launcher/runtime/BattleNet.kt"
DESKTOP = REPO / "tools/linuxfs/desktop/droiddeck-desktop"
SESSION_FILES = REPO / "app/src/main/java/com/droiddeck/launcher/session/SessionFiles.kt"


class Temp(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        null = open(os.devnull, "w")
        self.addCleanup(null.close)
        patch = mock.patch.object(REQ["sys"], "stderr", null)
        patch.start()
        self.addCleanup(patch.stop)

    def request(self, *lines):
        (self.tmp / REQ["REQUEST"]).write_text("".join(line + "\n" for line in lines))

    def program(self, name="droiddeck-thing", mode=0o755):
        folder = self.tmp / "bin"
        folder.mkdir(exist_ok=True)
        path = folder / name
        path.write_text("#!/bin/sh\n")
        path.chmod(mode)
        return path


class AppTest(unittest.TestCase):
    def test_the_app_and_the_script_name_the_same_request(self):
        self.assertIn('const val REQUEST = "%s"' % REQ["REQUEST"], KOTLIN.read_text())

    def test_battlenet_is_one_of_the_programs_that_may_run(self):
        found = re.search(r'const val PROGRAM = "([^"]+)"', BATTLENET.read_text())
        self.assertEqual(os.path.dirname(found.group(1)), REQ["BIN"])
        self.assertTrue((REPO / "tools/linuxfs/overlay" / found.group(1).lstrip("/")).is_file())

    def test_the_desktop_installs_the_autostart_entry_and_the_app_stages_the_script(self):
        self.assertIn("droiddeck-desktop-requests --install", DESKTOP.read_text())
        self.assertIn('"usr/local/bin/droiddeck-desktop-requests" to "usr/local/bin/droiddeck-desktop-requests"', SESSION_FILES.read_text())


class TakeTest(Temp):
    def test_a_request_is_read_once(self):
        self.request("/usr/local/bin/droiddeck-battlenet", "open")
        self.assertEqual(REQ["take"](str(self.tmp)), ["/usr/local/bin/droiddeck-battlenet", "open"])
        self.assertEqual(REQ["take"](str(self.tmp)), None)
        self.assertEqual(sorted(os.listdir(self.tmp)), [])

    def test_no_request_and_an_empty_one_are_nothing(self):
        self.assertIsNone(REQ["take"](str(self.tmp)))
        (self.tmp / REQ["REQUEST"]).write_text("")
        self.assertIsNone(REQ["take"](str(self.tmp)))


class RunTest(Temp):
    def test_only_the_apps_own_programs_run(self):
        good = self.program()
        folder = str(good.parent)
        outside = self.tmp / "elsewhere"
        outside.write_text("#!/bin/sh\n")
        outside.chmod(0o755)
        plain = self.program("droiddeck-data", 0o644)
        self.assertTrue(REQ["allowed"](str(good), folder))
        self.assertFalse(REQ["allowed"](str(outside), folder))
        self.assertFalse(REQ["allowed"](str(plain), folder))
        self.assertFalse(REQ["allowed"](str(good.parent / "missing"), folder))
        self.assertFalse(REQ["allowed"](folder + "/../elsewhere", folder))

    def test_a_program_runs_on_its_own_with_its_arguments(self):
        good = self.program()
        with mock.patch.object(REQ["subprocess"], "Popen") as popen:
            self.assertTrue(REQ["run"]([str(good), "a", "b c"], str(good.parent)))
            self.assertFalse(REQ["run"]([str(self.tmp / "elsewhere")], str(good.parent)))
        popen.assert_called_once()
        self.assertEqual(popen.call_args.args[0], [str(good), "a", "b c"])
        self.assertTrue(popen.call_args.kwargs["start_new_session"])
        self.assertEqual(popen.call_args.kwargs["stdin"], REQ["subprocess"].DEVNULL)

    def test_serving_runs_what_comes_in(self):
        self.request("/usr/local/bin/droiddeck-battlenet")
        with mock.patch.dict(REQ, {"run": mock.Mock()}):
            REQ["serve"](str(self.tmp), rounds=2, poll=0)
            REQ["run"].assert_called_once_with(["/usr/local/bin/droiddeck-battlenet"])


class InstallTest(Temp):
    def test_the_autostart_entry_runs_this_in_kde(self):
        path = self.tmp / "autostart" / "droiddeck-desktop-requests.desktop"
        REQ["install"](str(path))
        text = path.read_text()
        self.assertIn("Exec=/usr/local/bin/droiddeck-desktop-requests\n", text)
        self.assertIn("OnlyShowIn=KDE;\n", text)
        self.assertIn("NoDisplay=true\n", text)
        before = path.stat().st_mtime_ns
        REQ["install"](str(path))
        self.assertEqual(path.stat().st_mtime_ns, before)


class MainTest(Temp):
    def test_one_copy_a_desktop(self):
        first = REQ["single"](str(self.tmp))
        self.assertIsNotNone(first)
        self.assertIsNone(REQ["single"](str(self.tmp)))
        first.close()
        self.assertIsNotNone(REQ["single"](str(self.tmp)))

    def test_without_a_session_root_there_is_nothing_to_do(self):
        with mock.patch.dict(os.environ, {"BL_LAUNCH_DIR": str(self.tmp / "missing")}), \
                mock.patch.dict(REQ, {"serve": mock.Mock()}):
            self.assertEqual(REQ["main"]([]), 0)
            REQ["serve"].assert_not_called()
        self.assertEqual(REQ["main"](["--bogus"]), 2)


if __name__ == "__main__":
    unittest.main()
