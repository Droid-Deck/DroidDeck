"""Build checksum-pinned Android RGB UVC libraries and their fault-injection test."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import urllib.request
import zipfile


ROOT = Path(__file__).resolve().parents[1]
RECIPES = ROOT / 'tools/uvc'
PACKAGES = (
    {'name': 'libusb', 'version': '1.0.30', 'license': 'LGPL-2.1-or-later',
     'url': 'https://github.com/libusb/libusb/releases/download/v1.0.30/libusb-1.0.30.tar.bz2',
     'archive': 'libusb-1.0.30.tar.bz2',
     'sha256': 'fea36f34f9156400209595e300840767ab1a385ede1dc7ee893015aea9c6dbaf'},
    {'name': 'libuvc', 'version': '0.0.8', 'license': 'BSD-3-Clause',
     'url': 'https://github.com/libuvc/libuvc/archive/refs/tags/v0.0.8.zip',
     'archive': 'libuvc-0.0.8.zip',
     'sha256': '67922938ad81eb82b8ce14576f15a68ac75e30af738728a1f3070b6bbc938bce'},
)


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def download(package, cache):
    archive = cache / package['archive']
    if not archive.exists():
        request = urllib.request.Request(package['url'], headers={'User-Agent': 'DroidDeckXR-UVC-Build'})
        with urllib.request.urlopen(request, timeout=60) as response:
            payload = response.read()
        if hashlib.sha256(payload).hexdigest() != package['sha256']:
            raise ValueError(f"Downloaded checksum mismatch: {package['archive']}")
        archive.write_bytes(payload)
    if sha256(archive) != package['sha256']:
        raise ValueError(f'Cached checksum mismatch: {archive}')
    return archive


def extract(archive, destination):
    if archive.suffix == '.zip':
        with zipfile.ZipFile(archive) as bundle:
            for item in bundle.infolist():
                (destination / item.filename).resolve().relative_to(destination.resolve())
                if (item.external_attr >> 16) & 0o170000 == 0o120000:
                    raise ValueError('Symlink in ZIP archive')
            bundle.extractall(destination)
    else:
        with tarfile.open(archive) as bundle:
            bundle.extractall(destination, filter='data')


def patch_source(source, patch):
    old, new = [], []
    content = source.read_text(encoding='utf-8')
    hunks = []
    for line in patch.read_text(encoding='utf-8').splitlines(keepends=True):
        if line.startswith(('--- ', '+++ ')):
            continue
        if line.startswith('@@ '):
            if old or new:
                hunks.append((''.join(old), ''.join(new)))
            old, new = [], []
        elif line.startswith(' '):
            old.append(line[1:])
            new.append(line[1:])
        elif line.startswith('-'):
            old.append(line[1:])
        elif line.startswith('+'):
            new.append(line[1:])
        else:
            raise ValueError(f'Unsupported patch line: {line!r}')
    hunks.append((''.join(old), ''.join(new)))
    for before, after in hunks:
        if not before or content.count(before) != 1:
            raise ValueError(f'Patch context mismatch: {source}')
        content = content.replace(before, after, 1)
    source.write_text(content, encoding='utf-8', newline='\n')


def run(command, log):
    result = subprocess.run([str(part) for part in command], stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, encoding='utf-8')
    log.write_text(result.stdout, encoding='utf-8')
    print(result.stdout, end='')
    result.check_returncode()


def build(args):
    output, cache = args.output.resolve(), args.cache.resolve()
    source, binary, dist = output / 'src', output / 'android-arm64', output / 'dist'
    for folder in (output, cache, source):
        folder.mkdir(parents=True, exist_ok=True)
    for package in PACKAGES:
        extract(download(package, cache), source)
    patch = RECIPES / 'libuvc-submit-failure.patch'
    stream = source / 'libuvc-0.0.8/src/stream.c'
    patch_source(stream, patch)

    ndk, cmake = args.ndk.resolve(), args.cmake.resolve()
    ninja = args.ninja.resolve() if args.ninja else cmake.parent / ('ninja.exe' if os.name == 'nt' else 'ninja')
    configure = [cmake, '-S', RECIPES, '-B', binary, '-G', 'Ninja',
                 f'-DCMAKE_MAKE_PROGRAM={ninja.as_posix()}',
                 f'-DCMAKE_TOOLCHAIN_FILE={(ndk / "build/cmake/android.toolchain.cmake").as_posix()}',
                 f'-DRGB_UVC_SOURCE_ROOT={source.as_posix()}', '-DANDROID_ABI=arm64-v8a',
                 '-DANDROID_PLATFORM=android-26', '-DANDROID_STL=c++_static',
                 '-DCMAKE_BUILD_TYPE=RelWithDebInfo']
    compile_command = [cmake, '--build', binary, '--parallel', str(args.jobs)]
    run(configure, output / 'configure.log')
    run(compile_command, output / 'build.log')

    copies = {
        binary / 'libusb.so': dist / 'arm64-v8a/libusb.so',
        binary / 'libuvc.a': dist / 'arm64-v8a/libuvc.a',
        binary / 'uvc_submit_failure_test': dist / 'arm64-v8a/uvc_submit_failure_test',
        source / 'libusb-1.0.30/libusb/libusb.h': dist / 'include/libusb.h',
        source / 'libuvc-0.0.8/include/libuvc/libuvc.h': dist / 'include/libuvc/libuvc.h',
        binary / 'generated/libuvc/libuvc_config.h': dist / 'include/libuvc/libuvc_config.h',
        source / 'libusb-1.0.30/COPYING': dist / 'licenses/libusb-COPYING',
        source / 'libuvc-0.0.8/LICENSE.txt': dist / 'licenses/libuvc-LICENSE.txt',
    }
    for original, target in copies.items():
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(original, target)
    prebuilt = list((ndk / 'toolchains/llvm/prebuilt').iterdir())
    if len(prebuilt) != 1:
        raise ValueError(f'Expected one NDK host toolchain: {prebuilt}')
    readelf = prebuilt[0] / 'bin' / ('llvm-readelf.exe' if os.name == 'nt' else 'llvm-readelf')
    reports = []
    for name in ('libusb.so', 'uvc_submit_failure_test'):
        result = subprocess.check_output([str(readelf), '-h', '-l', '-d', str(binary / name)],
                                         text=True, encoding='utf-8')
        if 'AArch64' not in result or 'TEXTREL' in result:
            raise ValueError(f'Unexpected ELF ABI or text relocations: {name}')
        loads = [line for line in result.splitlines() if line.lstrip().startswith('LOAD')]
        if not loads or any(not line.rstrip().endswith('0x4000') for line in loads):
            raise ValueError(f'ELF must use 16 KiB LOAD alignment: {name}')
        if name == 'uvc_submit_failure_test' and ('[libusb.so]' not in result or 'libglasses' in result):
            raise ValueError('Fault test must depend on standalone libusb')
        reports.append(result)
    (output / 'elf-validation.txt').write_text('\n'.join(reports), encoding='utf-8')
    manifest = {
        'packages': PACKAGES,
        'patch': {'path': str(patch.relative_to(ROOT)), 'sha256': sha256(patch),
                  'sourceSha256': sha256(stream),
                  'reason': 'Preserve USB submission errors; remove unsubmitted transfers under cb_mutex, then cancel/drain submitted transfers and join the callback thread.'},
        'build': {'ndk': str(ndk), 'ndkSourceProperties': (ndk / 'source.properties').read_text(encoding='utf-8'),
                  'configure': [str(part) for part in configure], 'compile': [str(part) for part in compile_command],
                  'api': 26, 'abi': 'arm64-v8a', 'pic': True, 'queueBuffers': 8,
                  'isoPacketsPerTransferMaximum': 32, 'jpegConversion': False,
                  'libusbShared': True, 'libuvcStatic': True,
                  'recipeHashes': {str(path.relative_to(ROOT)): sha256(path) for path in
                                   (Path(__file__), RECIPES / 'CMakeLists.txt', RECIPES / 'submit_failure_test.cpp')}},
        'validation': {'nativeBuild': 'passed', 'linkNoUndefined': 'passed', 'machine': 'AArch64',
                       'loadAlignmentBytes': 16384, 'textRelocations': False,
                       'deviceRuntimeTest': 'not run by build script'},
        'artifacts': [{'path': str(path.relative_to(output)), 'sha256': sha256(path), 'size': path.stat().st_size}
                      for path in copies.values()],
    }
    (output / 'provenance.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    print(f'Libraries and headers: {dist}')
    print(f'Provenance: {output / "provenance.json"}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--cmake', type=Path, required=True)
    parser.add_argument('--ninja', type=Path)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/rgb-uvc')
    parser.add_argument('--cache', type=Path, default=ROOT / 'build/uvc-source-cache')
    parser.add_argument('--jobs', type=int, default=4)
    build(parser.parse_args())
