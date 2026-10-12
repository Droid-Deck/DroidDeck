import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


REPO = Path(__file__).resolve().parents[2]


class EffectsSourceCacheTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        compiler = shutil.which(os.environ.get('CC', 'cc'))
        if compiler is None:
            raise unittest.SkipTest('A host C compiler is required for the Vulkan cache test')
        candidates = [Path('/usr/include/vulkan'), Path('/opt/homebrew/include/vulkan')]
        for name in ('ANDROID_NDK_HOME', 'ANDROID_NDK_ROOT', 'NDK_HOME'):
            if os.environ.get(name):
                candidates.extend(Path(os.environ[name]).glob('toolchains/llvm/prebuilt/*/sysroot/usr/include/vulkan'))
        for sdk in {Path(os.environ.get('ANDROID_HOME', '/nonexistent')),
                    Path(os.environ.get('ANDROID_SDK_ROOT', '/nonexistent')),
                    Path.home() / 'Library/Android/sdk', Path.home() / 'Android/Sdk'}:
            candidates.extend(sdk.glob('ndk/*/toolchains/llvm/prebuilt/*/sysroot/usr/include/vulkan'))
        headers = next((p for p in candidates if (p / 'vulkan.h').is_file()), None)
        if headers is None:
            raise unittest.SkipTest('Vulkan headers from the Android NDK or system SDK are required')
        cls.temp = tempfile.TemporaryDirectory(prefix='droiddeck-effects-cache-')
        cls.addClassCleanup(cls.temp.cleanup)
        folder = Path(cls.temp.name)
        # Copy only Vulkan headers so Android's libc headers cannot shadow the host's libc.
        shutil.copytree(headers, folder / 'include/vulkan', ignore=shutil.ignore_patterns('*.hpp'))
        if (headers.parent / 'vk_video').is_dir():
            shutil.copytree(headers.parent / 'vk_video', folder / 'include/vk_video')
        cls.binary = folder / 'cache-test'
        build = subprocess.run([compiler, '-std=c11', '-pthread', '-I' + str(folder / 'include'),
                        '-I' + str(REPO / 'app/src/main/cpp/waylandcomp/src'),
                        '-I' + str(REPO / 'app/src/main/cpp/shaders'),
                        str(REPO / 'tools/tests/effects_source_cache_harness.c'),
                        '-o', str(cls.binary)], capture_output=True, text=True)
        if build.returncode:
            raise RuntimeError(build.stderr)

    def check_cache(self, case):
        result = subprocess.run([str(self.binary), case], check=True, capture_output=True, text=True)
        self.assertEqual('PASS', result.stdout.strip())

    def test_recycled_image_handle_gets_a_new_view_and_descriptor(self):
        self.check_cache('recycled')

    def test_unrelated_and_null_images_preserve_the_current_view(self):
        self.check_cache('unrelated')

    def test_repeated_owner_invalidation_does_not_double_free(self):
        self.check_cache('repeated')

    def test_resize_replaces_the_cached_view_and_descriptor(self):
        self.check_cache('resize')


if __name__ == '__main__':
    unittest.main()
