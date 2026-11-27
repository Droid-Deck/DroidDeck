import importlib.machinery
import importlib.util
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

HELPER = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-qam-park"

TREE = """
  Root window id: 0x3c3 (the root window) (has no name)
  1 child:
     0x2000035 "Steam Big Picture Mode": ("steamwebhelper" "steam")  1280x720+0+0  +0+0
        4 children:
        0x1600014 (has no name): ()  1136x615+%d+53  +%d+53
           1 child:
           0x1600015 (has no name): ()  1136x615+0+0  +0+53
        0x1600016 (has no name): ()  426x107+0+0  +0+0
           1 child:
           0x1600017 (has no name): ()  426x107+0+0  +0+0
        0x1600012 (has no name): ()  1x1+0+0  +0+0
     0x2000040 "Other": ()  1136x615+0+0  +0+0
"""


def load():
    loader = importlib.machinery.SourceFileLoader("qam_park", str(HELPER))
    spec = importlib.util.spec_from_loader("qam_park", loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


class QamParkTest(unittest.TestCase):
    def setUp(self):
        self.park = load()
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.park.FLAG = str(self.tmp / "flag")
        self.park.SIZE = str(self.tmp / "size")

    def tree(self, x):
        return mock.patch.object(self.park.subprocess, "run",
                                 return_value=subprocess.CompletedProcess([], 0, TREE % (x, x), ""))

    def test_finds_the_menu_window_among_big_picture_children(self):
        with self.tree(818):
            self.assertEqual((0x1600014, 818, 53, 1280), self.park.find(1136, 615))
        with self.tree(1344):
            self.assertEqual((0x1600014, 1344, 53, 1280), self.park.find(1135, 617))

    def test_ignores_windows_of_another_size_or_outside_big_picture(self):
        with self.tree(818):
            self.assertIsNone(self.park.find(1136, 722))
            self.assertIsNone(self.park.find(1136, 600))

    def test_size_and_stop_manage_the_state_files(self):
        with mock.patch.object(self.park.sys, "argv", ["x", "size", "1136", "615"]):
            self.assertEqual(0, self.park.main())
        self.assertEqual((1136, 615), self.park.wanted_size())
        Path(self.park.FLAG).write_text("1")
        with mock.patch.object(self.park.sys, "argv", ["x", "stop"]):
            self.assertEqual(0, self.park.main())
        self.assertFalse(os.path.exists(self.park.FLAG))
        self.assertIsNone(self.park.wanted_size())

    def test_rejects_unknown_commands(self):
        with mock.patch.object(self.park.sys, "argv", ["x", "start"]), mock.patch.object(self.park.sys, "stderr"):
            self.assertEqual(2, self.park.main())


if __name__ == "__main__":
    unittest.main()
