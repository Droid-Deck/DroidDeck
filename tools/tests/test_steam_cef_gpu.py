import os
from pathlib import Path
import subprocess
import unittest

SESSION = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-session"


class SteamCefGpuTest(unittest.TestCase):
    def flags(self, **settings):
        source = SESSION.read_text()
        begin = source.index('    _bl_cef=(-cef-force-gpu "${bl_steam_presentation[@]}" -cef-use-gl=angle "-cef-use-angle=${bl_steam_angle:-vulkan}")')
        end = source.index('    if [ "${BL_DESKTOP:-}" = 1 ] && [ "${BL_STEAM_UI:-}" = desktop ]; then', begin)
        script = "set -u\nbl_steam_presentation=()\n" + source[begin:end] + 'printf "%s\\n" "${_bl_cef[@]}"\n'
        env = {k: v for k, v in os.environ.items() if k not in ("BL_STEAM_CEF_GPU", "steam_cef_gpu")}
        result = subprocess.run(["bash", "-c", script], env={**env, **settings},
                                capture_output=True, text=True, timeout=5, check=True)
        return [line for line in result.stdout.splitlines() if not line.startswith("== ")]

    def test_the_browser_ui_stays_on_the_gpu_by_default(self):
        self.assertEqual(self.flags(), ["-cef-force-gpu", "-cef-use-gl=angle", "-cef-use-angle=vulkan"])
        self.assertEqual(self.flags(BL_STEAM_CEF_GPU="1"), ["-cef-force-gpu", "-cef-use-gl=angle", "-cef-use-angle=vulkan"])

    def test_the_browser_ui_can_draw_in_software_to_leave_games_the_memory(self):
        self.assertEqual(self.flags(BL_STEAM_CEF_GPU="0"), ["-cef-disable-gpu", "-cef-disable-gpu-compositing"])


if __name__ == "__main__":
    unittest.main()
