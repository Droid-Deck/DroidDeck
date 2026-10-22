import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SESSION = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-session"
URUNTIME = "/usr/local/lib/droiddeck/uruntime"


def target_case():
    match = re.search(r"^    case \$\{target,,\} in\n.*?^    esac\n", SESSION.read_text(), re.M | re.S)
    return match.group(0)


class SessionAppImageTest(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.dir, True)
        self.image = self.dir / "rpcs3.AppImage"
        self.image.write_bytes(b"\x7fELF")
        self.uruntime = self.dir / "uruntime"
        self.uruntime.write_text("#!/bin/sh\n")
        self.uruntime.chmod(0o755)

    def launch(self, fex=()):
        script = ("set -u\n"
                  "run_program() { printf '%%s\\n' \"TARGET=${TARGET_APPIMAGE:-} EXTRACT=${APPIMAGE_EXTRACT_AND_RUN:-}\" \"$@\"; exit 0; }\n"
                  "target=%s\nfex=(%s)\nset -- --no-gui\n" % (self.image, " ".join(fex))
                  + target_case().replace(URUNTIME, str(self.uruntime)))
        env = {k: v for k, v in os.environ.items() if k not in ("TARGET_APPIMAGE", "APPIMAGE_EXTRACT_AND_RUN")}
        return subprocess.run(["bash", "-c", script], env=env, capture_output=True, text=True, timeout=30).stdout.splitlines()

    def test_an_arm64_image_runs_through_the_shipped_runtime(self):
        self.assertEqual(["TARGET=%s EXTRACT=1" % self.image, str(self.uruntime), "--no-gui"], self.launch())

    def test_without_the_shipped_runtime_the_image_runs_itself(self):
        self.uruntime.unlink()
        self.assertEqual(["TARGET= EXTRACT=1", str(self.image), "--no-gui"], self.launch())

    def test_an_x86_image_still_starts_under_fex(self):
        fex = ("/usr/local/bin/droiddeck-fex", "run", "--mode", "on", "--")
        self.assertEqual(["TARGET= EXTRACT=1", *fex, str(self.image), "--no-gui"], self.launch(fex))


if __name__ == "__main__":
    unittest.main()
