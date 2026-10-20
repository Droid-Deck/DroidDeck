import os
import runpy
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest import mock

BWRAP = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-bwrap"


class BwrapSocketTest(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.dir, True)
        self.shim = runpy.run_path(str(BWRAP))
        self.shim["socket_name"].__globals__["SOCKET_FILE"] = str(self.dir / "bwrap-socket")

    def name(self, env):
        with mock.patch.dict(os.environ, env, clear=True):
            return self.shim["socket_name"]()

    def test_flatpak_empty_environment_reaches_a_renamed_package(self):
        (self.dir / "bwrap-socket").write_text("com.antutu.benchmark.full.bwrap\n")
        self.assertEqual("\0com.antutu.benchmark.full.bwrap", self.name({}))

    def test_the_environment_still_wins(self):
        (self.dir / "bwrap-socket").write_text("com.antutu.benchmark.full.bwrap\n")
        self.assertEqual("\0com.tencent.ig.bwrap", self.name({"DROIDDECK_BWRAP_SOCKET": "com.tencent.ig.bwrap"}))

    def test_without_either_the_standard_package_is_asked(self):
        self.assertEqual("\0com.droiddeck.launcher.bwrap", self.name({}))


if __name__ == "__main__":
    unittest.main()
