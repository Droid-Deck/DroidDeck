from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


@unittest.skipUnless(shutil.which('g++'), 'needs a Linux C++ compiler')
class VrWsiTest(unittest.TestCase):
    def test_compositor_handoff_and_frame_transport(self):
        repository = Path(__file__).resolve().parents[2]
        headers = next((p for p in (Path('/usr/include'), repository / 'build/vr/include')
                        if (p / 'vulkan/vulkan.h').is_file()), None)
        if headers is None:
            self.skipTest('needs Vulkan headers')
        with tempfile.TemporaryDirectory() as directory:
            for name in ('test_wsi_capture', 'test_xr_shared', 'test_xr_frames', 'test_xr_gpu'):
                with self.subTest(name=name):
                    executable = Path(directory) / name
                    subprocess.run(['g++', '-std=c++17', '-O2', '-pthread', '-Wall', '-Wextra', '-Werror',
                                    '-Wno-missing-field-initializers', '-I' + str(headers),
                                    str(repository / 'tools/vr/tests' / (name + '.cpp')), '-o', str(executable)], check=True)
                    subprocess.run([str(executable)], check=True, timeout=20)
