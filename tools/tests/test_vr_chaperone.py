import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


@unittest.skipUnless(shutil.which("g++"), "needs a Linux C++ compiler")
class XrChaperoneTest(unittest.TestCase):
    def test_seated_origin_and_stage_preservation(self):
        repository = Path(__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory() as directory:
            executable = Path(directory) / "chaperone-test"
            subprocess.run(["g++", "-std=c++17", "-Wall", "-Wextra", "-Werror", "-O2",
                            str(repository / "tools/vr/tests/test_xr_chaperone.cpp"), "-o", str(executable)], check=True)
            result = json.loads(subprocess.check_output([str(executable)], text=True))
        self.assertEqual((result["jsonid"], result["version"]), ("chaperone_info", 5))
        self.assertEqual(result["time"], "2026-10-15T21:00:00Z")
        universe, = result["universes"]
        self.assertEqual(int(universe["universeID"]), 2)
        self.assertEqual(universe["standing"], {"translation": [0, 0, 0], "yaw": 0})
        self.assertEqual(universe["collision_bounds"], [])
        self.assertEqual(universe["play_area"], [0, 0])
        self.assertAlmostEqual(universe["seated"]["translation"][1], -1.6, places=6)
