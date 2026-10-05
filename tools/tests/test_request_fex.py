import runpy
import shutil
import tempfile
import unittest
from pathlib import Path

COMPAT = runpy.run_path(str(Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/steam-compatibility"))


class RequestFexTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root, True)
        (self.root / "steamapps").mkdir()
        client = self.root / "steamrtarm64/steam"
        client.parent.mkdir()
        self.log = self.root / "client.log"
        client.write_text("#!/bin/sh\necho \"start $1\" >> %s\nsleep 0.3\necho \"end $1\" >> %s\n" % (self.log, self.log))
        client.chmod(0o755)

    def install(self, app):
        (self.root / ("steamapps/appmanifest_%s.acf" % app)).write_text("")

    def test_one_install_is_asked_for_at_a_time(self):
        asked = set()
        self.assertEqual(["3127680"], COMPAT["request_fex"](str(self.root), asked))
        self.assertEqual(["start steam://install/3127680", "end steam://install/3127680"], self.log.read_text().splitlines())
        self.assertEqual([], COMPAT["request_fex"](str(self.root), asked))
        self.install("3127680")
        self.assertEqual(["1628350"], COMPAT["request_fex"](str(self.root), asked))
        self.assertEqual(["start steam://install/3127680", "end steam://install/3127680",
                          "start steam://install/1628350", "end steam://install/1628350"],
                         self.log.read_text().splitlines())
        self.install("1628350")
        self.assertEqual([], COMPAT["request_fex"](str(self.root), asked))
        self.assertEqual({"3127680", "1628350"}, asked)

    def test_a_declined_install_is_not_asked_again_in_the_same_session(self):
        asked = set()
        self.assertEqual(["3127680"], COMPAT["request_fex"](str(self.root), asked))
        self.assertEqual([], COMPAT["request_fex"](str(self.root), asked))
        self.assertEqual(["3127680"], COMPAT["request_fex"](str(self.root), set()))

    def test_only_what_is_missing_is_asked_for_once(self):
        self.install("3127680")
        asked = set()
        self.assertEqual(["1628350"], COMPAT["request_fex"](str(self.root), asked))
        self.assertEqual([], COMPAT["request_fex"](str(self.root), asked))
        self.assertEqual(["start steam://install/1628350", "end steam://install/1628350"], self.log.read_text().splitlines())

if __name__ == "__main__":
    unittest.main()
