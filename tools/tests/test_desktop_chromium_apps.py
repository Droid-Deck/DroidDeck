from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "linuxfs/desktop/droiddeck-desktop-gpu"


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

    def test_electron_apps_keep_their_own_entries(self):
        self.assertIsNone(self.gpu["wrap"](self.entry("%s %%U" % self.electron, actions="%s --new" % self.electron)))
        self.assertIsNone(self.gpu["wrap"](self.entry(str(self.plain))))

    def test_an_electron_game_goes_to_the_gpu_as_installed(self):
        out = self.gpu["wrap"](self.entry(str(self.electron), categories="Game;", actions="%s --new" % self.electron))
        self.assertEqual(self.exec_lines(out), ["droiddeck-gpu " + str(self.electron), str(self.electron),
                                                "droiddeck-gpu %s --new" % self.electron])

    def test_with_the_gpu_off_no_entry_is_written(self):
        self.assertIsNone(self.gpu["wrap"](self.entry(str(self.electron), categories="Game;"), False))
        self.assertIsNone(self.gpu["wrap"](self.entry(str(self.plain), categories="Game;"), False))

    def test_games_that_are_not_chromium_are_unchanged(self):
        out = self.gpu["wrap"](self.entry(str(self.plain), categories="Game;"))
        self.assertEqual(self.exec_lines(out), ["droiddeck-gpu " + str(self.plain), str(self.plain)])


if __name__ == "__main__":
    unittest.main()
