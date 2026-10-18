import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest


HELPER = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin/droiddeck-steamvr'
AFFECTED_ICD = '/data/user/0/com.droiddeck.launcher/files/linux_vulkan_drivers/DD-Turnip_v0.2.0/icd.json'


@unittest.skipUnless(shutil.which('sh'), 'requires a POSIX shell')
class SteamVrGraphicsTest(unittest.TestCase):
    def launch(self, model, debug=None, action='start', preload=None, icd=None, driver_files=None):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bindir = root / 'bin'
            bindir.mkdir()
            runtime = root / 'steamvr/bin/linuxarm64'
            runtime.mkdir(parents=True)
            (runtime / 'vrstartup').touch(mode=0o755)
            driver = root / 'driver/droiddeck/bin/linuxarm64'
            driver.mkdir(parents=True)
            (driver / 'driver_droiddeck.so').touch()
            result = root / 'launch-env'
            preloads = root / 'launch-preload'
            scripts = {
                'cat': 'if [ "$1" = /sys/class/kgsl/kgsl-3d0/gpu_model ]; then printf "%s" "$TEST_GPU_MODEL"; else exec /bin/cat "$@"; fi',
                'pgrep': 'exit 1',
                'sleep': 'exit 0',
                'setsid': 'printf "%s" "$LD_PRELOAD" > "$TEST_LAUNCH_PRELOAD"; printf "%s" "${TU_DEBUG-unset}" > "$TEST_LAUNCH_ENV"',
            }
            for name, script in scripts.items():
                path = bindir / name
                path.write_text('#!/bin/sh\n' + script + '\n')
                path.chmod(0o755)
            env = dict(os.environ, HOME=str(root), XDG_RUNTIME_DIR=str(root / 'run'),
                       STEAMVR=str(root / 'steamvr'), DROIDDECK_VR_DIR=str(root / 'driver'),
                       PATH=str(bindir) + os.pathsep + os.environ['PATH'],
                       TEST_GPU_MODEL=model, TEST_LAUNCH_ENV=str(result), TEST_LAUNCH_PRELOAD=str(preloads))
            env.pop('TU_DEBUG', None)
            env.pop('LD_PRELOAD', None)
            env.pop('VK_ICD_FILENAMES', None)
            env.pop('VK_DRIVER_FILES', None)
            if debug is not None:
                env['TU_DEBUG'] = debug
            if preload is not None:
                env['LD_PRELOAD'] = preload
            if icd is not None:
                env['VK_ICD_FILENAMES'] = icd
            if driver_files is not None:
                env['VK_DRIVER_FILES'] = driver_files
            run = subprocess.run(['sh', str(HELPER), action], env=env,
                                 capture_output=True, text=True, timeout=10)
            self.assertEqual(run.returncode, 0, run.stderr)
            until = time.monotonic() + 2
            while not result.exists() and time.monotonic() < until:
                time.sleep(0.01)
            self.assertEqual(preloads.read_text(), str(root / 'driver/layer/libdroiddeck_vr_scheduling.so') +
                             (':' + preload if preload else ''))
            return result.read_text()

    def test_adreno_840_start_removes_forced_sysmem(self):
        self.assertEqual(self.launch('Adreno840v2', 'sysmem'), '')
        self.assertEqual(self.launch('Adreno840v2'), '')

    def test_preserves_other_debug_flags_and_removes_repeated_sysmem(self):
        self.assertEqual(self.launch('Adreno840v2', 'sysmem,forcecb,sysmem,noubwc'), 'forcecb,noubwc')
        self.assertEqual(self.launch('Adreno840v2', 'forcecb sysmem flushall'), 'forcecb,flushall')

    def test_library_launch_uses_the_same_graphics_setup(self):
        self.assertEqual(self.launch('Adreno840v2', 'sysmem,forcecb', action='launch'), 'forcecb')

    def test_scheduling_preload_preserves_existing_preloads(self):
        self.assertEqual(self.launch('Adreno840v2', 'sysmem', action='launch',
                                    preload='/existing/preload.so'), '')

    def test_other_and_unknown_gpus_keep_their_environment(self):
        self.assertEqual(self.launch('Adreno740'), 'unset')
        self.assertEqual(self.launch('Adreno740', 'sysmem,forcecb'), 'sysmem,forcecb')
        self.assertEqual(self.launch('', 'sysmem'), 'sysmem')

    def test_affected_driver_uses_flushall_for_app_and_library_launches(self):
        for action in ('start', 'launch'):
            with self.subTest(action=action):
                self.assertEqual(self.launch('Adreno840v2', 'sysmem', action=action,
                                             icd=AFFECTED_ICD), 'flushall')
                self.assertEqual(self.launch('Adreno840v2', action=action,
                                             driver_files=AFFECTED_ICD), 'flushall')

    def test_affected_driver_preserves_flags_without_duplicating_flushall(self):
        self.assertEqual(self.launch('Adreno840v2', 'sysmem,forcecb,noubwc',
                                     icd=AFFECTED_ICD), 'forcecb,noubwc,flushall')
        self.assertEqual(self.launch('Adreno840v2', 'forcecb sysmem flushall',
                                     icd=AFFECTED_ICD), 'forcecb,flushall')

    def test_flushall_workaround_excludes_other_gpus_and_driver_selections(self):
        for model in ('Adreno740', 'Adreno830', ''):
            with self.subTest(model=model):
                self.assertEqual(self.launch(model, 'sysmem,forcecb',
                                             icd=AFFECTED_ICD), 'sysmem,forcecb')
        for icd in (None, '', AFFECTED_ICD.replace('v0.2.0', 'v0.2.1'),
                    AFFECTED_ICD.replace('v0.2.0', 'v0.2.00'),
                    AFFECTED_ICD.replace('DD-Turnip_v0.2.0', 'Another-Driver'),
                    AFFECTED_ICD.replace('linux_vulkan_drivers', 'graphics_driver'),
                    '/other/icd.json:' + AFFECTED_ICD, AFFECTED_ICD + ':/other/icd.json'):
            with self.subTest(icd=icd):
                self.assertEqual(self.launch('Adreno840v2', 'sysmem,forcecb', icd=icd), 'forcecb')

    def test_effective_driver_files_takes_precedence_over_legacy_icd(self):
        self.assertEqual(self.launch('Adreno840v2', icd=AFFECTED_ICD,
                                     driver_files='/other/icd.json'), '')
        self.assertEqual(self.launch('Adreno840v2', icd='/other/icd.json',
                                     driver_files=AFFECTED_ICD), 'flushall')
        self.assertEqual(self.launch('Adreno840v2', icd=AFFECTED_ICD,
                                     driver_files=''), 'flushall')
        self.assertEqual(self.launch('Adreno840v2', icd=AFFECTED_ICD,
                                     driver_files='/other/icd.json:' + AFFECTED_ICD), '')


if __name__ == '__main__':
    unittest.main()
