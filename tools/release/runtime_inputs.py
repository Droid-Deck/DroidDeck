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
PACKS = Path('app/build/packs')
# Runtime trees shipped as add-on packs (tools/packs/build.sh) rather than inside the APK.
PACKED = {'assets/linuxfs/usr/local/lib/droiddeck-xbox/': 'xbox', 'assets/linuxfs/usr/local/lib/droiddeck-comet/': 'comet',
          'assets/directaudio/': 'directaudio'}
PACKED_FILES = {'lib/arm64-v8a/libproot.so': ('proot', 'libproot.so'),
                'lib/arm64-v8a/libproot-loader.so': ('proot', 'libproot-loader.so')}
PACKED_LIBRARIES = ['lib/arm64-v8a/libopencv_java4.so', 'lib/arm64-v8a/libmediapipe_tasks_jni.so']
PACK_NAMES = ['proot', 'directaudio', 'tracking', 'xbox', 'comet', 'butler']
SOURCE_PATHS = ['app/build.gradle', 'app/src/main/cpp/fakeinput_steam.cpp', 'app/src/main/assets/pulseaudio.tzst',
                'tools/linuxfs', 'tools/proot', 'tools/aaudio-sink', 'tools/directaudio', 'tools/packs',
                'tools/gamescope/release.env', 'tools/droiddeck-esync/release.env',
                'tools/mangoapp', 'tools/msitools', 'tools/pipewire', 'tools/comet', 'tools/xbox', 'tools/components', 'tools/ci', 'tools/build_local.sh',
                'tools/release/runtime_inputs.py', 'app/src/main/assets/desktop-removal',
                'tools/quest', 'tools/vr/src', 'tools/vr/driver_droiddeck', 'tools/vr/layer', 'tools/vr/build.sh',
                'app/src/main/cpp/waylandcomp/src/headpose_shm.h',
                'app/src/main/cpp/venc/venc_proto.h',
                'app/src/main/cpp/waylandcomp/src/spatialpose_shm.h',
                'app/src/main/cpp/adrenotools/src/hook/kgsl.h',
                'app/src/main/assets/graphics_driver/adrenotools-turnip25.1.0.tzst']


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
    return {path.relative_to(root).as_posix(): digest(path) for path in sorted(set(files))
            if path.is_file() and '__pycache__' not in path.parts and path.suffix != '.pyc'}


def outputs(root):
    result = {}
    for tree in ['linuxfs', 'directaudio', 'droiddeck-esync', 'desktop-removal']:
        base = root / 'app/src/main/assets' / tree
        for path in base.rglob('*'):
            if path.is_file() and not any(part.startswith('.') for part in path.relative_to(base).parts):
                result['assets/' + tree + '/' + path.relative_to(base).as_posix()] = path.relative_to(root).as_posix()
    for library in ['libproot.so', 'libproot-loader.so', 'libdirectaudiorelay.so']:
        result['lib/arm64-v8a/' + library] = 'app/src/main/jniLibs/arm64-v8a/' + library
    result['assets/pulseaudio.tzst'] = 'app/build/prepared-assets/pulseaudio.tzst'
    result['assets/graphics_driver/adrenotools-turnip25.1.0-quest1.tzst'] = \
        'app/build/prepared-assets/graphics_driver/adrenotools-turnip25.1.0-quest1.tzst'
    for name in ['turnip26.3.0-a6xx', 'turnip26.3.0-quest2']:
        result[f'assets/graphics_driver/adrenotools-{name}.tzst'] = \
            f'app/build/prepared-assets/graphics_driver/adrenotools-{name}.tzst'
    return result


