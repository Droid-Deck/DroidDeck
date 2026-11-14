"""Exercise production DMA-BUF release/ownership paths without a GPU.

Wayland resources and OS timer/Android AHB endpoints are fake; list macros and
the compositor/AHB lifecycle functions come directly from the repository.
"""
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

from test_vk_pending_completion import function

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / 'app/src/main/cpp/waylandcomp/src'


class VkDmabufQuarantineTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cc = shutil.which(os.environ.get('CC', 'cc'))
        if not cc:
            raise AssertionError('C compiler required')
        # Optional old-head input allows a genuine negative control using the
        # identical harness, rather than a model of the previous behaviour.
        source_dir = Path(os.environ.get('DROIDDECK_DMABUF_TEST_SOURCE', SRC))
        comp = (source_dir / 'compositor.c').read_text()
        ahb = (source_dir / 'ahb_swapchain.c').read_text()
        vk = (source_dir / 'vk_present.c').read_text()
        names = ['get_dmabuf', 'pending_release_free',
                 'pending_releases_forget_surface', 'on_pending_release_buffer_destroyed',
                 'arm_release_timer', 'on_release_timer', 'release_buffer',
                 'dmabuf_buffer_unref', 'drop_dmabuf', 'on_dmabuf_destroyed',
                 'droiddeck_dmabuf_ahb_slot', 'droiddeck_dmabuf_unref',
                 'droiddeck_release_buffer']
        helpers = []
        if re.search(r'\bstatic int buffer_release_blocked\(', comp):
            helpers = [function(comp, 'buffer_release_blocked')]
            if re.search(r'\bstatic void send_buffer_release_at\(', comp):
                # send_buffer_release is a macro over send_buffer_release_at (with buffer tracing).
                helpers += ['static void buf_trace_release(struct wl_resource *b, int line) { (void)b; (void)line; }',
                            '#define send_buffer_release(b) send_buffer_release_at((b), __LINE__)',
                            function(comp, 'send_buffer_release_at')]
            else:
                helpers += [function(comp, 'send_buffer_release')]
        else:
            helpers = ['static int __attribute__((unused)) buffer_release_blocked(struct wl_resource *b) { (void)b; return 0; }']
        if re.search(r'\bstatic int buffer_shown_elsewhere\(', comp):
            # One surface per buffer in these scenarios: never shown elsewhere.
            helpers += ['static int buffer_shown_elsewhere(struct wl_resource *b, const struct surface *e) '
                        '{ (void)b; (void)e; return 0; }']
        if re.search(r'\bvolatile int g_release_unpaced;', comp):
            # Releases stay paced in these scenarios (fps limit -1 sends them at once).
            helpers.insert(0, 'static int g_release_unpaced;')
        query = function(vk, 'vkp_completion_unknown') if 'int vkp_completion_unknown(' in vk else \
            'int vkp_completion_unknown(void) { return g_dev_state == -3; }'
        comp_functions = [function(comp, names[0])] + helpers + [function(comp, n) for n in names[1:]]
        ahb_functions = [function(ahb, n) for n in ('buf_free', 'maybe_free',
                         'on_buffer_resource_destroyed', 'send_deferred_release',
                         'on_fence_readable', 'ahb_swapchain_defer_release',
                         'ahb_swapchain_surface_gone')]
        structs = re.search(r'struct pending_release \{.*?\n\};', comp, re.S).group() + '\n' + \
            re.search(r'struct ahb_buf \{.*?\n\};', ahb, re.S).group()
        harness = Path(__file__).with_name('vk_dmabuf_quarantine_test.c').read_text()
        harness = harness.replace('/* PRODUCTION_STRUCTS */', structs)
        harness = harness.replace('/* PRODUCTION_FUNCTIONS */', '\n'.join([query] + comp_functions + ahb_functions))
        cls.tmp = tempfile.TemporaryDirectory(prefix='droiddeck-dmabuf-')
        cls.addClassCleanup(cls.tmp.cleanup)
        probe = Path(cls.tmp.name) / 'probe.c'
        probe.write_text(harness)
        cls.binary = probe.with_suffix('')
        built = subprocess.run([cc, '-std=c11', '-Wall', '-Wextra', '-Werror',
                                '-Wno-unused-parameter', '-UNDEBUG',
                                '-I', str(SRC.parent / 'prebuilt/include'),
                                str(probe), '-o', str(cls.binary)], capture_output=True, text=True)
        if built.returncode:
            raise AssertionError(built.stdout + built.stderr)

    def run_scenario(self, name):
        result = subprocess.run([str(self.binary), name], capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_replacement_retains_reference_and_release(self):
        self.run_scenario('replacement')

    def test_surface_destroy_retains_reference_and_release(self):
        self.run_scenario('surface-gone')

    def test_queued_release_is_held_and_timer_disarmed(self):
        self.run_scenario('queued')

    def test_mixed_queue_releases_only_copied_shm(self):
        self.run_scenario('mixed')

    def test_client_destroy_retains_backing_fd(self):
        self.run_scenario('client-gone')

    def test_ahb_callback_retains_ownership_and_release(self):
        self.run_scenario('ahb')

    def test_active_client_destroy_retains_reference(self):
        self.run_scenario('active-client-gone')

    def test_healthy_ahb_callback_and_cleanup(self):
        self.run_scenario('ahb-healthy')

    def test_device_lost_ahb_callback_and_cleanup(self):
        self.run_scenario('ahb-device-lost')

    def test_healthy_release_and_reference_cleanup(self):
        self.run_scenario('healthy')

    def test_genuine_device_loss_keeps_existing_cleanup(self):
        self.run_scenario('device-lost')


if __name__ == '__main__':
    unittest.main()
