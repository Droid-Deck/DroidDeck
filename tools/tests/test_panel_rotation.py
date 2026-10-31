from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / 'app' / 'src' / 'main' / 'cpp' / 'waylandcomp' / 'src'


def spirv_words(header):
    return re.findall(r'0x[0-9a-fA-F]{8}', header)


@unittest.skipUnless(shutil.which('glslangValidator'), 'needs glslangValidator')
class RotateShaderTest(unittest.TestCase):
    def test_committed_spirv_matches_the_glsl(self):
        with tempfile.TemporaryDirectory() as folder:
            out = Path(folder) / 'rotate_vert.h'
            subprocess.run(['glslangValidator', '-V', '--vn', 'rotate_vert_code', '-o', str(out),
                            str(SRC / 'rotate.vert')], check=True, capture_output=True)
            self.assertEqual(spirv_words(out.read_text()), spirv_words((SRC / 'rotate_vert.h').read_text()))

    def test_push_constants_match_the_blend_pass(self):
        rotate = (SRC / 'rotate.vert').read_text()
        blend = (SRC / 'blend.vert').read_text()
        pattern = r'uniform PC \{[^}]*\}'
        self.assertEqual(re.search(pattern, blend).group(0), re.search(pattern, rotate).group(0))


class PanelRotationSourceTest(unittest.TestCase):
    def setUp(self):
        self.present = (SRC / 'vk_present.c').read_text()

    def function(self, name):
        start = self.present.index(name)
        return self.present[start:self.present.index('\n}\n', start)]

    def test_only_the_final_pass_touches_swapchain_images_while_rotating(self):
        for name in ('static void record_screen_blit(', 'static int render_impl('):
            self.assertNotIn('g_images[img]', self.function(name), name)

    def test_rotation_is_limited_to_quarter_turns_and_can_be_switched_off(self):
        body = self.function('static int prerotate_degrees(')
        self.assertIn('debug.droiddeck.prerotate', body)
        self.assertIn('ROTATE_90_BIT_KHR) return 90', body)
        self.assertIn('ROTATE_270_BIT_KHR) return 270', body)
        self.assertNotIn('ROTATE_180', body)
        self.assertIn('if (g_prerot_failed) return 0;', body)

    def test_a_refused_rotated_swapchain_falls_back_to_the_display_rotating(self):
        body = self.function('static int swap_init_locked(void) {')
        self.assertIn('cr != VK_SUCCESS && g_prerot', body)
        self.assertIn('sci.imageExtent = g_extent;', body)


if __name__ == '__main__':
    unittest.main()