def required():
    paths = ['assets/graphics_driver/adrenotools-turnip25.1.0-quest1.tzst']
    paths += [f'assets/graphics_driver/adrenotools-{name}.tzst'
              for name in ['turnip26.3.0-a6xx', 'turnip26.3.0-quest2']]
    paths += ['assets/linuxfs/' + name for name in ['libfakeinput.so', 'libblsession.so',
             'libblfastpath.so', 'libssbs.so', 'usr/local/bin/droiddeck-clipboard',
             'usr/local/bin/droiddeck-gog-maintenance',
             'usr/local/bin/gamescope', 'usr/local/lib/droiddeck/uruntime',
             'usr/local/lib/droiddeck-pipewire.tzst']]
    for arch in ['x86_64', 'i386']:
        paths += ['assets/linuxfs/' + arch + '/' + lib for lib in ['libfakeinput.so', 'libblsession.so']]
    paths += ['assets/linuxfs/x86_64/' + lib for lib in
              ['libfaultreport.so', 'libvulkan-thunk.so', 'libwebkitinput.so']]
    paths += ['assets/linuxfs/i386/libfaultreport.so']
    paths += ['assets/linuxfs/usr/local/lib/droiddeck-msitools/' + name for name in
              ['msiinfo', 'cabextract', '7z', '7z.so', 'libmsi-1.0.so.0', 'libgsf-1.so.114', 'libgcab-1.0.so.0']]
    paths += ['assets/linuxfs/usr/local/lib/droiddeck-vr/' + name for name in
              ['droiddeck/bin/linuxarm64/driver_droiddeck.so', 'droiddeck/bin/linuxarm64/xr-display-mode',
               'droiddeck/driver.vrdrivermanifest',
               'droiddeck/resources/settings/default.vrsettings', 'layer/libVkLayer_droiddeck_vr.so',
               'layer/VK_LAYER_DROIDDECK_vr.json', 'layer/VK_LAYER_DROIDDECK_vr_implicit.json',
               'layer/libdroiddeck_vr_scheduling.so']]
    for side in ['left', 'right']:
        paths += [f'assets/linuxfs/usr/local/lib/droiddeck-vr/droiddeck/resources/rendermodels/droiddeck_hand_{side}/{name}'
                  for name in [f'droiddeck_hand_{side}.json', 'hand.mtl', 'hand.png', 'hand_open.obj', 'hand_fist.obj',
                               'thumb_index_open.obj', 'thumb_index_pinch.obj']]
    paths += ['assets/linuxfs/usr/local/lib/mangoapp/' + name for name in
              ['mangoapp', 'libfmt.so.10', 'libspdlog.so.1.13', 'libglfw.so.3', 'libtraceevent.so.1', 'libtracefs.so.1']]
    paths += ['assets/linuxfs/usr/local/lib/droiddeck-comet/' + name for name in
              ['comet', 'GalaxyCommunication.exe', 'update-permissions.exe', 'LICENSE', 'version']]
    paths += ['assets/linuxfs/usr/local/lib/droiddeck-xbox/' + name for name in
              ['xodus-cli', 'xodus-service', 'version', 'backend.lock.json', 'runtime.lock.json', 'LICENSE.xodus', 'LICENSE.nexus', 'LICENSE.LGPL-2.1', 'LICENSE.nghttp2',
               'windows/xgameruntime.dll', 'windows/winhttp.dll', 'windows/windows.web.dll', 'windows/twinapi.appcore.dll', 'windows/libnghttp2.dll']]
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


def pack_digests(root, name):
    path = root / PACKS / (name + '.tar.zst')
    if not path.is_file():
        raise ValueError('Missing add-on pack ' + name + '. Run tools/packs/build.sh app/build/packs.')
    process = subprocess.Popen(['zstd', '-dc', str(path)], stdout=subprocess.PIPE)
    digests = {}
    try:
        with tarfile.open(fileobj=process.stdout, mode='r|') as archive:
            for member in archive:
                if member.isfile():
                    digests[member.name] = hashlib.sha256(archive.extractfile(member).read()).hexdigest()
    finally:
        process.stdout.close()
    if process.wait():
        raise ValueError('Invalid add-on pack ' + name)
    return digests


def check_apk(apk, root=ROOT):
    data = verify(root)
    packs = {name: pack_digests(root, name) for name in PACK_NAMES}
    with ZipFile(apk) as package:
        names = set(package.namelist())
        for library in PACKED_LIBRARIES:
            if library in names:
                raise ValueError('APK still carries ' + library + ', which the tracking pack provides')
            if library.rsplit('/', 1)[1] not in packs['tracking']:
                raise ValueError('Tracking pack missing ' + library)
        for name, output in data['outputs'].items():
            if name in PACKED_FILES:
                pack, rel = PACKED_FILES[name]
                if name in names:
                    raise ValueError('APK still carries ' + name + ', which the ' + pack + ' pack provides')
                if packs[pack].get(rel) != output['sha256']:
                    raise ValueError('Add-on pack ' + pack + ' is missing or has a stale ' + name)
                continue
            prefix = next((p for p in PACKED if name.startswith(p)), None)
            if prefix:
                if name in names:
                    raise ValueError('APK still carries ' + name + ', which the ' + PACKED[prefix] + ' pack provides')
                if packs[PACKED[prefix]].get(name[len(prefix):]) != output['sha256']:
                    raise ValueError('Add-on pack ' + PACKED[prefix] + ' is missing or has a stale ' + name)
                continue
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
