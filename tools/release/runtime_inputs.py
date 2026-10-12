"""Attest prepared runtime inputs and reject stale local or CI APK payloads."""
import hashlib
import gzip
import json
from pathlib import Path
import subprocess
import sys
import tarfile
import shutil
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[2]
MANIFEST = Path('app/build/runtime-inputs.json')
SOURCE_PATHS = ['app/build.gradle', 'app/src/main/cpp/fakeinput_steam.cpp', 'app/src/main/assets/pulseaudio.tzst',
                'tools/linuxfs', 'tools/proot', 'tools/aaudio-sink', 'tools/directaudio',
                'tools/gamescope/release.env', 'tools/droiddeck-esync/release.env',
                'tools/mangoapp', 'tools/msitools', 'tools/build_local.sh',
                'tools/release/runtime_inputs.py', 'app/src/main/assets/desktop-removal']


def digest(path):
    with path.open('rb') as source:
        result = hashlib.file_digest(source, 'sha256') if hasattr(hashlib, 'file_digest') else None
        if result is None:
            result = hashlib.sha256()
            for block in iter(lambda: source.read(1024 * 1024), b''):
                result.update(block)
    return result.hexdigest()


def sources(root):
    files = []
    for name in SOURCE_PATHS:
        path = root / name
        files.extend(path.rglob('*') if path.is_dir() else [path])
    return {str(path.relative_to(root)): digest(path) for path in sorted(set(files))
            if path.is_file() and '__pycache__' not in path.parts and path.suffix != '.pyc'}


def outputs(root):
    result = {}
    for tree in ['linuxfs', 'directaudio', 'droiddeck-esync', 'desktop-removal']:
        base = root / 'app/src/main/assets' / tree
        for path in base.rglob('*'):
            if path.is_file() and not any(part.startswith('.') for part in path.relative_to(base).parts):
                result['assets/' + tree + '/' + str(path.relative_to(base))] = str(path.relative_to(root))
    for library in ['libproot.so', 'libproot-loader.so', 'libdirectaudiorelay.so']:
        result['lib/arm64-v8a/' + library] = 'app/src/main/jniLibs/arm64-v8a/' + library
    result['assets/pulseaudio.tzst'] = 'app/build/prepared-assets/pulseaudio.tzst'
    return result


def required():
    paths = ['assets/linuxfs/' + name for name in ['libfakeinput.so', 'libblsession.so',
             'libblfastpath.so', 'libssbs.so', 'usr/local/bin/droiddeck-clipboard',
             'usr/local/bin/gamescope', 'usr/local/lib/droiddeck/uruntime']]
    for arch in ['x86_64', 'i386']:
        paths += ['assets/linuxfs/' + arch + '/' + lib for lib in ['libfakeinput.so', 'libblsession.so']]
    paths += ['assets/linuxfs/x86_64/' + lib for lib in
              ['libfaultreport.so', 'libthunkaudit.so', 'libvulkan-thunk.so']]
    paths += ['assets/linuxfs/i386/libfaultreport.so']
    paths += ['assets/linuxfs/usr/local/lib/droiddeck-msitools/' + name for name in
              ['msiinfo', 'cabextract', '7z', '7z.so', 'libmsi-1.0.so.0', 'libgsf-1.so.114', 'libgcab-1.0.so.0']]
    paths += ['assets/linuxfs/usr/local/lib/mangoapp/' + name for name in
              ['mangoapp', 'libfmt.so.10', 'libspdlog.so.1.13', 'libglfw.so.3', 'libtraceevent.so.1', 'libtracefs.so.1']]
    for driver in ['linux-wine11', 'linux-wine11-systhread']:
        paths += ['assets/directaudio/' + driver + '/' + name for name in
                  ['aarch64-unix/winedirectaudio.so', 'aarch64-windows/winedirectaudio.drv',
                   'i386-windows/winedirectaudio.drv', 'version.txt']]
    paths += ['assets/droiddeck-esync/index.json', 'assets/droiddeck-esync/index.json.sig',
              'lib/arm64-v8a/libproot.so', 'lib/arm64-v8a/libproot-loader.so',
              'lib/arm64-v8a/libdirectaudiorelay.so', 'assets/pulseaudio.tzst']
    paths += ['assets/desktop-removal/' + name for name in
              ['lxqt-r1.tsv.gzip', 'kde-remove.tsv.gzip', 'kde-keep.txt.gzip',
               'emulators-keep.txt.gzip', 'versions.txt', 'sources.json']]
    return paths


