"""Build or verify the independent KWin runtime on Windows using Docker."""
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[2]
IMAGE = 'ubuntu:26.04'
SOURCE_FILES = tuple('tools/ubuntu/' + name for name in (
    'build-kwin-runtime.sh', 'kwin-independent.py', 'kwin-independent.cpp',
    'kwin-passthrough.cpp', 'kwin-output-layout.h', 'kwin-ahb-output.h',
    'kwin-output-opacity.h', 'kwin-independent-filter.h',
)) + ('app/src/main/cpp/waylandcomp/src/ahb_broker_protocol.h',)
PATCHES = 'tools/ubuntu/patches/kwin'
OUTPUTS = {'libkwin.so.6', 'kwin_wayland', 'LICENSE', 'kwin.sha256', 'build.json'}


def is_source(path):
    return path in SOURCE_FILES or path == 'tools/ubuntu/build_kwin_windows.py' or path.startswith(PATCHES + '/')


def digest(data):
    return hashlib.sha256(data).hexdigest()


def sources(root):
    paths = {root / name for name in SOURCE_FILES}
    patches = list((root / PATCHES).glob('*.patch'))
    if not patches:
        raise ValueError('Missing KWin patches')
    paths.update(patches)
    files = {}
    for path in sorted(paths):
        if path.is_symlink() or not path.resolve().is_relative_to(root.resolve()):
            raise ValueError('KWin source escapes the checkout: ' + str(path))
        files[path.relative_to(root).as_posix()] = path.read_bytes().replace(b'\r\n', b'\n')
    return files


def validate(folder, root=ROOT):
    found = {path.relative_to(folder).as_posix() for path in folder.rglob('*') if path.is_file()}
    if found != OUTPUTS:
        raise ValueError('KWin build has missing or unexpected output files')
    files = {}
    for name in sorted(OUTPUTS):
        path = folder / name
        if path.is_symlink() or not path.resolve().is_relative_to(folder.resolve()):
            raise ValueError('KWin output escapes its build directory: ' + name)
        data = path.read_bytes()
        if not data:
            raise ValueError('Empty KWin output: ' + name)
        if name in {'libkwin.so.6', 'kwin_wayland'}:
            if (len(data) < 64 or data[:6] != b'\x7fELF\x02\x01' or
                    struct.unpack_from('<H', data, 16)[0] not in ((3,) if name == 'libkwin.so.6' else (2, 3)) or
                    struct.unpack_from('<H', data, 18)[0] != 183):
                raise ValueError('KWin output is not an ARM64 ELF library or executable: ' + name)
        files[name] = data
    manifest = json.loads(files['build.json'])
    if (manifest.get('kwin') != '6.6.6' or
            manifest.get('systemPackage') != '4:6.6.6-0ubuntu0.1+droiddeck1' or
            manifest.get('sources') != {name: digest(data) for name, data in sources(root).items()}):
        raise ValueError('KWin build is stale; rebuild from current source')
    hashes = {name: digest(data) for name, data in files.items() if name != 'build.json'}
    if manifest.get('outputs') != hashes:
        raise ValueError('KWin build output checksum mismatch')
    checksum = ''.join(f'{hashes[name]}  {name}\n'
                       for name in sorted(hashes) if name != 'kwin.sha256').encode()
    if files['kwin.sha256'] != checksum:
        raise ValueError('KWin checksum file does not match its outputs')
    return files, manifest


def build(folder, root=ROOT, image=IMAGE):
    folder.mkdir(parents=True, exist_ok=True)
    files = sources(root)
    script = files['tools/ubuntu/build-kwin-runtime.sh'].decode()
    script = script.split("<<'BUILD'\n", 1)[1].rsplit('\nBUILD', 1)[0]
    with tempfile.TemporaryDirectory(prefix='droiddeck-kwin-source-') as temp:
        snapshot = Path(temp)
        for name, data in files.items():
            path = snapshot / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        subprocess.run([
            'docker', 'run', '--rm', '-i', '--platform', 'linux/arm64',
            '--mount', f'type=bind,source={snapshot},target=/repo,readonly',
            '--mount', f'type=bind,source={folder.resolve()},target=/out',
            '-e', 'DROIDDECK_OUTPUT_OWNER=0:0',
            '-e', 'DROIDDECK_BUILD_JOBS=' + os.environ.get('DROIDDECK_BUILD_JOBS', '6'),
            image, 'bash', '-s',
        ], input=script.encode(), check=True)
    return validate(folder, root)
