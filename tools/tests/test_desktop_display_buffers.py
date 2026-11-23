from pathlib import Path
import re
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
DESKTOP = ROOT / "tools/linuxfs/desktop/droiddeck-desktop"
SOURCE = ROOT / "app/src/main/cpp/waylandcomp/src/display_buffers.c"


class DesktopDisplayBuffersTest(unittest.TestCase):
    def test_clearing_the_runtime_directory_keeps_what_the_compositor_serves(self):
        socket = re.search(r'#define DISPLAY_BUFFERS_SOCKET "([^"]+)"', SOURCE.read_text()).group(1)
        lines = [line for line in DESKTOP.read_text().splitlines() if line.startswith('find "$XDG_RUNTIME_DIR"')]
        self.assertEqual(len(lines), 1)
        with tempfile.TemporaryDirectory() as directory:
            runtime = Path(directory) / "runtime"
            (runtime / "dbus-1").mkdir(parents=True)
            kept = ["wayland-0", "wayland-0.lock", "keymap.xkb", socket]
            for name in kept + ["wayland-1", "pipewire-0", "dbus-1/session"]:
                (runtime / name).write_text("")
            subprocess.run(["bash", "-c", lines[0]], env={"XDG_RUNTIME_DIR": str(runtime), "PATH": "/usr/bin:/bin"},
                           check=True, timeout=20)
            self.assertEqual(sorted(path.name for path in runtime.iterdir()), sorted(kept))


if __name__ == "__main__":
    unittest.main()
