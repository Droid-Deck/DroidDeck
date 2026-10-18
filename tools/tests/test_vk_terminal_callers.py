"""Fault-inject complete production render/blit/upload callers, without a GPU.

Uses Vulkan-Headers from the Android NDK (as installed by APK CI), or the host
Vulkan development package. Only Android platform declarations and the external
effect/frame-generation modules are mocked; vk_present.c is compiled unmodified.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from test_vk_pending_completion import function

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'app/src/main/cpp/waylandcomp/src/vk_present.c'


def vulkan_include():
    candidates = [Path(p) for p in [os.environ.get('DROIDDECK_VULKAN_INCLUDE', ''),
                                  '/usr/include', '/usr/local/include',
                                  '/opt/homebrew/include'] if p]
    for name in ('ANDROID_HOME', 'ANDROID_SDK_ROOT', 'ANDROID_NDK_HOME', 'NDK'):
        if os.environ.get(name):
            root = Path(os.environ[name])
            candidates += sorted(root.glob('ndk/*/toolchains/llvm/prebuilt/*/sysroot/usr/include'), reverse=True)
            candidates += list(root.glob('toolchains/llvm/prebuilt/*/sysroot/usr/include'))
    return next((p for p in candidates if (p / 'vulkan/vulkan.h').is_file()), None)


class VkTerminalCallersTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        headers = vulkan_include()
        if headers is None:
            raise unittest.SkipTest('NDK or host Vulkan-Headers required for full production-C caller tests')
        cc = shutil.which(os.environ.get('CC', 'cc'))
        if not cc:
            raise AssertionError('C compiler required')
        cls.tmp = tempfile.TemporaryDirectory(prefix='droiddeck-callers-')
        cls.addClassCleanup(cls.tmp.cleanup)
        tmp = Path(cls.tmp.name)
        (tmp / 'android').mkdir()
        (tmp / 'android/native_window.h').write_text('''
#pragma once
#include <stdint.h>
typedef struct ANativeWindow ANativeWindow;
int32_t ANativeWindow_getWidth(ANativeWindow *);
int32_t ANativeWindow_getHeight(ANativeWindow *);
void ANativeWindow_release(ANativeWindow *);
''')
        (tmp / 'android/hardware_buffer.h').write_text('#pragma once\ntypedef struct AHardwareBuffer AHardwareBuffer;\n')
        (tmp / 'android/log.h').write_text('''
#pragma once
#define ANDROID_LOG_INFO 4
int __android_log_print(int, const char *, const char *, ...);
''')
        (tmp / 'sys').mkdir()
        (tmp / 'sys/system_properties.h').write_text('''
#pragma once
#define PROP_VALUE_MAX 92
int __system_property_get(const char *, char *);
''')
        (tmp / 'wayland-server.h').write_text('#pragma once\nstruct wl_display; struct wl_resource; struct wl_client;\n')
        compositor = (SOURCE.parent / 'compositor.c').read_text()
        callers = '\n'.join(function(compositor, n) for n in ('buffer_release_blocked', 'send_buffer_release', 'take_shm', 'take_pixel'))
        harness = (Path(__file__).with_name('vk_terminal_callers_test.c')).read_text()
        probe = tmp / 'probe.c'
        probe.write_text(harness.replace('/* COMPOSITOR_CALLERS */', callers))
        cls.binary = tmp / 'probe'
        built = subprocess.run([cc, '-std=c11', '-Wall', '-Wextra', '-Werror',
                                '-Wno-unused-parameter', '-Wno-missing-field-initializers', '-UNDEBUG',
                                '-I', str(tmp), '-idirafter', str(headers), '-I', str(SOURCE.parent),
                                str(probe), '-pthread', '-o', str(cls.binary)],
                               capture_output=True, text=True)
        if built.returncode:
            raise AssertionError(built.stdout + built.stderr)

    def run_scenario(self, name):
        ran = subprocess.run([str(self.binary), name], capture_output=True, text=True, timeout=10)
        self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)

    def test_missing_android_properties_keep_non_quest_default(self):
        self.run_scenario('device-properties')

    def test_first_acquire_idle_failure(self):
        self.run_scenario('first-out-of-date')

    def test_first_surface_lost_idle_failure(self):
        self.run_scenario('first-surface-lost')

    def test_later_framegen_acquire_idle_failure(self):
        self.run_scenario('later-out-of-date')

    def test_later_framegen_surface_lost_idle_failure(self):
        self.run_scenario('later-surface-lost')

    def test_finite_blit_timeout_same_size_shm_upload(self):
        self.run_scenario('shm-timeout')

    def test_finite_blit_timeout_same_size_pixel_upload(self):
        self.run_scenario('pixel-timeout')

    def test_normal_present(self):
        self.run_scenario('normal')

    def test_normal_framegen_present(self):
        self.run_scenario('framegen')

    def test_healthy_later_acquire_timeout_drains_work(self):
        self.run_scenario('later-timeout')

    def test_real_device_lost_on_acquire(self):
        self.run_scenario('acquire-device-lost')

    def test_real_device_lost_on_present(self):
        self.run_scenario('present-device-lost')

    def test_pass_copy_and_abort_terminal_entry(self):
        self.run_scenario('pass-terminal')


if __name__ == '__main__':
    unittest.main()
