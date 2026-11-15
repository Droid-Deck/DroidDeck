import io
import os
import runpy
import subprocess
import tarfile
import tempfile
import unittest
from pathlib import Path

PACMAN = Path(__file__).resolve().parents[1] / "desktop-kde/overlay/usr/share/droiddeck/pacman"


class PacmanSharedFilesTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.top = Path(temp.name)

    def keep(self, action):
        lib = self.top / "lib"
        lib.mkdir(exist_ok=True)
        script = (PACMAN / "keep").read_text().replace("/var/lib/pacman", str(lib))
        subprocess.run(["bash", "-c", script, "keep", action], check=True, timeout=30)
        return lib

    def test_an_upgrade_that_drops_a_shared_file_gets_it_back(self):
        shared = self.top / "usr/lib/libshared.so"
        shared.parent.mkdir(parents=True)
        shared.write_text("shared")
        lib = self.top / "lib"
        lib.mkdir()
        (lib / "droiddeck-shared-files").write_text("%s\n%s\n" % (shared, self.top / "usr/lib/never-there"))
        self.keep("save")
        shared.unlink()
        self.keep("restore")
        self.assertEqual("shared", shared.read_text())
        self.assertFalse((self.top / "usr/lib/never-there").exists())
        self.assertFalse((lib / "droiddeck-shared-keep").exists())

    def test_a_backup_for_a_file_still_missing_survives_the_next_save(self):
        shared = self.top / "usr/lib/libshared.so"
        shared.parent.mkdir(parents=True)
        shared.write_text("shared")
        lib = self.top / "lib"
        lib.mkdir()
        (lib / "droiddeck-shared-files").write_text("%s\n" % shared)
        self.keep("save")
        shared.unlink()
        self.keep("save")
        self.keep("restore")
        self.assertEqual("shared", shared.read_text())

    def test_only_identical_files_are_taken_over(self):
        adopt = runpy.run_path(str(PACMAN / "adopt"), run_name="adopt")
        package = io.BytesIO()
        with tarfile.open(fileobj=package, mode="w") as archive:
            for name, data in (("same", b"one"), ("different", b"two")):
                info = tarfile.TarInfo(name)
                info.size = len(data)
                archive.addfile(info, io.BytesIO(data))
        (self.top / "same").write_bytes(b"one")
        (self.top / "different").write_bytes(b"other")
        package.seek(0)
        with tarfile.open(fileobj=package) as archive:
            members = {m.name: m for m in archive}
            self.assertTrue(adopt["same"](archive, members["same"], str(self.top / "same")))
            self.assertFalse(adopt["same"](archive, members["different"], str(self.top / "different")))


if __name__ == "__main__":
    unittest.main()
