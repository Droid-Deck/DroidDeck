import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest


HELPER = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin/droiddeck-steamvr'


@unittest.skipUnless(shutil.which('sh'), 'requires a POSIX shell')
class SteamVrGraphicsTest(unittest.TestCase):
    def launch(self, model, debug=None, action='start', preload=None):
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
            if debug is not None:
                env['TU_DEBUG'] = debug
            if preload is not None:
                env['LD_PRELOAD'] = preload
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


if __name__ == '__main__':
    unittest.main()
