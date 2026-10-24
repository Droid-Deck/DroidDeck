import os
from pathlib import Path
import runpy
import subprocess
import unittest

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
COMPAT = runpy.run_path(str(BIN / "steam-compatibility"))
SHOW = 'echo "DISABLE_GAMESCOPE_WSI=${DISABLE_GAMESCOPE_WSI-unset} WSI_X11_FORCE_SHM=${WSI_X11_FORCE_SHM-unset}"\n'
SESSION_DEFAULT = "DISABLE_GAMESCOPE_WSI=1 WSI_X11_FORCE_SHM=1"


class QualcommPresentTest(unittest.TestCase):
    def present(self, **extra):
        env = dict(os.environ, DISABLE_GAMESCOPE_WSI="1", WSI_X11_FORCE_SHM="1")
        env.pop("DROIDDECK_SYSTEM_QUALCOMM", None)
        env.pop("BL_QUALCOMM_SHM", None)
        env.update(extra)
        script = COMPAT["BL_PRESENT_SETUP"] + "\nbl_present\n" + SHOW
        return subprocess.run(["bash", "-c", script], env=env, capture_output=True, text=True, check=True).stdout.strip()

    def test_qualcomm_games_present_through_gamescope_wsi(self):
        self.assertEqual(self.present(DROIDDECK_SYSTEM_QUALCOMM="1"), "DISABLE_GAMESCOPE_WSI=unset WSI_X11_FORCE_SHM=unset")

    def test_turnip_sessions_and_the_shm_override_keep_the_session_default(self):
        for extra in ({}, {"DROIDDECK_SYSTEM_QUALCOMM": "0"}, {"DROIDDECK_SYSTEM_QUALCOMM": "1", "BL_QUALCOMM_SHM": "1"}):
            with self.subTest(extra=extra):
                self.assertEqual(self.present(**extra), SESSION_DEFAULT)

    def test_every_proton_launch_selects_the_present_path(self):
        self.assertIn("bl_present()", COMPAT["LAUNCHER_SH"])
        self.assertEqual((BIN / "steam-compatibility").read_text().count("BL_LIBPATH_SETUP + BL_PRESENT_SETUP"), 2)
        for script in (COMPAT["LAUNCHER_SH"], COMPAT["EXTRA_WRAPPER_SH"] % "proton"):
            lines = [line.strip() for line in script.splitlines()]
            launches = [i for i, line in enumerate(lines) if line.startswith("bl_run") and "droiddeck-game-env" in line]
            self.assertTrue(launches)
            for index in launches:
                self.assertIn("bl_present", lines[max(0, index - 6):index])


if __name__ == "__main__":
    unittest.main()
