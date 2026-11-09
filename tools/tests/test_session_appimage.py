import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
SESSION = BIN / "droiddeck-session"
EXEC = BIN / "droiddeck-appimage-exec"
URUNTIME = "/usr/local/lib/droiddeck/uruntime"
FEX = "/usr/local/bin/droiddeck-fex"
REPORT = "#!/bin/sh\nprintf '%%s\\n' \"TARGET=${TARGET_APPIMAGE:-} EXTRACT=${APPIMAGE_EXTRACT_AND_RUN:-}\" %s \"$@\"\n"


def target_case():
    match = re.search(r"^    case \$\{target,,\} in\n.*?^    esac\n", SESSION.read_text(), re.M | re.S)
    return match.group(0)


class AppImageExecTest(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.dir, True)
        self.image = self.dir / "rpcs3.AppImage"
        self.machine(183)
        self.uruntime = self.tool("uruntime", "uruntime")
        self.fex = self.tool("droiddeck-fex", "fex")

    def machine(self, number):
        self.image.write_bytes(b"\x7fELF" + bytes(14) + number.to_bytes(2, "little") + bytes(44))

    def tool(self, name, label):
        path = self.dir / name
        path.write_text(REPORT % label)
        path.chmod(0o755)
        return path

    def launch(self):
        script = self.dir / "exec"
        script.write_text(EXEC.read_text().replace(URUNTIME, str(self.uruntime)).replace(FEX, str(self.fex)))
        env = {k: v for k, v in os.environ.items() if k not in ("TARGET_APPIMAGE", "APPIMAGE_EXTRACT_AND_RUN")}
        return subprocess.run(["bash", str(script), str(self.image), "--no-gui"], env=env,
                              capture_output=True, text=True, timeout=30).stdout.splitlines()

    def test_an_arm64_image_runs_through_the_shipped_runtime(self):
        self.assertEqual(["TARGET=%s EXTRACT=1" % self.image, "uruntime", "--no-gui"], self.launch())

    def test_without_the_shipped_runtime_the_image_runs_itself(self):
        self.uruntime.unlink()
        self.image.write_text(REPORT % "own")
        self.assertEqual(["TARGET= EXTRACT=1", "own", "--no-gui"], self.launch())

    def test_an_x86_image_still_starts_under_fex(self):
        self.machine(62)
        self.assertEqual(["TARGET= EXTRACT=1", "fex", "run", "--mode", "on", "--", str(self.image), "--no-gui"], self.launch())

    def test_an_x86_image_without_fex_uses_the_shipped_runtime(self):
        self.machine(62)
        self.fex.unlink()
        self.assertEqual(["TARGET=%s EXTRACT=1" % self.image, "uruntime", "--no-gui"], self.launch())

    def test_the_session_starts_images_through_the_shared_launcher(self):
        script = ("set -u\n"
                  "run_program() { printf '%%s\\n' \"$@\"; exit 0; }\n"
                  "target=%s\nfex=()\nset -- --no-gui\n" % self.image + target_case())
        out = subprocess.run(["bash", "-c", script], capture_output=True, text=True, timeout=30).stdout.splitlines()
        self.assertEqual(["/usr/local/bin/droiddeck-appimage-exec", str(self.image), "--no-gui"], out)


if __name__ == "__main__":
    unittest.main()
