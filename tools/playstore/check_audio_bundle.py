import argparse
import gzip
import hashlib
from pathlib import Path
import subprocess
import sys
import tempfile
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from tools.playstore.build_audio import LIBRARIES, verify
from tools.release.check_android_libraries import page_alignment


def check(bundle, output, ndk):
    manifest = verify(output, ndk)
    readelf = str(ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf')
    nm = str(ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm')
    with ZipFile(bundle) as package, tempfile.TemporaryDirectory() as temporary:
        names = package.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Bundle contains duplicate entries')
        for name, digest in manifest['outputs'].items():
            if name.startswith('assets/'):
                asset = 'base/' + name
                if asset not in names and name.endswith('.tar.gz'):
                    asset = asset.removesuffix('.gz')
                    expected = hashlib.sha256()
                    with gzip.open(output / name, 'rb') as source:
                        for block in iter(lambda: source.read(1024 * 1024), b''):
                            expected.update(block)
                    digest = expected.hexdigest()
                actual = hashlib.sha256()
                with package.open(asset) as stream:
                    for block in iter(lambda: stream.read(1024 * 1024), b''):
                        actual.update(block)
                if actual.hexdigest() != digest:
                    raise ValueError('Bundle audio asset differs from verified output: ' + asset)
        for name in LIBRARIES:
            path = Path(temporary) / name
            path.write_bytes(package.read('base/lib/arm64-v8a/' + name))
            errors = page_alignment(path, readelf, 16384)
            if errors:
                raise ValueError('\n'.join(errors))
            def exports(library):
                result = subprocess.check_output([nm, '-D', '--defined-only', str(library)], text=True)
                return {line.split()[-1].split('@')[0] for line in result.splitlines() if line.split()}
            rebuilt = output / 'jni/arm64-v8a' / name
            if exports(rebuilt) - exports(path):
                raise ValueError('Bundle audio library dropped rebuilt exports: ' + name)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--ndk', type=Path, required=True)
    arguments = parser.parse_args()
    check(arguments.bundle, arguments.output, arguments.ndk)
    print('Bundle audio assets, corresponding sources and native exports verified')


if __name__ == '__main__':
    main()