def record(root=ROOT):
    # Refresh scripts even on a CI native-cache hit; these are source, not cached binaries.
    if root == ROOT:
        from check_session_assets import source_assets
        for source, asset in source_assets().items():
            target = root / 'app/src/main' / asset
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
    paths = outputs(root)
    missing = [name for name in required() if name not in paths or not (root / paths[name]).is_file()]
    if missing:
        raise ValueError('Missing runtime inputs:\n' + '\n'.join(missing))
    for name, path in paths.items():
        if name.startswith('assets/desktop-removal/') and name.endswith('.gzip'):
            try:
                gzip.decompress((root / path).read_bytes())
            except (OSError, EOFError) as error:
                raise ValueError('Invalid desktop removal inventory: ' + name) from error
    # Check inside the compressed payload, where a plain Gradle build used to ship no sinks.
    process = subprocess.Popen(['zstd', '-dc', str(root / paths['assets/pulseaudio.tzst'])], stdout=subprocess.PIPE)
    try:
        with tarfile.open(fileobj=process.stdout, mode='r|') as archive:
            names = {member.name.removeprefix('./') for member in archive}
    finally:
        process.stdout.close()
    if process.wait():
        raise ValueError('Invalid audio bundle')
    for name in ['pactl', 'modules/arm64/module-aaudio-sink.so', 'modules/arm64/module-directaudio-native-sink.so']:
        if name not in names:
            raise ValueError('Audio bundle missing ' + name)
    data = {'version': 1, 'sources': sources(root), 'outputs': {
        name: {'path': path, 'sha256': digest(root / path)} for name, path in sorted(paths.items())}}
    (root / MANIFEST).write_text(json.dumps(data, sort_keys=True, indent=2) + '\n')


def verify(root=ROOT):
    try:
        data = json.loads((root / MANIFEST).read_text())
    except (OSError, ValueError):
        raise ValueError('Runtime inputs have not been prepared. Run tools/build_local.sh.')
    if data.get('version') != 1 or data.get('sources') != sources(root):
        raise ValueError('Runtime sources changed since preparation. Run tools/build_local.sh.')
    for name in required():
        if name not in data['outputs']:
            raise ValueError('Runtime manifest missing ' + name)
    for name, output in data['outputs'].items():
        path = root / output['path']
        if not path.is_file() or digest(path) != output['sha256']:
            raise ValueError('Missing or changed runtime input: ' + name + '. Run tools/build_local.sh.')
    if outputs(root) != {name: output['path'] for name, output in data['outputs'].items()}:
        raise ValueError('Runtime file set changed. Run tools/build_local.sh.')
    return data


def check_apk(apk, root=ROOT):
    data = verify(root)
    with ZipFile(apk) as package:
        for name, output in data['outputs'].items():
            try:
                content = package.read(name)
            except KeyError:
                raise ValueError('APK missing ' + name)
            if hashlib.sha256(content).hexdigest() != output['sha256']:
                raise ValueError('APK has stale runtime input: ' + name)


if __name__ == '__main__':
    try:
        if sys.argv[1] == 'record':
            record()
        elif sys.argv[1] == 'verify':
            verify()
        elif sys.argv[1] == 'apk':
            check_apk(sys.argv[2])
        else:
            raise ValueError('Expected record, verify or apk <path>')
    except (ValueError, OSError) as error:
        sys.exit(str(error))
    print('Runtime inputs verified')
