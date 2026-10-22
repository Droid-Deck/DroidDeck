#!/usr/bin/env python3
"""Package pinned local community A850 drivers; never download or execute drivers."""

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import stat
import struct
import tempfile
import zipfile

ANDROID_SHA256 = '0cf849d068c875f6c469380ca1f74da1f757575bce53d4ef9c1f6d38677cbe8b'
LINUX_SHA256 = '40fc59506620001531b9bdd1c9152e104c6fb1f4c07773c4743b9d70deb87eec'
LIBRARY = 'libvulkan_freedreno.so'
VERSION = '20261012-a850exp1'
SOURCE = 'https://github.com/Droid-Deck/DroidDeck/issues/342'
README = f'''# Adreno 850 community experimental

Manual opt-in experiment for DroidDeck; not native Adreno 850 support.
Source: community attachments to {SOURCE}
Android original: AdrenoTools-A850-experimental.zip, SHA256 {ANDROID_SHA256}
Linux original: A850-experimental.zip, SHA256 {LINUX_SHA256}

These community binaries are patched to treat Adreno 850 as Adreno 840.
They have not been validated on the target phone by this project. They may
black-screen, hang, glitch, or fail to boot. They do not establish native 850
support. Import and select this bundle manually; global auto selection is
unchanged. Use sysmem mode as advised in the original Linux metadata.

Both original meta.json files, libraries, and Linux ICD JSON are preserved
byte-for-byte. No drivers are downloaded, executed, or rebuilt by this tool.
'''
NOTICE = f'''Community experimental binaries, distributed without a compatibility guarantee.
Original author attribution and release descriptions are in android/meta.json
and linux/meta.json. The412Banner is the original driver author; the community
attachment describes an experimental binary patch. Source: {SOURCE}
This wrapper makes no new license grant for the enclosed third-party binaries.
'''


def validate_elf(data, platform):
    if (len(data) < 64 or data[:6] != b'\x7fELF\x02\x01'
            or struct.unpack_from('<H', data, 18)[0] != 183):
        raise ValueError(f'{platform}: expected ELF64 little-endian AArch64 library')
    if platform == 'linux' and b'libc.so.6\x00' not in data:
        raise ValueError('linux: expected glibc library')
    if platform == 'android' and b'libc.so.6\x00' in data:
        raise ValueError('android: glibc library is not an AdrenoTools driver')


def read_driver(path, platform, expected_hash):
    original = Path(path).read_bytes()
    if hashlib.sha256(original).hexdigest() != expected_hash:
        raise ValueError(f'{platform}: SHA256 mismatch; only the pinned issue #342 attachment is accepted')
    allowed = {'meta.json', LIBRARY}
    if platform == 'linux':
        allowed.add('freedreno_icd.aarch64.json')
    with zipfile.ZipFile(io.BytesIO(original)) as archive:
        entries = archive.infolist()
        names = [entry.filename for entry in entries]
        if len(names) != len(set(names)) or set(names) != allowed:
            raise ValueError(f'{platform}: expected only {sorted(allowed)}; unsafe, extra or missing entries')
        for entry in entries:
            mode = entry.external_attr >> 16
            if entry.is_dir() or stat.S_IFMT(mode) not in (0, stat.S_IFREG) or entry.flag_bits & 1:
                raise ValueError(f'{platform}: entry must be an unencrypted regular file')
        files = {name: archive.read(name) for name in names}
    meta = json.loads(files['meta.json'])
    if not isinstance(meta, dict):
        raise ValueError(f'{platform}: meta.json must be an object')
    if platform == 'android' and meta.get('libraryName') != LIBRARY:
        raise ValueError('android: incorrect meta.json libraryName')
    if platform == 'linux':
        if meta.get('libc') != 'glibc' or meta.get('kind') != 'linux-vulkan-icd':
            raise ValueError('linux: incorrect meta.json libc/kind')
        icd = json.loads(files['freedreno_icd.aarch64.json'])
        if (not isinstance(icd, dict) or not isinstance(icd.get('ICD'), dict)
                or icd['ICD'].get('library_path') != './' + LIBRARY):
            raise ValueError('linux: incorrect ICD library_path')
    validate_elf(files[LIBRARY], platform)
    return files


def prepare_bundle(android, linux, output):
    android, linux, output = map(Path, (android, linux, output))
    for source in (android, linux):
        if source.resolve() == output.resolve() or (output.exists() and source.samefile(output)):
            raise ValueError('output must differ from both input archives')
    files = {}
    for platform, source, digest in (('android', android, ANDROID_SHA256), ('linux', linux, LINUX_SHA256)):
        files.update({f'{platform}/{name}': data for name, data in read_driver(source, platform, digest).items()})
    manifest = {'package': 'Adreno 850 community experimental', 'version': VERSION, 'tag': VERSION,
                'drivers': {'android': {'path': 'android'}, 'linux': {'path': 'linux'}}}
    files['manifest.json'] = (json.dumps(manifest, indent=2, sort_keys=True) + '\n').encode()
    files['README.md'] = README.encode()
    files['NOTICE'] = NOTICE.encode()
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=output.parent, prefix='.' + output.name + '.', delete=False) as target:
            temporary = Path(target.name)
            with zipfile.ZipFile(target, 'w', compression=zipfile.ZIP_STORED) as archive:
                for name, data in sorted(files.items()):
                    info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                    info.create_system = 3
                    info.external_attr = (stat.S_IFREG | 0o644) << 16
                    archive.writestr(info, data)
            target.flush()
            os.fsync(target.fileno())
        os.replace(temporary, output)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--android', type=Path, required=True, help='Original AdrenoTools-A850-experimental.zip')
    parser.add_argument('--linux', type=Path, required=True, help='Original A850-experimental.zip')
    parser.add_argument('--output', type=Path, required=True, help='Destination DroidDeck DriverBundle zip')
    args = parser.parse_args()
    try:
        result = prepare_bundle(args.android, args.linux, args.output)
    except (OSError, ValueError, zipfile.BadZipFile, RuntimeError) as error:
        parser.exit(1, f'error: {error}\n')
    print(f'Created {result} (manual opt-in; experimental, no target-phone validation)')


if __name__ == '__main__':
    main()
