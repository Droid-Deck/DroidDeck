import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SESSION = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-session"
DESKTOP_GPU = Path(__file__).resolve().parents[1] / "linuxfs/desktop/droiddeck-gpu"


class GamescopeBackendTest(unittest.TestCase):
    def select(self, backend=None):
        source = SESSION.read_text()
        start = source.index('  backend=${BL_GAMESCOPE_BACKEND:-wayland}')
        end = source.index('\n', source.index('  args=(--backend ', start))
        script = 'width=1280; height=720; nested=1280\n' + source[start:end]
        script += '\nprintf "%s\\n" "${args[0]}" "${args[1]}" "${SDL_VIDEODRIVER:-unset}"\n'
        env = dict(os.environ)
        env.pop("BL_GAMESCOPE_BACKEND", None)
        env.pop("SDL_VIDEODRIVER", None)
        if backend is not None:
            env["BL_GAMESCOPE_BACKEND"] = backend
        return subprocess.run(["bash", "-c", script], env=env, text=True,
                              capture_output=True, timeout=5)

    def test_default_retains_the_wayland_backend(self):
        result = self.select()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["--backend", "wayland", "unset"], result.stdout.splitlines())

    def test_sdl_uses_the_host_wayland_vulkan_swapchain(self):
        result = self.select("sdl")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["--backend", "sdl", "wayland"], result.stdout.splitlines())

    def test_unknown_backend_fails_before_gamescope(self):
        result = self.select("drm")
        self.assertEqual(64, result.returncode)
        self.assertIn("unsupported gamescope backend: drm", result.stdout)


class DesktopGamescopeBackendTest(unittest.TestCase):
    def launch(self, backend=None):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            capture = root / "gamescope.json"
            gamescope = root / "gamescope"
            gamescope.write_text(
                f"#!{sys.executable}\n"
                "import json, os, sys\n"
                "from pathlib import Path\n"
                "Path(os.environ['GAMESCOPE_CAPTURE']).write_text(json.dumps({\n"
                "    'args': sys.argv[1:],\n"
                "    'sdl': os.environ.get('SDL_VIDEODRIVER'),\n"
                "    'gdk': os.environ.get('GDK_BACKEND'),\n"
                "    'qt': os.environ.get('QT_QPA_PLATFORM'),\n"
                "    'wayland': os.environ.get('WAYLAND_DISPLAY'),\n"
                "}))\n"
            )
            gamescope.chmod(0o755)
            env = dict(os.environ)
            for name in ("BL_GAMESCOPE_BACKEND", "BL_DEBUG_DIR", "BL_FPS", "BL_REFRESH", "BL_HDR"):
                env.pop(name, None)
            env.update(PATH=f"{root}{os.pathsep}{env.get('PATH', '')}",
                       GAMESCOPE_CAPTURE=str(capture), WAYLAND_DISPLAY="desktop-0", BL_DESKTOP_GPU_WINDOW="0",
                       SDL_VIDEODRIVER="dummy", GDK_BACKEND="wayland", QT_QPA_PLATFORM="wayland")
            if backend is not None:
                env["BL_GAMESCOPE_BACKEND"] = backend
            result = subprocess.run(["bash", str(DESKTOP_GPU), "demo", "one argument"],
                                    env=env, text=True, capture_output=True, timeout=5)
            return result, json.loads(capture.read_text()) if capture.exists() else None

    def assert_xwayland_application(self, capture):
        args = capture["args"]
        self.assertEqual(["env", "SDL_VIDEODRIVER=x11", "/usr/local/bin/droiddeck-gpu-window",
                          "demo", "one argument"], args[args.index("--") + 1:])
        self.assertEqual("x11", capture["gdk"])
        self.assertEqual("xcb", capture["qt"])
        self.assertEqual("wayland-0", capture["wayland"])

    def test_default_keeps_wayland_and_xwayland_application(self):
        result, capture = self.launch()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["--backend", "wayland"], capture["args"][:2])
        self.assertEqual("x11", capture["sdl"])
        self.assert_xwayland_application(capture)

    def test_sdl_uses_wayland_without_leaking_to_the_application(self):
        result, capture = self.launch("sdl")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["--backend", "sdl"], capture["args"][:2])
        self.assertEqual("wayland", capture["sdl"])
        self.assert_xwayland_application(capture)

    def test_unknown_backend_fails_before_gamescope(self):
        result, capture = self.launch("drm")
        self.assertEqual(64, result.returncode)
        self.assertIn("unsupported gamescope backend: drm", result.stderr)
        self.assertIsNone(capture)
