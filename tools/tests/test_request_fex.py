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

    def test_each_install_is_handed_over_before_the_next_is_asked(self):
        asked = set()
        self.assertEqual(["3127680", "1628350"], COMPAT["request_fex"](str(self.root), asked))
        self.assertEqual(["start steam://install/3127680", "end steam://install/3127680",
                          "start steam://install/1628350", "end steam://install/1628350"],
                         self.log.read_text().splitlines())
        self.assertEqual({"3127680", "1628350"}, asked)

    def test_only_what_is_missing_is_asked_for_once(self):
        (self.root / "steamapps/appmanifest_3127680.acf").write_text("")
        asked = set()
        self.assertEqual(["1628350"], COMPAT["request_fex"](str(self.root), asked))
        self.assertEqual([], COMPAT["request_fex"](str(self.root), asked))
        self.assertEqual(["start steam://install/1628350", "end steam://install/1628350"], self.log.read_text().splitlines())


if __name__ == "__main__":
    unittest.main()
