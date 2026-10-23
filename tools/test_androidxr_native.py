#!/usr/bin/env python3
"""Hardware-only native OpenXR probe. Install a debug production APK first, before starting the guest."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import time

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--package', default='com.droiddeck.launcher')
    parser.add_argument('--seconds', type=int, default=20)
    parser.add_argument('--output', type=Path, default=Path('build/androidxr-native-probe'))
    args = parser.parse_args()
    if not 5 <= args.seconds <= 60:
        parser.error('--seconds must be 5..60')
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    adb = str(Path(sdk) / 'platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb')) if sdk else shutil.which('adb')
    if not adb:
        parser.error('adb unavailable; set ANDROID_HOME')
    def call(*parts):
        return subprocess.check_output([adb, '-s', args.serial, *parts], text=True, timeout=30)
    args.output.mkdir(parents=True, exist_ok=True)
    health = f'/sdcard/Android/data/{args.package}/files/xr-health.json'
    call('shell', 'rm', '-f', health)
    call('shell', 'am', 'start', '-W', '-n', args.package + '/com.droiddeck.launcher.QuestXrActivity', '--ez', 'native_probe', 'true')
    samples = []
    for _ in range(args.seconds):
        time.sleep(1)
        try:
            samples.append(json.loads(call('shell', 'cat', health)))
        except (ValueError, subprocess.CalledProcessError):
            pass
    (args.output / 'samples.json').write_text(json.dumps(samples, indent=2))
    (args.output / 'logcat.txt').write_text(call('logcat', '-d', '-s', 'DroidDeckXR:I', '*:S'))
    passed = len(samples) >= 2 and all(s.get('source') == 'native-probe' for s in samples)
    passed = passed and samples[-1].get('rendered', 0) > samples[0].get('rendered', 0)
    passed = passed and samples[-1].get('headFlags', 0) & 15 == 15 and samples[-1].get('sessionState') == 5
    report = {'source': 'native-openxr-hardware-probe', 'passed': bool(passed), 'serial': args.serial,
              'claims': ['startup, advancing probe grid, final focused 6DoF flags'] if passed else [],
              'notValidated': ['physical motion accuracy', 'hands', 'guest graphics', 'SteamVR gameplay']}
    (args.output / 'report.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2))
    return 0 if passed else 1

if __name__ == '__main__':
    raise SystemExit(main())
