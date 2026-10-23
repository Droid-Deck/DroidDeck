"""Build private ARM64 Zink libraries with the pre-maintenance5 fallback."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
REVISION = '31e9a6b2e95e30d84bf3177d1f497d063e59b6b2'
OPTIONS = ['-Dbuildtype=release', '-Dgallium-drivers=zink', '-Dvulkan-drivers=[]',
           '-Dllvm=disabled', '-Dplatforms=x11,wayland', '-Dglvnd=enabled',
           '-Dglvnd-vendor-name=droiddeck_qualcomm', '-Dgallium-va=disabled',
           '-Dvideo-codecs=[]', '-Dbuild-tests=false', '-Dtools=[]',
           '-Dglx=dri', '-Degl=enabled', '-Dgbm=enabled']
LIBRARIES = {
    'libgallium-26.2.3.so': 'src/gallium/targets/dri/libgallium-26.2.3.so',
    'dri/zink_dri.so': 'src/gallium/targets/dril/libdril_dri.so',
    'libGLX_droiddeck_qualcomm.so.0': 'src/glx/libGLX_droiddeck_qualcomm.so.0.0.0',
    'libEGL_droiddeck_qualcomm.so.0': 'src/egl/libEGL_droiddeck_qualcomm.so.0.0.0',
    'libgbm.so.1': 'src/gbm/libgbm.so.1.0.0',
    'gbm/dri_gbm.so': 'src/gbm/backends/dri/dri_gbm.so',
}

def run(args, cwd=None):
    subprocess.run([str(arg) for arg in args], cwd=cwd, check=True)

def prepare(source):
    if source.exists():
        raise ValueError('Source directory already exists; use a new directory or --prepared-source')
    source.mkdir(parents=True)
    run(['git', 'init', source])
    run(['git', 'remote', 'add', 'origin', 'https://gitlab.freedesktop.org/mesa/mesa.git'], source)
    run(['git', 'fetch', '--depth', '1', 'origin', REVISION], source)
    run(['git', 'checkout', '--detach', 'FETCH_HEAD'], source)
    run(['git', 'apply', HERE / 'zink-optional-maintenance5.patch'], source)

def package(source, output, strip_tool):
    output.mkdir(parents=True, exist_ok=True)
    for name, relative in LIBRARIES.items():
        destination = output / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source / 'build' / relative, destination)
        run([strip_tool, '--strip-unneeded', destination])
    (output / 'egl-vendor.json').write_text(json.dumps({
        'file_format_version': '1.0.0', 'ICD': {'library_path': 'libEGL_droiddeck_qualcomm.so.0'}}) + '\n')
    shutil.copyfile(HERE / 'qualcomm-gl.sh', output / 'qualcomm-gl.sh')
    shutil.copytree(source / 'licenses', output / 'licenses', dirs_exist_ok=True)
    shutil.copyfile(source / 'docs/license.rst', output / 'licenses/Mesa-license.rst')
    shutil.copyfile(HERE / 'zink-optional-maintenance5.patch', output / 'licenses/zink-optional-maintenance5.patch')
    (output / 'licenses/source.json').write_text(json.dumps({
        'url': 'https://gitlab.freedesktop.org/mesa/mesa/-/tree/' + REVISION,
        'source_commit': REVISION, 'options': OPTIONS,
        'patch_sha256': hashlib.sha256((HERE / 'zink-optional-maintenance5.patch').read_bytes()).hexdigest()}, indent=2) + '\n')
    files = [{'path': p.relative_to(output).as_posix(), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest(),
              'size': p.stat().st_size} for p in sorted(output.rglob('*')) if p.is_file() and p.name != 'build.json']
    (output / 'build.json').write_text(json.dumps({'source_commit': REVISION, 'files': files}, indent=2) + '\n')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, default=ROOT / 'build/qualcomm-zink/source')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/qualcomm-zink/runtime')
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
            run(['ninja', '-C', 'build', '-j' + str(args.jobs)], args.source)
        package(args.source, args.output, args.strip)
