#!/usr/bin/env python3
"""Read-only reboot projection. Never writes raw Android logs or account data.

This is evidence collection, not a GPU fault injector or a root-cause oracle.
The optional kernel projection reads only SYSTEM_LAST_KMSG after user unlock.
It emits fixed categories and numeric hardware fields, never arbitrary log lines.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

ENTRY = re.compile(r'^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\s+(SYSTEM_LAST_KMSG)\b', re.M)
CATEGORIES = ('GNOC', 'PVRSRV', 'watchdog', 'IOMMU', 'S2MPU', 'access control', 'Kernel panic', 'HWR', 'GPU fault')
PRIMARY_USER_STATES = frozenset(('RUNNING_LOCKED', 'RUNNING_UNLOCKING', 'RUNNING_UNLOCKED'))
# Exact canonical reasons plus the vendor reason observed in the bounded Pixel
# tests. Unrecognized/custom suffixes are not diagnostic output categories.
BOOT_REASONS = frozenset(('cold', 'hard', 'warm', 'reboot', 'shutdown',
                          'recovery', 'bootloader', 'kernel_panic', 'watchdog',
                          'reset,warm,gnoc_error'))


def primary_state(text):
    match = re.search(r'Started users state: \[0=([A-Z_]+)(?=[,\]])', text)
    return match.group(1) if match and match.group(1) in PRIMARY_USER_STATES else 'unknown'


def safe_reason(text):
    value = text.strip()
    return value if value in BOOT_REASONS else 'unknown'


def kernel_projection(text, device_timezone, after=None):
    entries = list(ENTRY.finditer(text))
    result = {'metadata_dates': [m.group(1) for m in entries], 'raw_body_saved': False,
              'root_cause_proven': False}
    if not entries:
        result['status'] = 'no_accessible_entry'
        return result
    latest = max(enumerate(entries), key=lambda row: row[1].group(1))
    index, match = latest
    body = text[match.end():entries[index + 1].start() if index + 1 < len(entries) else len(text)]
    result['entry_date_device_local'] = match.group(1)
    try:
        naive = datetime.strptime(match.group(1), '%Y-%m-%d %H:%M:%S')
        zone = ZoneInfo(device_timezone)
    except (ValueError, ZoneInfoNotFoundError):
        result['status'] = 'unknown_device_timezone'
        return result
    # Dropbox supplies a local wall clock without an offset. Round-trip both
    # folds: a repeated hour has two possible instants; a skipped hour has none.
    instants = {local.astimezone(timezone.utc)
                for fold in (0, 1)
                for local in (naive.replace(tzinfo=zone, fold=fold),)
                if local.astimezone(timezone.utc).astimezone(zone).replace(tzinfo=None) == naive}
    if len(instants) != 1:
        result['status'] = ('ambiguous_device_local_time' if instants
                            else 'nonexistent_device_local_time')
        return result
    local = instants.pop()
    result['entry_date_utc'] = local.isoformat()
    if after is not None and local < after:
        result['status'] = 'stale_entry_not_attributed_to_current_trial'
        return result
    result['status'] = 'projected_entry_not_a_root_cause'
    result['categories'] = {key: len(re.findall(re.escape(key), body, re.I)) for key in CATEGORIES}
    patterns = {
        'gnoc_addresses': r'Error from GNOC at addr (0x[0-9a-fA-F]+)',
        'gnoc_error_types': r'GNOC error type (0x[0-9a-fA-F]+)',
        'firmware_reboot_codes': r'Reboot reason:\s+(0x[0-9a-fA-F]+)',
        'access_control_addresses': r'Access Control Error \((0x[0-9a-fA-F]+)',
    }
    result['hardware_fields'] = {key: sorted(set(re.findall(pattern, body))) for key, pattern in patterns.items()}
    # Read the complete value, not just a recognized-looking prefix. Apply the
    # same fixed projection as getprop; no arbitrary kernel reason text escapes.
    reasons = re.findall(r'Last boot reason:[ \t]*([^\r\n]*)', body)
    result['last_boot_reasons'] = sorted({safe_reason(reason) for reason in reasons})
    return result


def collect(adb, serial, include_kernel=False, after=None):
    def shell(*args):
        try:
            process = subprocess.run([adb, '-s', serial, 'shell', *args], capture_output=True,
                                     text=True, timeout=20)
        except (OSError, subprocess.TimeoutExpired):
            return None, ''
        # No command stderr is copied to output: it may contain unrelated data.
        return process.returncode, process.stdout if process.returncode == 0 else ''

    result = {'at': datetime.now(timezone.utc).isoformat(), 'read_only': True,
              'raw_logs_saved': False, 'credentials_collected': False}
    code, value = shell('settings', 'get', 'global', 'boot_count')
    result['boot_count'] = int(value.strip()) if code == 0 and value.strip().isdigit() else None
    code, value = shell('getprop', 'ro.boot.bootreason')
    result['bootloader_reason'] = safe_reason(value)
    code, value = shell('getprop', 'sys.boot.reason')
    result['system_boot_reason'] = safe_reason(value)
    code, value = shell('dumpsys', 'user')
    result['primary_user_state'] = primary_state(value)
    code, value = shell('getprop', 'persist.sys.timezone')
    device_timezone = value.strip()
    try:
        ZoneInfo(device_timezone)
        result['device_timezone'] = device_timezone
    except (ValueError, ZoneInfoNotFoundError):
        result['device_timezone'] = 'unknown'
    code, value = shell('dumpsys', 'dropbox', 'SYSTEM_LAST_KMSG')
    result['kernel_metadata_dates'] = [m.group(1) for m in ENTRY.finditer(value)]
    if include_kernel:
        if result['primary_user_state'] != 'RUNNING_UNLOCKED':
            result['kernel'] = {'status': 'requires_user_unlock', 'raw_body_saved': False}
        else:
            code, value = shell('dumpsys', 'dropbox', '--print', 'SYSTEM_LAST_KMSG')
            result['kernel'] = kernel_projection(value, result['device_timezone'], after)
            result['kernel']['read_exit_code'] = code
    return result


def write_private_output(path, output):
    # Exclusive creation also rejects existing symlinks. Set the private mode
    # before any bytes are written, rather than chmod after exposing the data.
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, 'O_NOFOLLOW', 0)
    descriptor = os.open(path, flags, 0o600)
    with os.fdopen(descriptor, 'w', encoding='utf-8') as stream:
        stream.write(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--kernel', action='store_true')
    parser.add_argument('--after', help='Earliest relevant UTC ISO timestamp; excludes stale kernel entries')
    parser.add_argument('--output', type=Path, help='New JSON file; refuses to overwrite')
    args = parser.parse_args()
    after = datetime.fromisoformat(args.after.replace('Z', '+00:00')) if args.after else None
    if after is not None and after.tzinfo is None:
        parser.error('--after must include a timezone')
    result = collect(args.adb, args.serial, args.kernel, after)
    output = json.dumps(result, indent=2) + '\n'
    if args.output:
        write_private_output(args.output, output)
    print(output, end='')


if __name__ == '__main__':
    main()
