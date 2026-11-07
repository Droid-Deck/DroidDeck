#!/usr/bin/env python3
"""Checks that the published runtime has what the guest session needs.

The guest session is upstream's own script, and it runs inside the
published runtime image. If a program it needs is missing from the image,
the session fails at a point that is hard to read — half way through the
start-up, with the guest already running.

This tool lists the image and checks the programs the session and its
helpers use. The ones upstream guards with `command -v` are reported
separately: their absence is harmless.

Usage:
  tools/check_guest_tools.py [runtime archive]
"""

import pathlib
import subprocess
import sys

PROJECT = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_ARCHIVE = PROJECT / 'build/upstream-runtime/linuxfs-ubuntu-r1-rc4.tar.zst'

# The programs the guest session and the overlay's helpers run.
NEEDED = """
bash env date dd stat mkdir head cat tr sed awk grep find ls rm mv cp chmod sleep
python3 pgrep pkill localedef iconvconfig fc-cache fc-match xprop dbus-run-session
dbus-daemon taskset timeout uname id mktemp tee gzip tar zstd ps cksum sha256sum wc
ln touch sync nproc lscpu free lsblk blkid ip
""".split()

# Upstream guards these with `command -v`, so their absence only means the
# feature that uses them is skipped.
OPTIONAL = ['vulkaninfo', 'glxinfo', 'wineserver', 'wine', 'steam', 'gamescope']


def list_archive(archive: pathlib.Path) -> set:
    """The paths the archive holds."""
    names = set()
    process = subprocess.Popen(['tar', '-tf', '-'], stdin=subprocess.PIPE,
                               stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    decompressor = subprocess.Popen(['zstd', '-dc', str(archive)],
                                    stdout=process.stdin, stderr=subprocess.DEVNULL)
    for line in process.stdout:
        name = line.decode('utf-8', 'replace').strip()
        if name:
            names.add(name.lstrip('./'))
    decompressor.wait()
    process.wait()
    return names


def main() -> int:
    archive = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_ARCHIVE
    if not archive.exists():
        print(f'the published runtime is not downloaded: {archive}')
        print('run tools/fetch_upstream_runtime.sh first.')
        return 2

    print(f'==> listing {archive.name}')
    present = list_archive(archive)
    if not present:
        print('the archive could not be read')
        return 2
    print(f'    {len(present)} entries')

    def has(program: str) -> bool:
        for directory in ('bin', 'usr/bin', 'usr/sbin', 'sbin', 'usr/games',
                          'usr/local/bin', 'usr/local/sbin'):
            if f'{directory}/{program}' in present:
                return True
        return False

    missing = [program for program in NEEDED if not has(program)]
    absent_optional = [program for program in OPTIONAL if not has(program)]

    for program in missing:
        print(f'FAIL: the guest session needs {program}, and the image does not have it')
    for program in absent_optional:
        print(f'note: {program} is not in the image, but the session guards it')

    if missing:
        print(f'{len(missing)} program(s) the guest session needs are missing.')
        return 1

    print(f'==> every program the guest session needs is in the image '
          f'({len(absent_optional)} optional one(s) absent)')
    return 0


if __name__ == '__main__':
    sys.exit(main())
