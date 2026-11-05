import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-emulators"


class EmulatorsInstallTest(unittest.TestCase):
    def run_install(self, failure=""):
        with tempfile.TemporaryDirectory(prefix="emulator install 'quoted' ") as directory:
            root = Path(directory)
            bin_dir = root / "bin"
            bin_dir.mkdir()
            calls = root / "calls"
            launcher = root / "PPSSPPSDL"
            helper = root / "droiddeck-flatpak"
            helper.write_text(
                "import os, sys\n"
                "with open(os.environ['CALLS'], 'a') as f:\n"
                "    f.write('helper ' + ' '.join(sys.argv[1:]) + '\\n')\n"
                "sys.exit(1 if os.environ['FAILURE'] == 'helper' else 0)\n"
            )
            stubs = {
                "droiddeck-root":
                    'echo "apt $*" >> "$CALLS"\n'
                    '[ "$FAILURE" != "apt-$2" ]',
                "flatpak":
                    'echo "flatpak $*" >> "$CALLS"\n'
                    'if [ "$1" = install ]; then\n'
                    '  echo "Could not connect: No such file or directory" >&2\n'
                    '  exit 1\n'
                    'fi\n'
                    '[ "$FAILURE" != remote ]',
            }
            for name, body in stubs.items():
                path = bin_dir / name
                path.write_text("#!/bin/sh\n" + body + "\n")
                path.chmod(0o755)
            script = root / "install"
            script.write_text(SCRIPT.read_text()
                              .replace("/usr/local/bin/droiddeck-flatpak install", shlex.quote(str(helper)) + " install")
                              .replace("/usr/local/bin/PPSSPPSDL", shlex.quote(str(launcher))))
            env = dict(os.environ, PATH=str(bin_dir) + os.pathsep + os.environ["PATH"],
                       CALLS=str(calls), FAILURE=failure)
            result = subprocess.run(["bash", str(script), "install"], env=env,
                                    capture_output=True, text=True, timeout=30)
            return result, calls.read_text(), launcher.read_text() if launcher.exists() else None, \
                os.access(launcher, os.X_OK)

    def test_install_without_a_system_bus_uses_the_helper_and_creates_the_launcher(self):
        result, calls, launcher, executable = self.run_install()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("helper install org.ppsspp.PPSSPP\n", calls)
        self.assertNotIn("flatpak install ", calls)
        self.assertIn('exec /usr/local/bin/droiddeck-flatpak-run org.ppsspp.PPSSPP "$@"', launcher)
        self.assertTrue(executable)

    def test_helper_failure_does_not_publish_a_launcher(self):
        result, calls, launcher, _ = self.run_install("helper")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("helper install org.ppsspp.PPSSPP\n", calls)
        self.assertIn("== FAIL org.ppsspp.PPSSPP", result.stdout)
        self.assertIsNone(launcher)

    def test_apt_failure_stops_before_flatpak(self):
        for stage in ("update", "install"):
            with self.subTest(stage=stage):
                result, calls, launcher, _ = self.run_install("apt-" + stage)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("== FAIL apt-get " + stage, result.stdout)
                self.assertNotIn("flatpak ", calls)
                self.assertNotIn("helper ", calls)
                self.assertIsNone(launcher)

    def test_remote_failure_stops_before_the_helper(self):
        result, calls, launcher, _ = self.run_install("remote")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("== FAIL flathub", result.stdout)
        self.assertNotIn("helper ", calls)
        self.assertIsNone(launcher)


if __name__ == "__main__":
    unittest.main()
