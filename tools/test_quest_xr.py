import argparse
import json
import os
import subprocess
import time
from pathlib import Path

from adb_device import resolve_serial


def validate_samples(samples, require_controllers=False):
    if len(samples) < 2:
        return 'No advancing telemetry; check runtime initialization and headset wake/proximity'
    if samples[-1]['rendered'] - samples[0]['rendered'] < 72:
        return 'Fewer than 72 additional stereo frames were submitted'
    if not any(sample['sessionState'] == 5 and sample['headFlags'] & 15 == 15 for sample in samples):
        return 'No focused session with tracked 6DoF position and orientation'
    if samples[-1]['sessionState'] != 5 or samples[-1]['headFlags'] & 15 != 15:
        return 'Session did not remain focused with tracked 6DoF through the final sample'
    if require_controllers and not any(all(sample['controllersActive']) for sample in samples):
        return 'Both controllers did not become active'
    if samples[-1].get('uploadedFrames', 0) - samples[0].get('uploadedFrames', 0) < 72:
        return 'No advancing owned SteamVR images; XR submissions alone are insufficient'
    if samples[-1].get('sourceFrameId', 0) <= samples[0].get('sourceFrameId', 0):
        return 'SteamVR source frame IDs did not advance'
    if samples[-1].get('steamvrState') != 4:
        return 'SteamVR did not report RUNNING'
    expected = 'frames'
    if samples[-1].get('source') != expected:
        return 'Wrong projection source'
    return None


def summarize_samples(samples):
    result = {'sampleCount': len(samples), 'focusedTrackedFraction': None, 'observedSeconds': None, 'ratesHz': {}}
    if not samples:
        return result
    result['focusedTrackedFraction'] = sum(sample.get('sessionState') == 5 and sample.get('headFlags', 0) & 15 == 15
                                            for sample in samples) / len(samples)
    if len(samples) < 2 or '_hostMonotonicSeconds' not in samples[0] or '_hostMonotonicSeconds' not in samples[-1]:
        return result
    duration = samples[-1]['_hostMonotonicSeconds'] - samples[0]['_hostMonotonicSeconds']
    if duration <= 0:
        return result
    result['observedSeconds'] = duration
    for counter in ('rendered', 'guestFrames', 'receivedFrames', 'rejectedFrames', 'uploadedFrames'):
        if counter in samples[0] and counter in samples[-1]:
            delta = samples[-1][counter] - samples[0][counter]
            result['ratesHz'][counter] = delta / duration if delta >= 0 else None
    period = samples[-1].get('periodNs', 0)
    result['runtimeRefreshHz'] = 1000000000 / period if period > 0 else None
    return result


def collect_device_metrics(adb, package):
    result = {}
    for name, command in [('battery', ('shell', 'dumpsys', 'battery')),
                          ('thermal', ('shell', 'dumpsys', 'thermalservice')),
                          ('memory', ('shell', 'dumpsys', 'meminfo', package))]:
        try:
            result[name] = {'available': True, 'text': adb(*command)}
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
            result[name] = {'available': False, 'error': type(error).__name__}
    return result


def main():
    parser = argparse.ArgumentParser(description='Validate immersive DroidDeck XR on a connected Quest')
    parser.add_argument('--adb', default=os.environ.get('ADB', 'adb'))
    parser.add_argument('--serial')
    parser.add_argument('--seconds', type=int, default=20)
    parser.add_argument('--output', type=Path, default=Path('build/questxr-validation'))
    parser.add_argument('--require-controllers', action='store_true')
    parser.add_argument('--keep-awake', action='store_true')
    parser.add_argument('--metrics', action='store_true', help='Record public battery, thermal and package memory dumps before/after testing')
    args = parser.parse_args()
    if args.seconds < 5 or args.seconds > 600:
        parser.error('--seconds must be between 5 and 600')
    serial = resolve_serial(args.adb, args.serial)
    args.output.mkdir(parents=True, exist_ok=True)

    def adb(*command):
        return subprocess.run([args.adb, '-s', serial, *command], check=True,
                              capture_output=True, text=True, timeout=15).stdout

    package = 'com.droiddeck.launcher'
    health_path = f'/sdcard/Android/data/{package}/files/xr-health.json'
    adb('shell', 'rm', '-f', health_path)
    adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    launch = ['shell', 'am', 'start', '-n', f'{package}/.QuestXrActivity']
    samples = []
    failure = None
    metrics = {'before': collect_device_metrics(adb, package)} if args.metrics else None
    try:
        adb('shell', 'am', 'broadcast', '-a', 'com.oculus.vrpowermanager.prox_close')
        adb(*launch)
        deadline = time.monotonic() + args.seconds
        while time.monotonic() < deadline:
            time.sleep(1)
            adb('shell', 'am', 'broadcast', '-a', 'com.oculus.vrpowermanager.prox_close')
            try:
                sample = json.loads(adb('shell', 'cat', health_path))
                sample['_hostMonotonicSeconds'] = time.monotonic()
                samples.append(sample)
                if sample.get('error'):
                    failure = sample['error']
                    break
            except (subprocess.CalledProcessError, json.JSONDecodeError):
                pass
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        failure = f'ADB operation failed: {type(error).__name__}'
    finally:
        if not args.keep_awake:
            try:
                adb('shell', 'am', 'broadcast', '-a', 'com.oculus.vrpowermanager.prox_far')
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
                failure = failure or 'Could not restore headset proximity after testing'
    try:
        log = adb('logcat', '-d', '-s', 'DroidDeckXR:I', 'OpenXR-Loader:W', 'AndroidRuntime:E')
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        log = str(error)
    (args.output / 'logcat.txt').write_text(log, encoding='utf-8')
    if metrics is not None:
        metrics['after'] = collect_device_metrics(adb, package)
    failure = failure or validate_samples(samples, args.require_controllers)
    report = {'ok': failure is None, 'error': failure, 'samples': samples, 'summary': summarize_samples(samples), 'deviceMetrics': metrics}
    (args.output / 'report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps({'ok': report['ok'], 'error': failure, 'samples': len(samples),
                      'last': samples[-1] if samples else None}, indent=2))
    return 0 if report['ok'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
