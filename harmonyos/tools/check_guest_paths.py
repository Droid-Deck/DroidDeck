#!/usr/bin/env python3
"""Checks that the paths the guest is told about are the guest's paths.

The guest sees the rootfs as its /, and the host's runtime directory is
bound into it at /run/droiddeck. Every path the launcher hands to the
guest therefore has to be a path the guest can resolve:

* the PulseAudio server's socket, which the guest's client connects to;
* the runtime directory itself, which the compositor's Wayland socket
  lives in;
* the log and debug directories, which are host paths — but the app's
  files directory is bound into the guest at the same absolute path, so
  those resolve too.

A host path that is not bound, on the other hand, silently resolves
inside the guest's rootfs, where the file is not. That failure is quiet:
the guest's audio client simply never connects.

This tool compares the native and the ArkTS side, so the guest runtime
directory cannot drift apart between them.

Usage: tools/check_guest_paths.py
"""

import pathlib
import re
import sys

PROJECT = pathlib.Path(__file__).resolve().parent.parent

NATIVE_RUNTIME = PROJECT / 'entry/src/main/cpp/runtime/runtime.cpp'
ARKTS_PATHS = PROJECT / 'entry/src/main/ets/common/Paths.ets'
ARKTS_SERVICE = PROJECT / 'entry/src/main/ets/service/RuntimeService.ets'


def main() -> int:
    failures = 0

    native = NATIVE_RUNTIME.read_text()
    arkts_paths = ARKTS_PATHS.read_text()
    arkts_service = ARKTS_SERVICE.read_text()

    # The native side: the guest runtime directory and the bind that puts
    # the host's directory there.
    native_dirs = re.findall(r'kGuestRuntimeDir\s*=\s*"([^"]+)"', native)
    if len(native_dirs) != 1:
        print('FAIL: the native side does not define exactly one guest runtime directory')
        return 1
    guest_dir = native_dirs[0]

    # The ArkTS side: the same directory.
    arkts_dirs = re.findall(r"guestRuntimeDir\(\)[^}]*return\s*'([^']+)'", arkts_paths)
    if len(arkts_dirs) != 1:
        print('FAIL: the ArkTS side does not define exactly one guest runtime directory')
        return 1

    if arkts_dirs[0] != guest_dir:
        print(f'FAIL: the guest runtime directory differs: the native side says '
              f'{guest_dir}, the ArkTS side says {arkts_dirs[0]}')
        failures += 1
    else:
        print(f'ok: both sides use {guest_dir}')

    # The bind that makes it true.
    if f'":" + kGuestRuntimeDir' not in native:
        print('FAIL: the native side does not bind the runtime directory into the guest')
        failures += 1
    else:
        print('ok: the runtime directory is bound into the guest')

    # The guest's environment must name the guest's directory.
    if f'env["XDG_RUNTIME_DIR"] = kGuestRuntimeDir' not in native:
        print('FAIL: XDG_RUNTIME_DIR does not name the guest runtime directory')
        failures += 1
    else:
        print('ok: XDG_RUNTIME_DIR names the guest runtime directory')

    # PULSE_SERVER has to be the guest's path, not the host's.
    address = re.search(r"pulseServerAddress\(\)[^}]*return\s*`([^`]+)`", arkts_service)
    if not address:
        print('FAIL: the PulseAudio server address could not be read')
        failures += 1
    else:
        value = address.group(1).replace('${Paths.guestRuntimeDir()}', guest_dir)
        if guest_dir not in value:
            print(f'FAIL: the PulseAudio server address is {value}, which does not name '
                  f'the guest runtime directory {guest_dir}')
            failures += 1
        else:
            print(f'ok: the PulseAudio server address is {value}')

    # The host's cache directory must not leak into the guest's environment.
    host_cache = re.search(r"cacheDir\(\)[^}]*return\s*`([^`]+)`", arkts_paths)
    if host_cache:
        cache = host_cache.group(1).replace('${Paths.filesDir()}', '<files>')
        for name, value in re.findall(r"'([A-Z_]+)':\s*([^,}]+)", arkts_service):
            if 'cacheDir' in value or 'runtimeDir' in value:
                print(f'FAIL: the guest environment variable {name} is built from a host path')
                failures += 1

    if failures:
        print(f'{failures} guest path problem(s) found.')
        return 1
    print('==> every path the guest is told about is the guest\'s')
    return 0


if __name__ == '__main__':
    sys.exit(main())
