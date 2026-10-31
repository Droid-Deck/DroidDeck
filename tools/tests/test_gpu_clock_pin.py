import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]


class GpuClockPinTest(unittest.TestCase):
    @unittest.skipUnless(sys.platform.startswith('linux') and shutil.which('cc'), 'requires a Linux C compiler')
    def test_constraint_lifecycle(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = pathlib.Path(tmp)
            (directory / 'android').mkdir()
            (directory / 'android/log.h').write_text(
                '#define ANDROID_LOG_WARN 5\n'
                'int __android_log_print(int, const char *, const char *, ...);\n')
            binary = directory / 'gpu-clock-pin-test'
            subprocess.run([
                'cc', '-std=c11', '-D_DEFAULT_SOURCE', '-D__user=', '-Wall', '-Wextra', '-Werror',
                '-I', str(directory), '-pthread',
                str(ROOT / 'tools/tests/test_gpu_clock_pin.c'),
                str(ROOT / 'app/src/main/cpp/waylandcomp/src/gpu_clock_pin.c'),
                '-Wl,--wrap=open,--wrap=close,--wrap=mmap,--wrap=munmap,--wrap=ioctl,--wrap=pthread_create',
                '-o', str(binary),
            ], check=True)
            subprocess.run([str(binary)], check=True, timeout=10)
