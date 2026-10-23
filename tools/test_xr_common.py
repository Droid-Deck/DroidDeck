#!/usr/bin/env python3
"""Host tests for the production XR core and v1 bridge; no Android SDK required."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sanitize', action='store_true')
    parser.add_argument('--openxr-include', type=Path, help='Optional OpenXR include directory for mocked binding dispatch tests')
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='droiddeck-xr-') as tmp:
        sources = ['shared/xr/test_core.cpp', 'tools/vr/tests/test_xr_tracking.cpp',
                   'tools/vr/tests/test_xr_touch.cpp', 'tools/vr/tests/test_xr_chaperone.cpp']
        if args.openxr_include:
            sources += ['shared/xr/test_openxr_bindings.cpp']
        for index, source in enumerate(sources):
            executable = str(Path(tmp) / f'test-{index}')
            command = [os.environ.get('CXX', 'c++'), '-std=c++17', '-Wall', '-Wextra', '-Werror',
                       '-pthread', '-DDROIDDECK_XR_FIXTURES', '-I' + str(ROOT / 'tools/vr/src')]
            if args.sanitize:
                command += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer', '-g']
            if args.openxr_include:
                command += ['-I' + str(args.openxr_include), '-Wno-missing-field-initializers']
            subprocess.run(command + [str(ROOT / source), '-o', executable], check=True)
            subprocess.run([executable, tmp], check=True, timeout=30)

if __name__ == '__main__':
    main()
