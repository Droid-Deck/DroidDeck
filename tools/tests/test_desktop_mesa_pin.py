import pathlib
import subprocess
import sys
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
BUILDER = ROOT / "tools/desktop-kde"


class MesaPinTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        for repo in ("core", "extra", "alarm"):
            (self.root / ("x_" + repo)).mkdir()
        rolling = self.root / "x_extra/mesa-26.2.5"
        rolling.mkdir()
        (rolling / "desc").write_text(
            "%NAME%\nmesa\n\n%VERSION%\n1:26.2.5-1\n\n"
            "%FILENAME%\nrolling-mesa.pkg.tar.xz\n\n%SHA256SUM%\nrolling\n\n"
            "%DEPENDS%\nrolling-only-dependency\n\n%PROVIDES%\nopengl-driver\n"
        )
        fields = {}
        for block in (BUILDER / "pins/mesa/desc").read_text().strip().split("\n\n"):
            key, *values = block.splitlines()
            fields[key.strip("%")] = values
        self.pin = fields
        self.runtime = self.root / "runtime.txt"
        self.runtime.write_text("".join(name + " 999\n" for name in fields["DEPENDS"]))
        self.seeds = self.root / "seeds.txt"

    def resolve(self, seed):
        self.seeds.write_text(seed + "\n")
        return subprocess.run(
            [sys.executable, str(BUILDER / "closure.py"), str(self.root),
             str(self.runtime), str(self.seeds)],
            text=True, capture_output=True,
        )

    def assertPinned(self, seed):
        result = self.resolve(seed)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(
            result.stdout.strip(),
            "extra/" + self.pin["FILENAME"][0] + " " + self.pin["SHA256SUM"][0],
        )

    def test_pinned_metadata_overrides_rolling_dependencies(self):
        self.assertPinned("mesa")

    def test_virtual_provider_uses_pinned_package(self):
        self.assertPinned("opengl-driver")

    def test_newer_runtime_cannot_bypass_tested_mesa(self):
        with self.runtime.open("a") as stream:
            stream.write("mesa 1:26.2.5-1\n")
        self.assertPinned("mesa")

    def test_requirement_above_pin_fails_instead_of_using_rolling_mesa(self):
        result = self.resolve("mesa>=1:26.2.5")
        self.assertNotEqual(result.returncode, 0)


if __name__ == "__main__":
    unittest.main()
