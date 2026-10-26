import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

TOOLS = Path(__file__).resolve().parents[1]
GPU = TOOLS / "linuxfs/desktop/droiddeck-gpu"
WINDOW = TOOLS / "linuxfs/overlay/usr/local/bin/droiddeck-gpu-window"
KEYS = ("MESA_VK_WSI_DEBUG", "SDL_VIDEODRIVER", "WAYLAND_DISPLAY", "BL_GPU_CHILD_WSI", "BL_DESKTOP_GPU_CHILD")


class DesktopGpuWindowTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.out = self.tmp / "gamescope.json"
        fake = self.tmp / "gamescope"
        fake.write_text("#!%s\nimport json, os, sys\njson.dump({'args': sys.argv[1:], 'env': {k: os.environ.get(k) for k in %r}}, open(%r, 'w'))\n"
                        % (sys.executable, KEYS, str(self.out)))
        fake.chmod(0o755)

    def run_gpu(self, **env):
        base = {k: v for k, v in os.environ.items() if k not in KEYS + ("BL_DEBUG_DIR", "BL_DESKTOP_GPU_WINDOW")}
        base.update(PATH="%s:%s" % (self.tmp, os.environ["PATH"]), BL_WIDTH="1280", BL_HEIGHT="720", **env)
        subprocess.run(["bash", str(GPU), "game.exe"], env=base, check=True, capture_output=True)
        return json.loads(self.out.read_text())

    def test_on_the_desktop_the_game_gets_a_window_of_its_own(self):
        run = self.run_gpu(WAYLAND_DISPLAY="wayland-1", MESA_VK_WSI_DEBUG="sw,linear")
        self.assertEqual(run["args"][:2], ["--backend", "sdl"])
        self.assertNotIn("-f", run["args"])
        self.assertEqual(run["args"][-2:], ["/usr/local/bin/droiddeck-gpu-window", "game.exe"])
        self.assertEqual(run["env"]["WAYLAND_DISPLAY"], "wayland-1")
        self.assertEqual(run["env"]["SDL_VIDEODRIVER"], "wayland")
        self.assertEqual(run["env"]["MESA_VK_WSI_DEBUG"], "sw")
        self.assertEqual(run["env"]["BL_GPU_CHILD_WSI"], "linear")
        self.assertEqual(run["env"]["BL_DESKTOP_GPU_CHILD"], "1")

    def test_full_screen_stays_available(self):
        for env in ({"WAYLAND_DISPLAY": "wayland-1", "BL_DESKTOP_GPU_WINDOW": "0"}, {"WAYLAND_DISPLAY": "wayland-0"}, {}):
            run = self.run_gpu(**env)
            self.assertEqual(run["args"][:2], ["--backend", "wayland"])
            self.assertIn("-f", run["args"])
            self.assertEqual(run["env"]["WAYLAND_DISPLAY"], "wayland-0")
            self.assertIsNone(run["env"]["MESA_VK_WSI_DEBUG"])
            self.assertIsNone(run["env"]["BL_GPU_CHILD_WSI"])

    def test_the_game_inside_the_window_gets_its_own_settings_back(self):
        script = "import json, os; print(json.dumps({k: os.environ.get(k) for k in %r}))" % (KEYS,)
        for saved, expected in (("linear", "linear"), ("", None)):
            env = {k: v for k, v in os.environ.items() if k not in KEYS + ("DISPLAY",)}
            env.update(BL_GPU_CHILD_WSI=saved, MESA_VK_WSI_DEBUG="sw", SDL_VIDEODRIVER="wayland")
            out = subprocess.run([sys.executable, str(WINDOW), sys.executable, "-c", script], env=env,
                                 check=True, capture_output=True, text=True).stdout
            child = json.loads(out)
            self.assertEqual(child["MESA_VK_WSI_DEBUG"], expected)
            self.assertEqual(child["SDL_VIDEODRIVER"], "x11")
            self.assertIsNone(child["BL_GPU_CHILD_WSI"])

    def test_outside_a_window_the_game_keeps_its_settings(self):
        script = "import os; print(os.environ.get('MESA_VK_WSI_DEBUG'), os.environ.get('SDL_VIDEODRIVER'))"
        env = {k: v for k, v in os.environ.items() if k not in KEYS + ("DISPLAY",)}
        env.update(MESA_VK_WSI_DEBUG="linear", SDL_VIDEODRIVER="x11")
        out = subprocess.run([sys.executable, str(WINDOW), sys.executable, "-c", script], env=env,
                             check=True, capture_output=True, text=True).stdout.split()
        self.assertEqual(out, ["linear", "x11"])


if __name__ == "__main__":
    unittest.main()
