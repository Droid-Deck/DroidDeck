"""Build the private Qualcomm Gamescope on ARM64 Linux and retain its source."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
REVISION = '8f212644c46460549035971ab914421180f61a7c'
OPTIONS = ['-Dbuildtype=release', '-Denable_openvr_support=false',
           '-Denable_gamescope_wsi_layer=false', '-Denable_tests=false',
           '-Ddrm_backend=enabled', '-Dsdl2_backend=enabled', '-Dpipewire=disabled',
           '-Davif_screenshots=disabled', '-Dinput_emulation=disabled', '-Dbenchmark=disabled']

def run(args, cwd=None):
    subprocess.run([str(arg) for arg in args], cwd=cwd, check=True)

def patches():
    base = ROOT / 'tools/gamescope/patches'
    extra = HERE / 'gamescope-patches'
    return sorted([*base.glob('*.patch'), *extra.glob('*.patch')], key=lambda p: p.name) + [
        HERE / 'constant-yuv-samplers.patch', HERE / 'gamescope-no-dmabuf.patch']

def prepare(source):
    if source.exists():
        raise ValueError('Source directory already exists; use a new directory or --prepared-source')
    source.mkdir(parents=True)
    run(['git', 'init', source])
    run(['git', 'remote', 'add', 'origin', 'https://github.com/ValveSoftware/gamescope.git'], source)
    run(['git', 'fetch', '--depth', '1', 'origin', REVISION], source)
    run(['git', 'checkout', '--detach', 'FETCH_HEAD'], source)
    run(['git', 'submodule', 'update', '--init', '--depth', '1', 'src/reshade',
         'subprojects/libliftoff', 'subprojects/vkroots', 'subprojects/wlroots',
         'thirdparty/SPIRV-Headers'], source)
    for patch in patches():
        run(['git', 'apply', '--ignore-whitespace', patch], source)

def archive_source(source, destination):
    with destination.open('wb') as raw, gzip.GzipFile(filename='', mode='wb', fileobj=raw, mtime=0) as compressed:
        with tarfile.open(fileobj=compressed, mode='w', format=tarfile.PAX_FORMAT) as package:
            for path in sorted(source.rglob('*')):
                relative = path.relative_to(source)
                if any(part in {'.git', 'build', '__pycache__'} for part in relative.parts) or not path.is_file():
                    continue
                info = package.gettarinfo(str(path), arcname='gamescope/' + relative.as_posix())
                info.uid = info.gid = info.mtime = 0
                info.uname = info.gname = ''
                info.mode = 0o755 if path.suffix == '.sh' else 0o644
                info.pax_headers = {}
                with path.open('rb') as data:
                    package.addfile(info, data)

def package(source, output, strip_tool='strip'):
    output.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source / 'build/src/gamescope', output / 'gamescope')
    run([strip_tool, '--strip-unneeded', output / 'gamescope'])
    archive_source(source, output / 'source.tar.gz')
    manifest = {'source_commit': REVISION, 'options': OPTIONS,
                'patches': {str(p.relative_to(ROOT)).replace('\\', '/'): hashlib.sha256(p.read_bytes()).hexdigest() for p in patches()},
                'files': {name: {'sha256': hashlib.sha256((output / name).read_bytes()).hexdigest(),
                                 'size': (output / name).stat().st_size} for name in ['gamescope', 'source.tar.gz']}}
    (output / 'build.json').write_text(json.dumps(manifest, indent=2) + '\n')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, default=ROOT / 'build/qualcomm-gamescope/source')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/qualcomm-gamescope')
    parser.add_argument('--prepared-source', action='store_true')
    parser.add_argument('--prepare-only', action='store_true')
    parser.add_argument('--package-only', action='store_true')
    parser.add_argument('--strip', default='strip')
    parser.add_argument('--jobs', type=int, default=4)
    args = parser.parse_args()
    if not args.prepared_source and not args.package_only:
        prepare(args.source)
    if not args.prepare_only:
        if not args.package_only:
            run(['meson', 'setup', 'build', *OPTIONS], args.source)
            run(['ninja', '-C', 'build', '-j' + str(args.jobs), 'src/gamescope'], args.source)
        package(args.source, args.output, args.strip)
