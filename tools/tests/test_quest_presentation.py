from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(shutil.which('cc'), 'needs a C compiler')
class QuestBackdropTest(unittest.TestCase):
    def test_first_frame_requires_client_content(self):
        # Compile the real entrypoint with controlled layer outcomes, including the
        # successful no-op that previously dismissed the loading UI on a black frame.
        source = (ROOT / 'app/src/main/cpp/waylandcomp/src/vk_present.c').read_text()
        start = source.index('\nint vkp_render(')
        end = source.index('\nint vkp_render_hdr(', start)
        with tempfile.TemporaryDirectory() as folder:
            work = Path(folder)
            (work / 'vkp_render_under_test.inc').write_text(source[start:end])
            binary = work / 'test-first-frame'
            subprocess.run(['cc', '-std=c11', '-Wall', '-Wextra',
                            '-Wno-unused-parameter', '-Wno-unused-function', f'-I{work}',
                            str(ROOT / 'tools/quest/test_first_frame.c'), '-o', str(binary)],
                           check=True, capture_output=True, text=True)
            result = subprocess.run([str(binary)], capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('PASS: Quest first-frame', result.stdout)

    def test_backdrop_lifecycle_and_failed_buffer_initialization(self):
        with tempfile.TemporaryDirectory() as folder:
            work = Path(folder)
            android = work / 'android'
            android.mkdir()
            (android / 'rect.h').write_text('''#pragma once
#include <stdint.h>
typedef struct ARect { int32_t left, top, right, bottom; } ARect;
''')
            (android / 'native_window.h').write_text('''#pragma once
typedef struct ANativeWindow ANativeWindow;
''')
            (android / 'hardware_buffer.h').write_text('''#pragma once
#include <stdint.h>
#include "rect.h"
typedef struct AHardwareBuffer AHardwareBuffer;
typedef struct { uint32_t width,height,layers,format; uint64_t usage;
    uint32_t stride,rfu0; uint64_t rfu1; } AHardwareBuffer_Desc;
#define AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM 1
#define AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM 0x2b
#define AHARDWAREBUFFER_USAGE_CPU_READ_RARELY 2ULL
#define AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY 32ULL
#define AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE 256ULL
#define AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT 512ULL
#define AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY 2048ULL
int AHardwareBuffer_allocate(const AHardwareBuffer_Desc *, AHardwareBuffer **);
void AHardwareBuffer_describe(const AHardwareBuffer *, AHardwareBuffer_Desc *);
int AHardwareBuffer_lock(AHardwareBuffer *, uint64_t, int32_t, const ARect *, void **);
int AHardwareBuffer_unlock(AHardwareBuffer *, int32_t *);
void AHardwareBuffer_release(AHardwareBuffer *);
''')
            binary = work / 'test-presentation'
            subprocess.run(['cc', '-std=c11', '-O1', '-g', '-Wall', '-Wextra',
                            '-Wno-unused-parameter', '-Wno-unused-function',
                            '-ffunction-sections', '-fdata-sections', '-Wl,--gc-sections',
                            '-fsanitize=address,undefined', f'-I{work}',
                            f'-I{ROOT}/app/src/main/cpp/waylandcomp/prebuilt/include',
                            str(ROOT / 'tools/quest/test_presentation.c'), '-ldl', '-pthread',
                            '-o', str(binary)], check=True, capture_output=True, text=True)
            result = subprocess.run([str(binary)], capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('PASS: Quest backdrop', result.stdout)


if __name__ == '__main__':
    unittest.main()
