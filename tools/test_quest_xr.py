import argparse
import json
import os
import subprocess
import tempfile
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


def validate_pico_samples(samples, require_controllers=False, require_inputs=False, require_haptics=False):
    failure = validate_samples(samples, require_controllers or require_inputs or require_haptics)
    if failure:
        return failure
    last = samples[-1]
    if require_controllers or require_inputs or require_haptics:
        if len(last.get('controllersActive', [])) != 2 or not all(last['controllersActive']) or len(last.get('controllerFlags', [])) != 2:
            return 'Both PICO controllers must remain active in the final sample'
        if any(flags & 15 != 15 for flags in last['controllerFlags']):
            return 'Both PICO controllers must have tracked 6DoF in the final sample'
        profiles = last.get('controllerProfiles', [])
        expected = {'/interaction_profiles/bytedance/' + name for name in
                    ('pico_neo3_controller', 'pico4_controller', 'pico4s_controller',
                     'pico_ultra_controller_bd')}
        if len(profiles) != 2 or any(profile not in expected for profile in profiles):
            return 'PICO native controller profiles were not active on both hands'
    if require_inputs or require_haptics:
        inputs = last.get('inputs', [])
        if len(inputs) != 2:
            return 'No per-hand input evidence was recorded'
        for hand, evidence in enumerate(inputs):
            if require_inputs:
                # Face/stick clicks, capacitive touch, and the PICO physical trigger/grip clicks.
                required = 0x7 | 0x1f0 | 0x1e00 | (0x8 if hand == 0 else 0)
                if evidence.get('buttonsSeen', 0) & required != required:
                    return f'Hand {hand}: not all clicks and touch inputs were observed'
                if evidence.get('triggerMax', 0) < 0.7 or evidence.get('squeezeMax', 0) < 0.7:
                    return f'Hand {hand}: trigger and grip analog range was not observed'
                minimum, maximum = evidence.get('stickMin', []), evidence.get('stickMax', [])
                if len(minimum) != 2 or len(maximum) != 2 or any(x > -0.7 for x in minimum) or any(x < 0.7 for x in maximum):
                    return f'Hand {hand}: all four thumbstick directions were not observed'
            if require_haptics and evidence.get('hapticsApplied', 0) < 1:
                return f'Hand {hand}: no SteamVR haptic pulse was accepted by OpenXR'
    return None


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
    parser = argparse.ArgumentParser(description='Validate immersive DroidDeck XR on a connected headset')
    parser.add_argument('--adb', default=os.environ.get('ADB', 'adb'))
    parser.add_argument('--serial')
    parser.add_argument('--headset', choices=('quest', 'pico'), default='quest')
    parser.add_argument('--package', default='com.droiddeck.launcher')
    parser.add_argument('--seconds', type=int, default=20)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--require-controllers', action='store_true')
    parser.add_argument('--require-inputs', action='store_true', help='Require every PICO application input during the run')
    parser.add_argument('--require-haptics', action='store_true', help='Require SteamVR haptics accepted by OpenXR on both hands')
    parser.add_argument('--keep-awake', action='store_true')
    parser.add_argument('--metrics', action='store_true', help='Record public battery, thermal and package memory dumps before/after testing')
    args = parser.parse_args()
    if args.output is None:
        args.output = (Path(tempfile.gettempdir()) / 'droiddeck-pico-validation' if args.headset == 'pico'
                       else Path('build/questxr-validation'))
    if args.seconds < 5 or args.seconds > 600:
        parser.error('--seconds must be between 5 and 600')
    if args.headset != 'pico' and (args.require_inputs or args.require_haptics):
        parser.error('--require-inputs and --require-haptics currently require --headset pico')
    serial = resolve_serial(args.adb, args.serial)
    args.output.mkdir(parents=True, exist_ok=True)

    def adb(*command):
        return subprocess.run([args.adb, '-s', serial, *command], check=True,
                              capture_output=True, text=True, timeout=15).stdout

    package = args.package
    if args.headset == 'pico':
        maker = adb('shell', 'getprop', 'ro.product.manufacturer').strip().lower()
        brand = adb('shell', 'getprop', 'ro.product.brand').strip().lower()
        if maker != 'pico' and brand != 'pico':
            parser.error('Selected device is not a PICO headset')
    health_path = f'/sdcard/Android/data/{package}/files/xr-health.json'
    adb('shell', 'rm', '-f', health_path)
    adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    activity = 'OpenXrActivity' if args.headset == 'pico' else 'QuestXrActivity'
    launch = ['shell', 'am', 'start', '-n', f'{package}/com.droiddeck.launcher.{activity}']
    samples = []
    failure = None
    metrics = {'before': collect_device_metrics(adb, package)} if args.metrics else None
    try:
        if args.headset == 'quest':
            adb('shell', 'am', 'broadcast', '-a', 'com.oculus.vrpowermanager.prox_close')
        adb(*launch)
        deadline = time.monotonic() + args.seconds
        while time.monotonic() < deadline:
            time.sleep(1)
            if args.headset == 'quest':
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
        if args.headset == 'quest' and not args.keep_awake:
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
    failure = failure or (validate_pico_samples(samples, args.require_controllers, args.require_inputs, args.require_haptics)
                          if args.headset == 'pico' else validate_samples(samples, args.require_controllers))
    report = {'ok': failure is None, 'error': failure, 'samples': samples, 'summary': summarize_samples(samples), 'deviceMetrics': metrics}
    (args.output / 'report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps({'ok': report['ok'], 'error': failure, 'samples': len(samples),
                      'last': samples[-1] if samples else None}, indent=2))
    return 0 if report['ok'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
