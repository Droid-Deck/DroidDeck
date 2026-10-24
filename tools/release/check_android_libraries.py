"""Validate the JNI entry points and Android ELF dependency closure in the actual APK."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import tempfile
import tarfile
from zipfile import ZipFile


SYSTEM = set('libc.so libm.so libdl.so liblog.so libandroid.so libz.so libvulkan.so libstdc++.so '
             'libGLESv2.so libGLESv3.so libEGL.so libnativewindow.so libjnigraphics.so libaaudio.so '
             'libOpenSLES.so libmediandk.so libcamera2ndk.so libsync.so libneuralnetworks.so'.split())
EXPORTS = ['Java_com_droiddeck_launcher_stores_StoresNative_nativeVersion',
           'Java_com_droiddeck_launcher_stores_gog_GogNative_nativeStart',
           'Java_com_droiddeck_launcher_stores_epic_EpicNative_nativeCall',
           'Java_com_droiddeck_launcher_stores_epic_EpicNative_nativeInstall']
AMAZON_EXPORTS = ['Java_com_droiddeck_launcher_stores_amazon_AmazonNative_native' + name
                  for name in ['EngineAbi', 'Configure', 'Call', 'Fetch', 'Inspect', 'Install', 'Cancel', 'Release']]


def check_amazon_pack(path, readelf):
    errors = []
    raw = subprocess.check_output(['zstd', '-dc', str(path)])
    with tarfile.open(fileobj=io.BytesIO(raw)) as archive:
        if sorted(archive.getnames()) != ['engine.json', 'libdroiddeckamazon.so']:
            return ['Amazon pack must contain exactly its manifest and native engine']
        for member in archive:
            if not member.isfile():
                return ['Amazon pack contains a non-regular file']
        library = archive.extractfile('libdroiddeckamazon.so').read()
        metadata = json.load(archive.extractfile('engine.json'))
    if (metadata.get('schema'), metadata.get('engine'), metadata.get('abi'), metadata.get('target')) != (
            1, 'amazon', 1, 'aarch64-linux-android'):
        errors.append('Amazon engine metadata is incompatible')
    if metadata.get('library_sha256') != hashlib.sha256(library).hexdigest():
        errors.append('Amazon engine digest differs from its manifest')
    with tempfile.TemporaryDirectory() as temp:
        binary = Path(temp) / 'libdroiddeckamazon.so'
        binary.write_bytes(library)
        header = subprocess.check_output([readelf, '-h', str(binary)], text=True)
        if 'AArch64' not in header or 'ELF64' not in header:
            errors.append('Amazon engine is not Android ARM64')
        symbols = subprocess.check_output([readelf, '--dyn-syms', '--wide', str(binary)], text=True)
        exports = {line.split()[-1] for line in symbols.splitlines()
                   if ' GLOBAL ' in line and ' UND ' not in line and line.split()}
        errors += ['Amazon library missing ' + symbol for symbol in AMAZON_EXPORTS if symbol not in exports]
        if any(symbol in exports for symbol in EXPORTS):
            errors.append('Amazon engine still exports other store entry points')
        dynamic = subprocess.check_output([readelf, '-d', str(binary)], text=True)
        errors += ['Amazon engine needs missing ' + name for name in re.findall(r'NEEDED.*\[(.*?)\]', dynamic)
                   if name not in SYSTEM]
    return errors


def check(apk, readelf, stores=True):
    errors = []
    with tempfile.TemporaryDirectory() as temp, ZipFile(apk) as package:
        libraries = {}
        for name in package.namelist():
            if name.startswith('lib/arm64-v8a/') and name.endswith('.so'):
                path = Path(temp) / Path(name).name
                path.write_bytes(package.read(name))
                libraries[path.name] = path
        for name, path in libraries.items():
            dynamic = subprocess.check_output([readelf, '-d', str(path)], text=True)
            for dependency in re.findall(r'NEEDED.*\[(.*?)\]', dynamic):
                if dependency not in libraries and dependency not in SYSTEM:
                    errors.append(name + ' needs missing ' + dependency)
        library = libraries.get('libdroiddeckstores.so')
        if stores:
            if library is None:
                errors.append('APK missing libdroiddeckstores.so')
            else:
                symbols = subprocess.check_output([readelf, '--dyn-syms', '--wide', str(library)], text=True)
                exports = {line.split()[-1] for line in symbols.splitlines()
                           if ' GLOBAL ' in line and ' UND ' not in line and line.split()}
                errors += ['Store library missing ' + symbol for symbol in EXPORTS if symbol not in exports]
                if any(symbol in exports for symbol in AMAZON_EXPORTS):
                    errors.append('Bundled store library still contains Amazon entry points')
        elif library is not None:
            errors.append('Rust was skipped but the APK contains a cached store library')
        if 'libdroiddeckamazon.so' in libraries:
            errors.append('APK still bundles the optional Amazon engine')
    return errors


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk')
    parser.add_argument('--readelf', default='readelf')
    parser.add_argument('--without-stores', action='store_true')
    parser.add_argument('--amazon-pack', default='app/build/packs/amazon.tar.zst')
    args = parser.parse_args()
    errors = check(args.apk, args.readelf, not args.without_stores)
    if not args.without_stores:
        errors += check_amazon_pack(args.amazon_pack, args.readelf)
    if errors:
        parser.exit(1, '\n'.join(errors) + '\n')
    print('APK native exports and dependencies verified')
