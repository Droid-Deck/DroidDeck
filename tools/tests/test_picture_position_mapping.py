import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]


class GameTopMappingTest(unittest.TestCase):
    def test_letterbox_alignment_and_touch_inverse(self):
        cc = shutil.which(os.environ.get('CC', 'cc'))
        if not cc:
            self.skipTest('A host C compiler is required')
        source = (REPO / 'app/src/main/cpp/waylandcomp/src/vk_present.c').read_text()
        header = (REPO / 'app/src/main/cpp/waylandcomp/src/vk_present.h').read_text()
        enums = header[header.index('enum vkp_scale_mode'):header.index('void vk_present_set_scale_mode')]
        state = source[source.index('static volatile int g_mode'):source.index('static int64_t g_swap_retry_at_ns')]
        def function(signature):
            start = source.index(signature)
            return source[start:source.index('\n}\n', start) + 3]
        funcs = '\n'.join(function(name) for name in ['static const char *mode_name(',
            'static const char *align_name(', 'static void update_map(', 'int vkp_output_to_scene(',
            'int vkp_scene_to_output(', 'int vkp_game_picture_rect(', 'static int map_draw(',
            'static int draw_to_blit(', 'int vkp_map_draw(', 'int vkp_map_rect('])
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            code = '#include <assert.h>\n#include <math.h>\n#include <stdio.h>\n#include <string.h>\n#include <pthread.h>\n'
            code += '#define droiddeck_log(...) ((void)0)\nstatic struct { unsigned width, height; } g_extent;\n'
            code += r'''
#define VK_IMAGE_ASPECT_COLOR_BIT 1
typedef struct { unsigned aspectMask, mipLevel, baseArrayLayer, layerCount; } VkImageSubresourceLayers;
typedef struct { int x, y, z; } VkOffset3D;
typedef struct {
    VkImageSubresourceLayers srcSubresource;
    VkOffset3D srcOffsets[2];
    VkImageSubresourceLayers dstSubresource;
    VkOffset3D dstOffsets[2];
} VkImageBlit;
struct vkp_image { int w, h; };
struct vkp_draw {
    struct vkp_image *img;
    float sx, sy, sw, sh;
    int dx, dy, dw, dh;
    int blend;
};
'''
            code += enums + state + funcs
            code += r'''
static void near(double a, double b) { assert(fabs(a - b) < 0.01); }
static void check(int sw, int sh, int ow, int oh, int alignment) {
    g_extent.width = ow; g_extent.height = oh;
    g_mode = VKP_MODE_FIT; g_align = alignment;
    update_map(sw, sh);
    float rect[4];
    assert(vkp_game_picture_rect(rect));
    double top = alignment == VKP_ALIGN_PICTURE_TOP ? 0 : oh - sh * g_map.ky;
    near(rect[0], g_map.off_x); near(rect[1], top);
    near(rect[2], g_map.off_x + sw * g_map.kx);
    near(rect[3], top + sh * g_map.ky);
    near(g_map.off_y, top);
    double sx, sy;
    assert(vkp_output_to_scene(g_map.off_x + sw * g_map.kx / 2,
                              top + sh * g_map.ky / 2, &sx, &sy));
    near(sx, sw / 2.0); near(sy, sh / 2.0);
    near(g_map.kx, g_map.ky);
    assert(sh * g_map.ky <= oh + 0.01);
    g_align = VKP_ALIGN_CENTER; update_map(sw, sh);
    assert(!vkp_game_picture_rect(rect));
    near(g_map.off_y, (int)((oh - sh * g_map.ky) * 0.5));
}
static void check_height_fit(void) {
    int blit[8];
    double sx, sy, ox, oy;
    g_extent.width = 2400; g_extent.height = 1080;
    g_mode = VKP_MODE_HEIGHT_FIT; g_align = VKP_ALIGN_CENTER;

    /* A wider scene keeps the full height and is cropped equally on both sides. */
    update_map(2560, 1080);
    near(g_map.kx, 1.0); near(g_map.ky, 1.0);
    near(g_map.off_x, -80.0); near(g_map.off_y, 0.0);
    assert(vkp_output_to_scene(0, 0, &sx, &sy)); near(sx, 80.0); near(sy, 0.0);
    assert(vkp_output_to_scene(2400, 1080, &sx, &sy)); near(sx, 2480.0); near(sy, 1080.0);
    assert(vkp_scene_to_output(0, 540, &ox, &oy)); near(ox, -80.0); near(oy, 540.0);
    assert(vkp_scene_to_output(80, 0, &ox, &oy)); near(ox, 0.0); near(oy, 0.0);
    assert(vkp_scene_to_output(2480, 1080, &ox, &oy)); near(ox, 2400.0); near(oy, 1080.0);
    assert(vkp_scene_to_output(2560, 540, &ox, &oy)); near(ox, 2480.0); near(oy, 540.0);
    assert(vkp_map_rect(2560, 1080, 2560, 1080, blit));
    assert(blit[0] == 80 && blit[1] == 0 && blit[2] == 2480 && blit[3] == 1080);
    assert(blit[4] == 0 && blit[5] == 0 && blit[6] == 2400 && blit[7] == 1080);

    /* A narrower scene keeps the full height and is pillarboxed horizontally. */
    update_map(1280, 720);
    near(g_map.kx, 1.5); near(g_map.ky, 1.5);
    near(g_map.off_x, 240.0); near(g_map.off_y, 0.0);
    assert(vkp_output_to_scene(0, 540, &sx, &sy)); near(sx, -160.0); near(sy, 360.0);
    assert(vkp_output_to_scene(240, 0, &sx, &sy)); near(sx, 0.0); near(sy, 0.0);
    assert(vkp_scene_to_output(1280, 720, &ox, &oy)); near(ox, 2160.0); near(oy, 1080.0);
    assert(vkp_map_rect(1280, 720, 1280, 720, blit));
    assert(blit[0] == 0 && blit[1] == 0 && blit[2] == 1280 && blit[3] == 720);
    assert(blit[4] == 240 && blit[5] == 0 && blit[6] == 2160 && blit[7] == 1080);

    /* Matching aspects fill the output without cropping or bars. */
    update_map(1920, 864);
    near(g_map.kx, 1.25); near(g_map.ky, 1.25);
    near(g_map.off_x, 0.0); near(g_map.off_y, 0.0);
}
int main(void) {
    for (int alignment = VKP_ALIGN_PICTURE_TOP; alignment <= VKP_ALIGN_PICTURE_BOTTOM; alignment++) {
        check(1240, 1100, 2480, 2200, alignment);
        check(1280, 720, 2480, 2200, alignment);
        check(1024, 768, 2480, 2200, alignment);
        check(1920, 1080, 2400, 1080, alignment);
        check(1240, 1100, 2480, 1394, alignment);
    }
    g_align = VKP_ALIGN_TOP; update_map(1280, 720);
    assert(g_map.rh == 697 && g_map.ry == 0);
    check_height_fit();
    puts("PASS");
}
'''
            (tmp / 'map.c').write_text(code)
            subprocess.run([cc, '-std=c11', str(tmp / 'map.c'), '-lm', '-o', str(tmp / 'map')], check=True, capture_output=True, text=True)
            result = subprocess.run([str(tmp / 'map')], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == '__main__':
    unittest.main()
