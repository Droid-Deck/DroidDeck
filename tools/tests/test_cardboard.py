from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / 'app' / 'src' / 'main' / 'cpp' / 'waylandcomp' / 'src'
TOOLS = ROOT / 'tools' / 'cardboard'


def spirv_words(header: str) -> list[str]:
    return re.findall(r'0x[0-9a-fA-F]{8}', header)


@unittest.skipUnless(shutil.which('cc'), 'needs a C compiler')
class CardboardParamsTest(unittest.TestCase):
    def test_clamp_and_push_constant_layout(self):
        with tempfile.TemporaryDirectory() as folder:
            binary = str(Path(folder) / 'test-cardboard')
            subprocess.run(['cc', '-std=c11', '-Wall', '-Wextra', '-Werror', '-O2', '-DCARDBOARD_HOST_TEST',
                            '-D_DEFAULT_SOURCE', f'-I{SRC}', f'-I{Path(folder)}', '-o', binary,
                            str(TOOLS / 'test_cardboard_params.c'), str(SRC / 'cardboard.c'), '-lm'],
                           check=True, cwd=folder, env=self._env(folder))
            result = subprocess.run([binary], capture_output=True, text=True, check=True, timeout=10)
            self.assertIn('PASS: cardboard params', result.stdout)

    @staticmethod
    def _env(folder):
        import os
        stub = Path(folder) / 'vulkan'
        stub.mkdir(exist_ok=True)
        (stub / 'vulkan.h').write_text('typedef struct VkDevice_T *VkDevice; typedef struct VkImage_T *VkImage;\n'
                                       'typedef struct VkCommandBuffer_T *VkCommandBuffer;\n'
                                       'typedef struct { int unused; } VkPhysicalDeviceMemoryProperties;\n')
        return dict(os.environ)


@unittest.skipUnless(shutil.which('glslangValidator'), 'needs glslangValidator')
class CardboardShaderTest(unittest.TestCase):
    def test_committed_spirv_matches_the_glsl(self):
        with tempfile.TemporaryDirectory() as folder:
            out = Path(folder) / 'cardboard_frag.h'
            subprocess.run(['glslangValidator', '-V', '--vn', 'cardboard_frag_code', '-o', str(out),
                            str(SRC / 'cardboard.frag')], check=True, capture_output=True)
            self.assertEqual(spirv_words(out.read_text()), spirv_words((SRC / 'cardboard_frag.h').read_text()),
                             'cardboard_frag.h is stale: regenerate it from cardboard.frag (see cardboard.c)')

    def test_push_constants_fit_the_guaranteed_range(self):
        glsl = (SRC / 'cardboard.frag').read_text()
        block = glsl[glsl.index('uniform PC {'):glsl.index('} pc;')]
        self.assertLessEqual(16 * len(re.findall(r'\bvec4\b', block)), 128)


if __name__ == '__main__':
    unittest.main()
