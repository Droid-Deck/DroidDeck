import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SESSION = (Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-session").read_text()
GATE = SESSION[SESSION.index("bl_qualcomm_browser_gpu() {"):SESSION.index("reap_orphans() {")]


class QualcommBrowserTest(unittest.TestCase):
    def gate(self, glxinfo=None, **extra):
        with tempfile.TemporaryDirectory() as directory:
            env = dict(os.environ)
            for name in ("DROIDDECK_SYSTEM_QUALCOMM", "BL_STEAM_CEF_SOFTWARE"):
                env.pop(name, None)
            if glxinfo is not None:
                stub = Path(directory) / "glxinfo"
                stub.write_text("#!/bin/sh\necho 'touched' > \"$0.ran\"\n" + glxinfo, newline="\n")
                stub.chmod(0o755)
                env["PATH"] = directory + os.pathsep + env["PATH"]
            env.update(extra)
            result = subprocess.run(["bash", "-c", GATE + "\nbl_qualcomm_browser_gpu; echo \"rc=$?\""],
                                    env=env, capture_output=True, text=True)
            ran = glxinfo is not None and (Path(directory) / "glxinfo.ran").exists()
            return result.stdout.strip(), ran

    def test_zink_below_opengl_4_selects_software_compositing(self):
        out, _ = self.gate("printf '    Max core profile version: 3.2\\n'\n", DROIDDECK_SYSTEM_QUALCOMM="1")
        self.assertIn("browser software compositing (Zink offers OpenGL 3.2; GPU compositing needs 4.0)", out)
        self.assertTrue(out.endswith("rc=1"))

    def test_zink_at_opengl_4_keeps_gpu_compositing(self):
        out, _ = self.gate("printf '    Max core profile version: 4.6\\n'\n", DROIDDECK_SYSTEM_QUALCOMM="1")
        self.assertIn("browser GPU compositing on (Zink OpenGL 4.6)", out)
        self.assertTrue(out.endswith("rc=0"))

    def test_an_unreadable_version_selects_software_compositing(self):
        out, _ = self.gate("exit 1\n", DROIDDECK_SYSTEM_QUALCOMM="1")
        self.assertIn("Zink offers OpenGL unknown", out)
        self.assertTrue(out.endswith("rc=1"))

    def test_an_explicit_override_and_other_drivers_skip_the_probe(self):
        for extra in ({"DROIDDECK_SYSTEM_QUALCOMM": "1", "BL_STEAM_CEF_SOFTWARE": "0"},
                      {"DROIDDECK_SYSTEM_QUALCOMM": "1", "BL_STEAM_CEF_SOFTWARE": "1"},
                      {"DROIDDECK_SYSTEM_QUALCOMM": "0"}, {}):
            with self.subTest(extra=extra):
                out, ran = self.gate("printf '    Max core profile version: 3.2\\n'\n", **extra)
                self.assertEqual(out, "rc=0")
                self.assertFalse(ran)

    def test_the_steam_launch_applies_the_gate(self):
        self.assertIn("bl_qualcomm_browser_gpu || steam_cef_gpu=0", SESSION)


if __name__ == "__main__":
    unittest.main()
