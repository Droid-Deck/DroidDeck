"""Validate the JNI entry points and Android ELF dependency closure in the actual APK."""
import argparse
from pathlib import Path
import re
import subprocess
import tempfile
from zipfile import ZipFile


SYSTEM = set('libc.so libm.so libdl.so liblog.so libandroid.so libz.so libvulkan.so libstdc++.so '
             'libGLESv2.so libEGL.so libnativewindow.so libjnigraphics.so libaaudio.so '
             'libOpenSLES.so libmediandk.so libcamera2ndk.so libsync.so libneuralnetworks.so'.split())
EXPORTS = ['Java_com_droiddeck_launcher_stores_StoresNative_nativeVersion',
           'Java_com_droiddeck_launcher_stores_gog_GogNative_nativeStart',
           'Java_com_droiddeck_launcher_stores_epic_EpicNative_nativeStart',
           'Java_com_droiddeck_launcher_stores_epic_EpicNative_nativeAssemble',
           'Java_com_droiddeck_launcher_stores_amazon_AmazonNative_nativeStart']


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
        elif library is not None:
            errors.append('Rust was skipped but the APK contains a cached store library')
    return errors


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk')
    parser.add_argument('--readelf', default='readelf')
    parser.add_argument('--without-stores', action='store_true')
    args = parser.parse_args()
    errors = check(args.apk, args.readelf, not args.without_stores)
    if errors:
        parser.exit(1, '\n'.join(errors) + '\n')
    print('APK native exports and dependencies verified')
