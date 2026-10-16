#!/usr/bin/env python3
"""Build the Quest allocation adapter around the unchanged bundled Android Turnip."""
import io
import json
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import hashlib
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / 'app/build/prepared-assets/graphics_driver/adrenotools-turnip25.1.0-quest1.tzst'
A6XX_TAG = 'v26.3.0-20261012-r2'
A6XX_SHA256 = 'bb1b2c51e6446cf428d70255d037be169ac68108c00af33aa1ba7ece711f8336'
A6XX_URL = f'https://github.com/The412Banner/Banners-Turnip/releases/download/{A6XX_TAG}/Turnip-{A6XX_TAG}.zip'


def pack(entries, output):
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode='w', format=tarfile.USTAR_FORMAT) as archive:
        for name, content in entries:
            entry = tarfile.TarInfo(name)
            entry.size = len(content)
            entry.mode = 0o644
            archive.addfile(entry, io.BytesIO(content))
    compressed = subprocess.run(['zstd', '-q', '-19', '-c'], input=stream.getvalue(),
                                capture_output=True, check=True).stdout
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(compressed)
    print(output)


def build_a6xx(ndk, output_dir=OUTPUT.parent, archive_path=None):
    """Pin a real A6xx driver, with a separate Quest adapter around the same bytes."""
    cached = archive_path or ROOT / 'app/build/quest-inputs' / f'Turnip-{A6XX_TAG}.zip'
    if not cached.exists():
        cached.parent.mkdir(parents=True, exist_ok=True)
        data = urllib.request.urlopen(A6XX_URL, timeout=120).read()
        if hashlib.sha256(data).hexdigest() != A6XX_SHA256:
            raise ValueError('A6xx archive checksum mismatch')
        cached.write_bytes(data)
    data = cached.read_bytes()
    if hashlib.sha256(data).hexdigest() != A6XX_SHA256:
        raise ValueError('A6xx archive checksum mismatch')
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        driver = archive.read('libvulkan_freedreno.so')
        original_meta = json.loads(archive.read('meta.json'))
    if original_meta.get('libraryName') != 'libvulkan_freedreno.so':
        raise ValueError('Unexpected A6xx driver library')
    hosts = list((Path(ndk) / 'toolchains/llvm/prebuilt').glob('*'))
    if len(hosts) != 1:
        raise ValueError('Expected one NDK host toolchain')
    llvm = hosts[0] / 'bin'
    provenance = (f'Banners-Turnip {A6XX_TAG}\n{A6XX_URL}\nSHA256 {A6XX_SHA256}\n'
                  'Mesa source: a0ff50acf0e1d152f54949490fa41558d2e0f7a4\n'
                  'Quest adapter: DroidDeck tools/quest (GPL-3.0-or-later)\n').encode()
    for quest in (False, True):
        meta = dict(original_meta)
        meta['name'] = 'Turnip Adreno 6xx' + (' - Quest 2 GPUOBJ experimental' if quest else ' - bundled fallback')
        meta['description'] += ' Bundled for Adreno 6xx; Quest 2 hardware validation pending.'
        entries = [('libvulkan_freedreno.so', driver), ('SOURCE.txt', provenance)]
        if quest:
            with tempfile.TemporaryDirectory(prefix='quest-a6xx-') as folder:
                work = Path(folder)
                original = work / 'libvulkan_freedreno.so'
                original.write_bytes(driver)
                wrapper = work / 'libquest_turnip.so'
                subprocess.run([
                    str(llvm / 'aarch64-linux-android28-clang'), '-shared', '-fPIC', '-O2',
                    '-Wall', '-Wextra', '-Werror', '-fvisibility=hidden',
                    '-Wl,-z,max-page-size=16384', '-Wl,-soname,libquest_turnip.so',
                    '-Wl,--no-as-needed', str(original), '-Wl,--as-needed',
                    str(ROOT / 'tools/quest/android_wrapper.c'), str(ROOT / 'tools/quest/kgsl_compat.c'),
                    '-ldl', '-llog', '-o', str(wrapper),
                ], check=True)
                subprocess.run([str(llvm / 'llvm-strip'), '--strip-unneeded', str(wrapper)], check=True)
                entries.append(('libquest_turnip.so', wrapper.read_bytes()))
            meta['libraryName'] = 'libquest_turnip.so'
        entries.append(('meta.json', (json.dumps(meta, indent=2) + '\n').encode()))
        name = 'turnip26.3.0-quest2' if quest else 'turnip26.3.0-a6xx'
        pack(entries, output_dir / f'adrenotools-{name}.tzst')


def build(ndk, output=OUTPUT):
    hosts = list((Path(ndk) / 'toolchains/llvm/prebuilt').glob('*'))
    if len(hosts) != 1:
        raise ValueError('Expected one NDK host toolchain')
    llvm = hosts[0] / 'bin'
    original = ROOT / 'app/src/main/assets/graphics_driver/adrenotools-turnip25.1.0.tzst'
    data = subprocess.run(['zstd', '-dc', str(original)], capture_output=True, check=True).stdout
    with tempfile.TemporaryDirectory(prefix='quest-driver-') as folder:
        work = Path(folder)
        with tarfile.open(fileobj=io.BytesIO(data)) as archive:
            driver = archive.extractfile('vulkan.ad07xx.so').read()
        (work / 'vulkan.ad07xx.so').write_bytes(driver)
        wrapper = work / 'libquest_turnip.so'
        subprocess.run([
            str(llvm / 'aarch64-linux-android26-clang'), '-shared', '-fPIC', '-O2',
            '-Wall', '-Wextra', '-Werror', '-fvisibility=hidden',
            '-Wl,-z,max-page-size=16384', '-Wl,-soname,libquest_turnip.so',
            '-Wl,--no-as-needed', str(work / 'vulkan.ad07xx.so'), '-Wl,--as-needed',
            str(ROOT / 'tools/quest/android_wrapper.c'), str(ROOT / 'tools/quest/kgsl_compat.c'),
            '-ldl', '-llog', '-o', str(wrapper),
        ], check=True)
        subprocess.run([str(llvm / 'llvm-strip'), '--strip-unneeded', str(wrapper)], check=True)
        metadata = json.dumps({
            'libraryName': 'libquest_turnip.so',
            'name': 'Turnip Adreno 7xx - Quest GPUOBJ experimental',
            'description': 'Bundled A7xx driver with the experimental Quest 3/3S allocation adapter.',
            'driverVersion': 'Bundled Mesa + Quest GPUOBJ v1',
        }, indent=2).encode() + b'\n'
        stream = io.BytesIO()
        with tarfile.open(fileobj=stream, mode='w', format=tarfile.USTAR_FORMAT) as archive:
            for name, content in [('meta.json', metadata), ('libquest_turnip.so', wrapper.read_bytes()),
                                  ('vulkan.ad07xx.so', driver)]:
                entry = tarfile.TarInfo(name)
                entry.size = len(content)
                entry.mode = 0o644
                archive.addfile(entry, io.BytesIO(content))
        compressed = subprocess.run(['zstd', '-q', '-19', '-c'], input=stream.getvalue(),
                                    capture_output=True, check=True).stdout
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(compressed)
        print(output)


if __name__ == '__main__':
    build(sys.argv[1])
    build_a6xx(sys.argv[1])
