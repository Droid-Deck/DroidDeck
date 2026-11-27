from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

PRELOAD = Path(__file__).resolve().parents[1] / "linuxfs/preload"
LIMIT = 1024
WRAPPERS = {
    "descriptor.c": ("dd_descriptor_unlink", "dd_descriptor_rmdir", "dd_descriptor_remove", "dd_descriptor_rename"),
    "tracer.c": ("dd_status_without_tracer",),
}


def frames(source, build):
    obj = build / (source + ".o")
    subprocess.run(["cc", "-c", "-fPIC", "-O2", "-Wall", "-fstack-usage", "-o", str(obj), str(PRELOAD / source)],
                   check=True)
    sizes = {}
    for line in obj.with_suffix(".su").read_text().splitlines():
        place, size, _ = line.split("\t")
        sizes[place.rsplit(":", 1)[-1]] = int(size)
    return sizes


@unittest.skipUnless(shutil.which("cc"), "needs a C compiler")
class PreloadWrapperStackTest(unittest.TestCase):
    def test_wrappers_keep_path_buffers_off_their_frames(self):
        with tempfile.TemporaryDirectory() as build:
            for source, names in WRAPPERS.items():
                sizes = frames(source, Path(build))
                for name in names:
                    with self.subTest(function=name):
                        self.assertIn(name, sizes)
                        self.assertLess(sizes[name], LIMIT)


if __name__ == "__main__":
    unittest.main()
