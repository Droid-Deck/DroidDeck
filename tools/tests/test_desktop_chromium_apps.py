import os
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "linuxfs/desktop/droiddeck-desktop-gpu"
PREFIX = "env -u WAYLAND_DISPLAY XDG_SESSION_TYPE=x11 ELECTRON_DISABLE_SANDBOX=1 "
FLAGS = " --no-sandbox --ozone-platform=x11"


def load():
    source = SCRIPT.read_text()
    assert source.rstrip().endswith("main()")
    scope = {"__name__": "droiddeck_desktop_gpu"}
    exec(compile(source.rstrip()[:-len("main()")], str(SCRIPT), "exec"), scope)
    return scope


class DesktopChromiumAppsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.gpu = load()
        self.electron = self.app("lib/chat/chat", sandbox=True)
        self.plain = self.app("lib/editor/editor", sandbox=False)
        bin_dir = self.root / "bin"
        bin_dir.mkdir()
        (bin_dir / "chat").symlink_to(self.electron)
        old_path = os.environ.get("PATH", "")
        os.environ["PATH"] = "%s:%s" % (bin_dir, old_path)
        self.addCleanup(os.environ.__setitem__, "PATH", old_path)

    def app(self, relative, sandbox):
        path = self.root / relative
        path.parent.mkdir(parents=True)
        path.write_text("#!/bin/sh\n")
        path.chmod(0o755)
        if sandbox:
            (path.parent / "chrome-sandbox").write_text("")
        return path

    def entry(self, exec_line, categories="Network;", actions=""):
        text = "[Desktop Entry]\nType=Application\nName=App\nExec=%s\nCategories=%s\n" % (exec_line, categories)
        if actions:
            text += "Actions=new;\n\n[Desktop Action new]\nName=New\nExec=%s\n" % actions
        return text

    def exec_lines(self, text):
        return [l[5:] for l in text.splitlines() if l.startswith("Exec=")]

    def test_an_electron_app_starts_without_the_sandbox_on_x11(self):
        out = self.gpu["wrap"](self.entry("%s %%U" % self.electron, actions="%s --new" % self.electron))
        self.assertEqual(self.exec_lines(out), [PREFIX + str(self.electron) + FLAGS + " %U",
                                                PREFIX + str(self.electron) + FLAGS + " --new"])
        self.assertIn("X-DroidDeck-GPU=1", out)
        self.assertIn("Actions=new;", out)
        self.assertNotIn("droiddeck-window", out)

    def test_a_command_on_the_path_is_followed_to_its_install_folder(self):
        out = self.gpu["wrap"](self.entry("chat %U"))
        self.assertEqual(self.exec_lines(out), [PREFIX + "chat" + FLAGS + " %U"])

    def test_a_quoted_program_keeps_its_quotes(self):
        out = self.gpu["wrap"](self.entry('"%s" %%U' % self.electron))
        self.assertEqual(self.exec_lines(out), [PREFIX + '"%s"' % self.electron + FLAGS + " %U"])

    def test_other_apps_are_left_alone(self):
        self.assertIsNone(self.gpu["wrap"](self.entry("%s %%F" % self.plain)))
        self.assertIsNone(self.gpu["wrap"](self.entry("missing-program %F")))

    def test_an_electron_game_goes_to_the_gpu_and_keeps_a_window_action(self):
        out = self.gpu["wrap"](self.entry(str(self.electron), categories="Game;"))
        fixed = PREFIX + str(self.electron) + FLAGS
        self.assertEqual(self.exec_lines(out), ["droiddeck-gpu " + fixed, fixed])

    def test_with_the_gpu_off_electron_apps_are_still_fixed(self):
        out = self.gpu["wrap"](self.entry(str(self.electron), categories="Game;"), False)
        self.assertEqual(self.exec_lines(out), [PREFIX + str(self.electron) + FLAGS])
        self.assertIsNone(self.gpu["wrap"](self.entry(str(self.plain), categories="Game;"), False))

    def test_games_that_are_not_chromium_are_unchanged(self):
        out = self.gpu["wrap"](self.entry(str(self.plain), categories="Game;"))
        self.assertEqual(self.exec_lines(out), ["droiddeck-gpu " + str(self.plain), str(self.plain)])


if __name__ == "__main__":
    unittest.main()
