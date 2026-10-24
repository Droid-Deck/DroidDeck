"""Build the Qualcomm Vulkan WSI layer with Wayland presentation on ARM64 Linux."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
OPTIONS = ['-DCMAKE_BUILD_TYPE=Release', '-DBUILD_WSI_WAYLAND=ON', '-DBUILD_WSI_X11=ON', '-DBUILD_WSI_HEADLESS=OFF',
           '-DBUILD_WSI_DISPLAY=OFF', '-DSELECT_EXTERNAL_ALLOCATOR=dma_buf_heaps', '-DWSIALLOC_MEMORY_HEAP_NAME=system']
OUTPUTS = ['libVkLayer_window_system_integration.so', 'VkLayer_window_system_integration.json']


def run(args, cwd=None):
    subprocess.run([str(arg) for arg in args], cwd=cwd, check=True)


def patches():
    return sorted((HERE / 'wsi-patches').glob('*.patch'), key=lambda p: p.name)


def pinned_archive(cache):
    lock = json.loads((HERE / 'runtime.json').read_text())['wsi']
    archive = cache / (lock['sha256'] + '.tzst')
    if not archive.is_file() or hashlib.sha256(archive.read_bytes()).hexdigest() != lock['sha256']:
        raise ValueError('Stage the pinned WSI archive first (tools/qualcomm/fetch_runtime.py): ' + str(archive))
    return archive


def prepare(source, cache, zstd):
    if source.exists():
        raise ValueError('Source directory already exists; use a new directory or --prepared-source')
    source.mkdir(parents=True)
    with zipfile.ZipFile(pinned_archive(cache)) as package:
        packed = package.read('source.tar.zst')
    unpacked = subprocess.run([zstd, '-dc'], input=packed, capture_output=True, check=True).stdout
    tar_path = source.parent / (source.name + '.tar')
    tar_path.write_bytes(unpacked)
    try:
        with tarfile.open(tar_path) as archive:
            archive.extractall(source, filter='data')
    finally:
        tar_path.unlink()
    for patch in patches():
        run(['git', '-c', 'core.autocrlf=false', 'apply', '--ignore-whitespace', patch], source)


def package(source, output, strip_tool='strip'):
    output.mkdir(parents=True, exist_ok=True)
    for name in OUTPUTS:
        shutil.copyfile(source / 'build' / name, output / name)
    run([strip_tool, '--strip-unneeded', output / OUTPUTS[0]])
    for patch in patches():
        shutil.copyfile(patch, output / patch.name)
    names = OUTPUTS + [patch.name for patch in patches()]
    manifest = {'options': OPTIONS,
                'files': {name: {'sha256': hashlib.sha256((output / name).read_bytes()).hexdigest(),
                                 'size': (output / name).stat().st_size} for name in names}}
    (output / 'build.json').write_text(json.dumps(manifest, indent=2) + '\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, default=ROOT / 'build/qualcomm-wsi/source')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/qualcomm-wsi')
    parser.add_argument('--cache', type=Path, default=ROOT / 'build/qualcomm-cache')
    parser.add_argument('--zstd', default='zstd')
    parser.add_argument('--prepared-source', action='store_true')
    parser.add_argument('--prepare-only', action='store_true')
    parser.add_argument('--package-only', action='store_true')
    parser.add_argument('--strip', default='strip')
    parser.add_argument('--jobs', type=int, default=4)
    args = parser.parse_args()
    if not args.prepared_source and not args.package_only:
        prepare(args.source, args.cache, args.zstd)
    if not args.prepare_only:
        if not args.package_only:
            run(['cmake', '-S', '.', '-B', 'build', '-G', 'Ninja', *OPTIONS], args.source)
            run(['ninja', '-C', 'build', '-j' + str(args.jobs)], args.source)
        package(args.source, args.output, args.strip)
