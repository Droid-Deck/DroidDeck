#!/usr/bin/env python3
"""Build/install the isolated Jetpack XR diagnostic; never selects an adb device implicitly."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT / 'tools/androidxr-diagnostics'
PACKAGE = 'com.droiddeck.xrdiagnostics'

def device_abi(value):
    for abi in value.strip().split(','):
        if abi in ('arm64-v8a', 'x86_64'):
            return abi
    raise ValueError(f'Unsupported diagnostic device ABI: {value.strip()}')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build', action='store_true')
    parser.add_argument('--injected-tests', action='store_true', help='Run Robolectric/ArCoreTestRule tests')
    parser.add_argument('--serial', help='Explicit adb serial required for install, launch and collect')
    parser.add_argument('--install', action='store_true')
    parser.add_argument('--launch', choices=['live', 'fixture'])
    parser.add_argument('--collect', action='store_true')
    parser.add_argument('--seconds', type=int, default=10)
    parser.add_argument('--abis', default='arm64-v8a,x86_64')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/androidxr-validation')
    args = parser.parse_args()
    device_action = args.install or args.launch or args.collect
    if device_action and not args.serial:
        parser.error('--serial is required for every device action')
    if not (args.build or args.injected_tests or device_action):
        parser.error('select --build, --injected-tests, --install, --launch, or --collect')
    if not 0 <= args.seconds <= 60:
        parser.error('--seconds must be 0..60')
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    adb = str(Path(sdk) / 'platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb')) if sdk else shutil.which('adb')
    def run_adb(*command):
        if not adb:
            raise RuntimeError('adb unavailable; set ANDROID_HOME')
        return subprocess.check_output([adb, '-s', args.serial, *command], text=True, timeout=30)
    abis = args.abis
    if args.serial:
        abis = device_abi(run_adb('shell', 'getprop', 'ro.product.cpu.abilist'))
        print(f'Selected {args.serial}: building JNI only for {abis}', flush=True)
    if any(abi not in ('arm64-v8a', 'x86_64') for abi in abis.split(',')):
        parser.error('--abis must contain arm64-v8a and/or x86_64')
    if args.build or args.injected_tests:
        wrapper = PROJECT / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
        command = [str(wrapper)] if os.name == 'nt' else ['bash', str(wrapper)]
        tasks = []
        if args.build:
            tasks += [':app:assembleDebug', ':app:processDebugMainManifest', ':app:processReleaseMainManifest']
        if args.injected_tests:
            tasks += [':app:testDebugUnitTest']
        subprocess.run(command + ['-p', str(PROJECT), *tasks, f'-PxrAbis={abis}', '--console=plain'], check=True)
    if args.install:
        apk = PROJECT / 'app/build/outputs/apk/debug/app-debug.apk'
        print(run_adb('install', '-r', str(apk)))
    if args.launch:
        print(run_adb('shell', 'am', 'force-stop', PACKAGE))
        print(run_adb('shell', 'am', 'start', '-W', '-n', PACKAGE + '/.MainActivity', '--es', 'provider', args.launch))
    if args.collect:
        deadline = time.monotonic() + args.seconds
        while time.monotonic() < deadline:
            time.sleep(min(1, max(0, deadline - time.monotonic())))
        args.output.mkdir(parents=True, exist_ok=True)
        report = json.loads(run_adb('shell', 'run-as', PACKAGE, 'cat', 'files/report.json'))
        report['collector'] = {'serial': args.serial, 'abi': abis,
                               'clockPolicy': 'host does not compare its clock with device sample timestamps'}
        (args.output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        (args.output / 'logcat.txt').write_text(run_adb('logcat', '-d', '-s', 'DroidDeckXRDiag:I', '*:S'))
        print(json.dumps(report, indent=2))
        if not report.get('packetRoundTrip') or (report.get('source') == 'synthetic' and
                                               not report.get('injectedTests', {}).get('passed')):
            raise RuntimeError('diagnostic roundtrip or fixture tests failed; see collected report')

if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print(f'Android XR diagnostics: {error}', file=sys.stderr)
        sys.exit(1)
