import os
from pathlib import Path
import re
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
        shutil.copytree(headers, folder / 'include/vulkan', ignore=shutil.ignore_patterns('*.hpp'))
        if (headers.parent / 'vk_video').is_dir():
            shutil.copytree(headers.parent / 'vk_video', folder / 'include/vk_video')
        cls.binary = folder / 'cache-test'
        cls.pipeline_binary = folder / 'pipeline-test'
        for source, binary in (('effects_source_cache_harness.c', cls.binary),
                               ('effects_pipeline_harness.c', cls.pipeline_binary)):
            build = subprocess.run([compiler, '-std=c11', '-pthread', '-I' + str(folder / 'include'),
                            '-I' + str(REPO / 'app/src/main/cpp/waylandcomp/src'),
                            '-I' + str(REPO / 'app/src/main/cpp/shaders'),
                            str(REPO / 'tools/tests' / source),
                            '-o', str(binary)], capture_output=True, text=True, timeout=120)
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

    def check_pipeline(self, case):
        result = subprocess.run([str(self.pipeline_binary), case], capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual('PASS', result.stdout.strip())

    def test_disabled_and_zero_strength_effects_create_no_pipelines(self):
        for case in ('off', 'zero-cas'):
            with self.subTest(case=case):
                self.check_pipeline(case)

    def test_cas_builds_one_pipeline_and_reuses_it(self):
        self.check_pipeline('cas')

    def test_switching_effects_only_adds_missing_pipelines(self):
        self.check_pipeline('switch')

    def test_switching_upscalers_reuses_shared_easu_and_rcas_pipelines(self):
        self.check_pipeline('scaling-switch')

    def test_mid_frame_settings_changes_apply_to_the_next_frame(self):
        self.check_pipeline('settings-snapshot')

    def test_resize_and_source_format_changes_preserve_pipeline_cache(self):
        for case in ('resize', 'source-format'):
            with self.subTest(case=case):
                self.check_pipeline(case)

    def test_output_format_changes_rebuild_only_needed_pipelines(self):
        self.check_pipeline('formats')

    def test_explicit_chain_teardown_discards_cached_pipelines(self):
        self.check_pipeline('device-reset')

    def test_an_unused_pipeline_failure_cannot_disable_the_selected_effect(self):
        self.check_pipeline('unused-failure')

    def test_required_pipeline_failure_draws_no_partial_chain_and_is_not_retried(self):
        for case in ('required-failure', 'late-failure', 'fragment-failure', 'vertex-failure', 'shared-failure'):
            with self.subTest(case=case):
                self.check_pipeline(case)

    def test_format_change_keeps_the_existing_failure_recovery_behavior(self):
        self.check_pipeline('failure-recovery')

    def test_each_needed_pipeline_and_fragment_failure_uses_the_whole_chain_fallback(self):
        for stage in ('pipeline-failure', 'shader-failure'):
            for pipeline in range(13):
                with self.subTest(stage=stage, pipeline=pipeline):
                    result = subprocess.run([str(self.pipeline_binary), stage, str(pipeline)],
                                            capture_output=True, text=True, timeout=10)
                    self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                    self.assertEqual('PASS', result.stdout.strip())

    def test_all_scaling_modes_and_effect_combinations_use_only_needed_pipelines(self):
        spatial = {3: [0], 4: [3, 4], 5: [3, 4], 6: [4], 7: [2], 8: [1]}
        effect_pipelines = [7, 8, 6, 5, 9, 10, 11, 12]
        for mode in range(9):
            for mask in (0, 1, 2, 4, 8, 16, 32, 64, 128, 12, 255):
                for upscale in (False, True):
                    with self.subTest(mode=mode, mask=mask, upscale=upscale):
                        selected = spatial.get(mode, []) if upscale or mode == 6 else []
                        effects = [p for i, p in enumerate(effect_pipelines) if mask & (1 << i)]
                        draws = 2 * (len(selected) + len(effects))
                        result = subprocess.run([
                            str(self.pipeline_binary), 'trace', str(mode), str(mask),
                            '270' if upscale else '135', '146' if upscale else '73', str(draws), '0',
                        ], capture_output=True, text=True, timeout=10)
                        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                        values = dict(re.findall(r'(\w+)=(\w+)', result.stdout))
                        self.assertEqual(len(set(selected + effects)), int(values['pipelines']))


if __name__ == '__main__':
    unittest.main()
