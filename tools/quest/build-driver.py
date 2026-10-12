#!/usr/bin/env python3
"""Build the Quest allocation adapter around the unchanged bundled Android Turnip."""
import io
import json
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / 'app/build/prepared-assets/graphics_driver/adrenotools-turnip25.1.0-quest1.tzst'


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
            # Only read the known driver entry; no archive paths are extracted.
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
