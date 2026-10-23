#!/usr/bin/env python3
"""Validate a real OpenXR runtime on Quest or Android XR; never treat mock as hardware."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time

from adb_device import DeviceResolutionError, resolve_serial
from test_quest_xr import collect_device_metrics, summarize_samples, validate_progress, validate_samples as validate_guest


def validate_samples(samples, mode='smoke', require_hands=True):
    if mode not in ('smoke', 'steamvr'):
        return 'Unknown validation mode'
    if len(samples) < 2:
        return 'No advancing OpenXR telemetry'
    stalled = validate_progress(samples)
    if stalled:
        return stalled
    if any(s.get('backend') != 'openxr' for s in samples):
        return 'A real OpenXR backend is required; mock is not hardware evidence'
    if any(s.get('error') for s in samples):
        return 'Runtime reported an error'
    generation = samples[0].get('generation')
    if not generation or any(s.get('generation') != generation for s in samples):
        return 'Session generation changed or is missing'
    if any(s.get('originEpoch') != samples[0].get('originEpoch') for s in samples):
        return 'Reference space changed during validation'
    final = samples[-1]
    if final.get('sessionState') != 5 or final.get('headFlags', 0) & 15 != 15:
        return 'Final session must be focused with tracked 6DoF'
    if not final.get('eyeSeparationMeters', 0) > 0.001:
        return 'No nonzero stereo eye separation; two swapchains alone are insufficient'
    if final.get('rendered', 0) - samples[0].get('rendered', 0) < 72:
        return 'Fewer than 72 stereo submissions'
    if mode == 'smoke':
        if any(s.get('source') != 'synthetic' for s in samples):
            return 'Smoke must render its local stereo scene'
    else:
        failure = validate_guest(samples)
        if failure:
            return failure
    if require_hands:
        if final.get('inputMode') != 'hands' or not any(final.get('handsActive', [])):
            return 'No active runtime hand at the final sample'
        providers = {'ext-hand-interaction', 'fb-hand-aim'}
        if not any(p in providers for p in final.get('handProviders', [])):
            return 'No supported runtime hand input provider'
        completed = False
        for hand in range(2):
            if not final.get('handsActive', [False, False])[hand] or final.get('handProviders', ['', ''])[hand] not in providers:
                continue
            start_press = samples[0].get('handPinchPresses', [0, 0])[hand]
            start_release = samples[0].get('handPinchReleases', [0, 0])[hand]
            end_press = final.get('handPinchPresses', [0, 0])[hand]
            end_release = final.get('handPinchReleases', [0, 0])[hand]
            completed |= end_press > start_press and end_release > start_release
        if not completed:
            return 'No mapped runtime pinch press and release observed'
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default=os.environ.get('ADB', 'adb'))
    parser.add_argument('--serial')
    parser.add_argument('--platform', choices=('quest', 'androidxr'), required=True)
    parser.add_argument('--mode', choices=('smoke', 'steamvr'), default='smoke')
    parser.add_argument('--input', choices=('hands', 'auto', 'controllers'), default='hands')
    parser.add_argument('--package')
    parser.add_argument('--activity', help='Fully qualified Activity class')
    parser.add_argument('--seconds', type=int, default=30)
    parser.add_argument('--output', type=Path, default=Path('build/xr-validation'))
    parser.add_argument('--metrics', action='store_true')
    args = parser.parse_args()
    if args.platform == 'androidxr' and args.mode == 'steamvr' and args.input != 'hands':
        parser.error('Android XR SteamVR entry requires --input hands')
    if not 5 <= args.seconds <= 600:
        parser.error('--seconds must be between 5 and 600')
    args.output.mkdir(parents=True, exist_ok=True)
    package = args.package or ('com.droiddeck.xrsmoke' if args.mode == 'smoke' else 'com.droiddeck.launcher')
    activity = args.activity or ('com.droiddeck.xrsmoke.RuntimeProbeActivity' if args.mode == 'smoke' else
                                'com.droiddeck.launcher.' + ('QuestXrActivity' if args.platform == 'quest' else 'AndroidXrActivity'))
    samples, failure, metrics, serial = [], None, None, None
    try:
        serial = resolve_serial(args.adb, args.serial)

        def adb(*command):
            return subprocess.run([args.adb, '-s', serial, *command], check=True, capture_output=True,
                                  text=True, timeout=15).stdout

        if adb('shell', 'getprop', 'ro.kernel.qemu').strip() == '1':
            raise DeviceResolutionError('Android emulator does not validate native OpenXR; only the 2D launcher can be validated')
        health = f'/sdcard/Android/data/{package}/files/xr-health.json'
        adb('shell', 'rm', '-f', health)
        if args.metrics:
            metrics = {'before': collect_device_metrics(adb, package)}
        adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
        output = adb('shell', 'am', 'start', '-W', '-n', f'{package}/{activity}',
                     '--es', 'xr_input', args.input)
        if 'Error:' in output or 'Exception' in output:
            raise DeviceResolutionError(output.strip())
        deadline = time.monotonic() + args.seconds
        while time.monotonic() < deadline:
            time.sleep(1)
            try:
                sample = json.loads(adb('shell', 'cat', health))
                sample['_hostMonotonicSeconds'] = time.monotonic()
                samples.append(sample)
                if sample.get('error'):
                    break
            except (subprocess.CalledProcessError, json.JSONDecodeError):
                pass
        if metrics is not None:
            metrics['after'] = collect_device_metrics(adb, package)
        failure = validate_samples(samples, args.mode, args.input == 'hands')
        (args.output / 'logcat.txt').write_text(adb('logcat', '-d', '-s', 'DroidDeckXR:I', 'OpenXR-Loader:W', 'AndroidRuntime:E'), encoding='utf-8')
    except (DeviceResolutionError, subprocess.CalledProcessError, subprocess.TimeoutExpired, OSError) as error:
        failure = str(error)
    report = {'ok': failure is None, 'status': 'passed' if failure is None else 'failed' if samples else 'not_run',
              'platform': args.platform, 'mode': args.mode, 'input': args.input,
              'package': package, 'activity': activity, 'error': failure, 'samples': samples,
              'evidence': 'shared-hand-action-bridge', 'opticalHandTrackingVerified': False,
              'steamVrInputDeliveryVerified': False,
              'summary': summarize_samples(samples), 'deviceMetrics': metrics}
    (args.output / 'report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps({key: report[key] for key in ('ok', 'status', 'error', 'summary')}, indent=2))
    return 0 if report['ok'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
