"""Build DroidDeck's ARM64 VR components in Docker and attest their inputs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
IMAGE = 'droiddeck-local-cross:24.04-v3'
MANIFEST = 'vr-build.json'
SOURCE_TREES = ('tools/vr/src', 'tools/vr/tests', 'tools/vr/third_party',
                'tools/vr/driver_droiddeck', 'tools/vr/layer')
SOURCE_FILES = ('tools/vr/build.sh', 'tools/vr/build_windows.py', 'tools/vr/make_hand_models.cpp', 'tools/local-cross.Dockerfile',
                'app/src/main/cpp/waylandcomp/src/headpose_shm.h',
                'app/src/main/cpp/waylandcomp/src/spatialpose_shm.h',
                'app/src/main/cpp/waylandcomp/generated/xdg-shell-protocol.c',
                'app/src/main/cpp/waylandcomp/generated/xdg-shell-client-protocol.h')
LIBRARIES = {'droiddeck/bin/linuxarm64/driver_droiddeck.so', 'layer/libVkLayer_droiddeck_vr.so',
             'layer/libdroiddeck_vr_scheduling.so'}
CONFIGS = {
    'droiddeck/driver.vrdrivermanifest': 'tools/vr/driver_droiddeck/driver.vrdrivermanifest',
    'droiddeck/resources/settings/default.vrsettings': 'tools/vr/driver_droiddeck/resources/settings/default.vrsettings',
    'layer/VK_LAYER_DROIDDECK_vr.json': 'tools/vr/layer/VK_LAYER_DROIDDECK_vr.json',
    'layer/VK_LAYER_DROIDDECK_vr_implicit.json': 'tools/vr/layer/VK_LAYER_DROIDDECK_vr_implicit.json',
}
EXECUTABLES = {'droiddeck/bin/linuxarm64/xr-display-mode'}
HAND_MODELS = {
    f'droiddeck/resources/rendermodels/droiddeck_hand_{side}/{name}'
    for side in ('left', 'right')
    for name in (f'droiddeck_hand_{side}.json', 'hand.mtl', 'hand.png', 'hand_open.obj',
                 'hand_fist.obj', 'thumb_index_open.obj', 'thumb_index_pinch.obj')
}
OUTPUTS = LIBRARIES | CONFIGS.keys() | EXECUTABLES | HAND_MODELS


def is_source(path):
    return path in SOURCE_FILES or any(path.startswith(tree + '/') for tree in SOURCE_TREES)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def commit(root):
    return subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD']).decode().strip()


def sources(root):
    paths = {root / name for name in SOURCE_FILES}
    for tree in SOURCE_TREES:
        paths.update(path for path in (root / tree).rglob('*') if path.is_file())
    result = {}
    for path in sorted(paths):
        if path.is_symlink() or not path.resolve().is_relative_to(root.resolve()):
            raise ValueError('VR source escapes the checkout: ' + str(path))
        result[path.relative_to(root).as_posix()] = path.read_bytes().replace(b'\r\n', b'\n')
    return result


def headers(include):
    for name in ('vulkan/vulkan_core.h', 'vulkan/vk_layer.h'):
        if not (include / name).is_file():
            raise ValueError('Missing Vulkan header: ' + str(include / name))
    return {path.relative_to(include).as_posix(): path.read_bytes().replace(b'\r\n', b'\n')
            for tree in ('vulkan', 'vk_video') for path in sorted((include / tree).rglob('*'))
            if path.is_file()}


def hashes(files):
    return {name: sha(data) for name, data in files.items()}


def read_outputs(folder, root):
    found = {p.relative_to(folder).as_posix() for p in folder.rglob('*') if p.is_file()}
    if found - {MANIFEST} != OUTPUTS:
        raise ValueError('VR build has missing or unexpected output files')
    files = {}
    for name in sorted(OUTPUTS):
        path = folder / name
        if path.is_symlink() or not path.resolve().is_relative_to(folder.resolve()):
            raise ValueError('VR output escapes its build directory: ' + name)
        data = path.read_bytes()
        if name in LIBRARIES or name in EXECUTABLES:
            if (len(data) < 64 or data[:6] != b'\x7fELF\x02\x01' or
                    struct.unpack_from('<H', data, 16)[0] not in ((3,) if name in LIBRARIES else (2, 3)) or
                    struct.unpack_from('<H', data, 18)[0] != 183):
                raise ValueError('VR output is not an ARM64 ELF shared library or executable: ' + name)
        elif name in CONFIGS and json.loads(data) != json.loads((root / CONFIGS[name]).read_bytes()):
            raise ValueError('VR configuration differs from current source: ' + name)
        elif not data:
            raise ValueError('Empty VR output: ' + name)
        files[name] = data
    return files


def validate(folder, include, root=ROOT):
    try:
        manifest = json.loads((folder / MANIFEST).read_text(encoding='utf-8'))
    except (OSError, ValueError) as error:
        raise ValueError('Missing or invalid VR build manifest; rebuild with tools/vr/build_windows.py') from error
    if (manifest.get('version') != 1 or manifest.get('source_commit') != commit(root) or
            manifest.get('sources') != hashes(sources(root)) or
            manifest.get('headers') != hashes(headers(include))):
        raise ValueError('VR build is stale; rebuild with tools/vr/build_windows.py')
    files = read_outputs(folder, root)
    if manifest.get('outputs') != hashes(files):
        raise ValueError('VR build output checksum mismatch; rebuild with tools/vr/build_windows.py')
    return files, manifest


def build(folder, include, root=ROOT, image=IMAGE):
    folder = folder.resolve()
    workspace = (root / 'build/windows-setup').resolve()
    if not workspace.is_relative_to(root.resolve()) or not folder.is_relative_to((root / 'build').resolve()):
        raise ValueError('VR build output must be inside the checkout build directory')
    input_files, input_headers, revision = sources(root), headers(include), commit(root)
    docker = shutil.which('docker')
    if not docker:
        candidate = Path(os.environ.get('ProgramFiles', 'C:/Program Files')) / 'Docker/Docker/resources/bin/docker.exe'
        if candidate.is_file():
            docker = str(candidate)
    if not docker:
        raise ValueError('Docker Desktop is required to rebuild VR; start its Linux engine and retry')
    subprocess.run([docker, 'info', '--format', '{{.OSType}}'], check=True)
    workspace.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='vr-build-', dir=workspace) as temp:
        snapshot = Path(temp).resolve()
        # build.sh's recursive output cleanup and TemporaryDirectory cleanup are
        # confined to this new, verified directory, never the real checkout.
        if not snapshot.is_relative_to(workspace):
            raise ValueError('Temporary VR workspace escapes the checkout')
        inspect = subprocess.run([docker, 'image', 'inspect', '--format', '{{.Id}}', image],
                                 capture_output=True, text=True)
        if inspect.returncode:
            if image != IMAGE:
                raise ValueError('VR compiler image is not available locally: ' + image)
            subprocess.run([docker, 'build', '--tag', image, '--file', '-', str(snapshot)],
                           input=input_files['tools/local-cross.Dockerfile'], check=True)
        image_id = subprocess.check_output([docker, 'image', 'inspect', '--format', '{{.Id}}', image]).decode().strip()
        for name, data in input_files.items():
            target = snapshot / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        for name, data in input_headers.items():
            target = snapshot / 'vk-include' / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        subprocess.run([docker, 'run', '--rm', '--platform', 'linux/amd64',
                        '--mount', f'type=bind,source={snapshot},target=/src', '-w', '/src',
                        '-e', 'VK_INCLUDE=/src/vk-include', image_id,
                        'bash', 'tools/vr/build.sh', '--native', '/src/out'], check=True)
        if sources(root) != input_files or headers(include) != input_headers or commit(root) != revision:
            raise ValueError('VR inputs changed during compilation; retry the build')
        files = read_outputs(snapshot / 'out', root)
        manifest = {'version': 1, 'source_commit': revision, 'sources': hashes(input_files),
                    'headers': hashes(input_headers), 'compiler_image': image_id,
                    'outputs': hashes(files), 'native_tests': 'passed in tools/vr/build.sh'}
        # Publish only after the complete build and native regression tests pass.
        for name, data in files.items():
            target = folder / name
            if not target.resolve().is_relative_to(folder):
                raise ValueError('VR output escapes the build directory: ' + name)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        (folder / MANIFEST).write_text(json.dumps(manifest, indent=2, sort_keys=True) + '\n', encoding='utf-8')
    return validate(folder, include, root)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--vk-include', type=Path, required=True, help='NDK sysroot/usr/include directory')
    parser.add_argument('--out', type=Path, default=ROOT / 'build/windows-setup/vr')
    parser.add_argument('--image', default=IMAGE, help='Existing Docker compiler image; default is built if missing')
    args = parser.parse_args()
    try:
        build(args.out, args.vk_include.resolve(), image=args.image)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, str(error) + '\n')
    print('VR build and native tests verified: ' + str(args.out))


if __name__ == '__main__':
    main()
