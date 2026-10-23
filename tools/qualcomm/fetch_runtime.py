"""Stage the checksum-pinned ARM64 glibc bridge, including its corresponding source."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import urllib.request
import zipfile


def fetch(cache, lock):
    expected = lock['sha256']
    cache.mkdir(parents=True, exist_ok=True)
    archive = cache / (expected + '.tzst')
    def valid(path):
        return path.is_file() and path.stat().st_size == lock['size'] and hashlib.sha256(path.read_bytes()).hexdigest() == expected
    if not valid(archive):
        temporary = archive.with_suffix('.partial')
        try:
            with urllib.request.urlopen(lock['url'], timeout=120) as source, temporary.open('wb') as target:
                shutil.copyfileobj(source, target)
            if not valid(temporary):
                raise ValueError('Qualcomm bridge archive failed SHA-256 verification')
            temporary.replace(archive)
        finally:
            temporary.unlink(missing_ok=True)
    return archive


def stage(output, cache, lock, ndk=None):
    archive = fetch(cache, lock)
    output.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(archive, output / 'runtime.tzst')
    gamescope = lock['gamescope']
    built = gamescope.get('build')
    if built:
        root = Path(__file__).resolve().parents[2]
        def build_input(name):
            target = (root / built[name]).resolve()
            if not target.is_relative_to(root / 'build'):
                raise ValueError('Invalid Qualcomm Gamescope build path')
            if not target.is_file():
                raise FileNotFoundError('Build private Gamescope first with tools/qualcomm/build_gamescope.py: ' + str(target))
            return target.read_bytes()
        binary = build_input('binary')
        source = build_input('source')
        if len(source) != built['source_size'] or hashlib.sha256(source).hexdigest() != built['source_sha256']:
            raise ValueError('Qualcomm Gamescope source failed SHA-256 verification')
        licenses = output / 'licenses'
        licenses.mkdir(exist_ok=True)
        (licenses / 'gamescope-patched-source.tar.gz').unlink(missing_ok=True)
        (licenses / 'gamescope-patched-source.tar.gzip').write_bytes(source)
    else:
        with zipfile.ZipFile(fetch(cache, gamescope)) as package:
            binary = package.read(gamescope['member'])
    if hashlib.sha256(binary).hexdigest() != gamescope['member_sha256']:
        raise ValueError('Qualcomm Gamescope binary failed SHA-256 verification')
    (output / 'gamescope').write_bytes(binary)
    if 'mesa' in lock:
        root = Path(__file__).resolve().parents[2]
        directory = (root / lock['mesa']['directory']).resolve()
        if not directory.is_relative_to(root / 'build'):
            raise ValueError('Invalid Qualcomm Mesa build path')
        for entry in lock['mesa']['files']:
            relative = Path(entry['path'])
            if relative.is_absolute() or '..' in relative.parts:
                raise ValueError('Invalid Qualcomm Mesa destination')
            origin = directory / relative
            if not origin.is_file():
                raise FileNotFoundError('Build private Zink first with tools/qualcomm/build_zink.py: ' + str(origin))
            data = origin.read_bytes()
            if len(data) != entry['size'] or hashlib.sha256(data).hexdigest() != entry['sha256']:
                raise ValueError('Qualcomm Mesa member failed SHA-256 verification: ' + entry['path'])
            target = output / 'mesa' / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
    if 'wsi' in lock:
        with zipfile.ZipFile(fetch(cache, lock['wsi'])) as package:
            for entry in lock['wsi']['files']:
                target = Path(entry['path'])
                if target.is_absolute() or '..' in target.parts:
                    raise ValueError('Invalid WSI destination')
                data = package.read(entry['name'])
                if len(data) != entry['size'] or hashlib.sha256(data).hexdigest() != entry['sha256']:
                    raise ValueError('Qualcomm WSI member failed SHA-256 verification')
                destination = output / target
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(data)
    if 'shaders' in lock:
        if ndk is None:
            raise ValueError('The Android NDK is required to build Qualcomm shader compatibility')
        from build_shaders import build
        build(output / 'shaders', fetch(cache, lock['shaders']), lock['shaders'], ndk)
    (output / 'runtime.json').write_text(json.dumps(lock, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--cache', type=Path, required=True)
    parser.add_argument('--ndk', type=Path, required=True)
    args = parser.parse_args()
    stage(args.output, args.cache, json.loads(Path(__file__).with_name('runtime.json').read_text()), args.ndk)
