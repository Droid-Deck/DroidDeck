from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
WSI = ROOT / 'tools/vulkan-wsi'


def pins():
    values = {}
    for line in (WSI / 'source.env').read_text().splitlines():
        name, _, value = line.partition('=')
        values[name.strip()] = value.strip()
    return values


def fetch(url, revision, destination):
    subprocess.run(['git', 'init', '-q', str(destination)], check=True)
    subprocess.run(['git', '-C', str(destination), 'fetch', '-q', '--depth', '1', url, revision], check=True, timeout=120)
    subprocess.run(['git', '-C', str(destination), 'checkout', '-q', 'FETCH_HEAD'], check=True)


class VulkanWsiPatchesTest(unittest.TestCase):
    def test_build_applies_every_patch_and_records_the_combined_diff(self):
        build = (WSI / 'build.sh').read_text()
        self.assertIn('for patch in "$root"/tools/vulkan-wsi/patches/*.patch', build)
        self.assertIn('git -C "$work/source" diff > "$out/downstream.patch"', build)
        self.assertEqual([p.name for p in sorted((WSI / 'patches').glob('*.patch'))][:3],
                         ['0001-link-x11-external-memory.patch', '0002-x11-dri3-driver-exported-memory.patch',
                          '0003-x11-follow-gamescope-frame-limiter.patch'])

    @unittest.skipUnless(shutil.which('git') and shutil.which('gcc') and Path('/usr/include/drm/drm_fourcc.h').is_file(),
                         'needs git, gcc, network and the kernel drm headers')
    def test_wsialloc_serves_layouts_without_a_dma_buf_heap(self):
        if Path('/dev/dma_heap/system').exists():
            self.skipTest('a dma-buf heap is present; the no-heap path is not reachable here')
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            try:
                fetch('https://github.com/ginkage/vulkan-wsi-layer.git', pins()['WSI_REVISION'], work / 'source')
                fetch('https://github.com/KhronosGroup/Vulkan-Headers.git', pins()['VULKAN_HEADERS_REVISION'],
                      work / 'headers')
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
                self.skipTest('pinned sources are not reachable')
            for patch in sorted((WSI / 'patches').glob('*.patch')):
                subprocess.run(['git', '-C', str(work / 'source'), 'apply', str(patch)], check=True)
            source = work / 'source'
            subprocess.run(['gcc', '-std=gnu99', '-Wall', '-Wextra', '-Werror', '-DWSIALLOC_MEMORY_HEAP_NAME=system',
                            '-I' + str(source / 'util/wsialloc'), '-I' + str(source / 'util/drm'),
                            '-I' + str(source / 'util'), '-I/usr/include/drm', '-I' + str(work / 'headers/include'),
                            str(WSI / 'tests/wsialloc_no_heap_test.c'),
                            str(source / 'util/wsialloc/wsialloc_dma_buf_heaps.c'),
                            str(source / 'util/wsialloc/wsialloc_helpers.c'), str(source / 'util/drm/format_table.c'),
                            '-o', str(work / 'test')], check=True)
            subprocess.run([str(work / 'test')], check=True, timeout=20)


if __name__ == '__main__':
    unittest.main()
