"""One entry point for runtime recipes, cache identities and verified job handoff."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[2]
REGISTRY = 'tools/components/recipes.json'
TREES = ['app/src/main/assets/linuxfs', 'app/src/main/assets/directaudio',
         'app/src/main/assets/droiddeck-esync', 'app/build/prepared-assets',
         'app/build/component-records']
LIBRARIES = ['app/src/main/jniLibs/arm64-v8a/' + name for name in
             ('libproot.so', 'libproot-loader.so', 'libdirectaudiorelay.so')]
MANIFEST = 'app/build/prepared-components.json'


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def registry(root=ROOT):
    return json.loads((root / REGISTRY).read_text())


def identity(name, root=ROOT):
    spec = registry(root)[name]
    paths = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard',
                                     '--', *spec['inputs'], *spec.get('source_inputs', [])], cwd=root, text=True).splitlines()
    values = {}
    for name_ in sorted(set(paths)):
        if '__pycache__' in name_ or name_.endswith(('.pyc', '.md')):
            continue
        content = (root / name_).read_bytes()
        if b'\0' not in content:
            content = content.replace(b'\r\n', b'\n')
        values[name_] = hashlib.sha256(content).hexdigest()
    policy = dict(spec=spec, sources=values, runner=os.environ.get('RUNNER_OS', 'Linux'),
                  architecture=os.environ.get('RUNNER_ARCH', 'X64'))
    entrypoint = root / 'tools/components/prepare.py'
    if entrypoint.is_file():
        policy['entrypoint'] = hashlib.sha256(entrypoint.read_bytes().replace(b'\r\n', b'\n')).hexdigest()
    installer = root / 'tools/ci/install-ubuntu-packages.sh'
    if spec.get('packages') and installer.is_file():
        policy['installer'] = hashlib.sha256(installer.read_bytes().replace(b'\r\n', b'\n')).hexdigest()
    if spec.get('ndk'):
        policy['ndk'] = os.environ.get('NDK_VERSION', '')
        policy['ndk_policy'] = hashlib.sha256((root / 'tools/ci/toolchains.env').read_bytes().replace(b'\r\n', b'\n')).hexdigest()
    return hashlib.sha256(json.dumps(policy, sort_keys=True).encode()).hexdigest()


def prepare(name, root=ROOT):
    spec = registry(root)[name]
    prepare_tools([name], root)
    if 'command' in spec:
        command = spec['command']
    else:
        script = (root / spec['script']).resolve()
        if not script.is_relative_to((root / 'tools/components').resolve()):
            raise ValueError('Recipe script escapes components directory')
        command = ['bash', str(script)]
    environment = dict(os.environ)
    if spec.get('source'):
        environment.update(COMPONENT_SOURCE_URL=spec['source']['url'],
                           COMPONENT_SOURCE_SHA256=spec['source']['sha256'])
    subprocess.run(command, cwd=root, env=environment, check=True)
    missing = [name_ for name_ in spec['outputs'] if not (root / name_).is_file()]
    if missing:
        raise ValueError(f'{name} did not prepare required outputs: {missing}')
    record = dict(component=name, input_sha256=identity(name, root),
                  outputs={p: digest(root / p) for p in spec['outputs']})
    destination = root / 'app/build/component-records' / (name + '.json')
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(record, sort_keys=True, indent=2) + '\n')


def prepare_tools(names, root=ROOT):
    packages = sorted({package for name in names for package in registry(root)[name].get('packages', [])})
    if packages:
        subprocess.run(['bash', 'tools/ci/install-ubuntu-packages.sh', *packages], cwd=root, check=True)


def files(root=ROOT):
    result = []
    for name in TREES:
        folder = root / name
        if not folder.is_dir() or folder.is_symlink():
            raise ValueError('Missing prepared component tree: ' + name)
        for path in folder.rglob('*'):
            if path.is_symlink():
                raise ValueError('Symlink in prepared components: ' + str(path))
            if path.is_file():
                result.append(path.relative_to(root).as_posix())
    for name in LIBRARIES:
        if not (root / name).is_file() or (root / name).is_symlink():
            raise ValueError('Missing prepared component library: ' + name)
        result.append(name)
    return sorted(result)


def pack(destination, root=ROOT):
    names = files(root)
    tree = subprocess.check_output(['git', 'rev-parse', 'HEAD^{tree}'], cwd=root, text=True).strip()
    data = dict(version=1, source_tree=tree, run=os.environ.get('GITHUB_RUN_ID', ''),
                recipes={name: identity(name, root) for name in registry(root)},
                files={name: digest(root / name) for name in names})
    (root / MANIFEST).write_text(json.dumps(data, sort_keys=True, indent=2) + '\n')
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(destination, mode='w:gz', compresslevel=1) as archive:
        for name in names + [MANIFEST]:
            archive.add(root / name, arcname=name, recursive=False)


def unpack(archive_path, source_tree, root=ROOT):
    total = 0
    seen = set()
    with tarfile.open(archive_path, mode='r:gz') as archive:
        for entry in archive:
            allowed = entry.name in LIBRARIES + [MANIFEST] or any(entry.name.startswith(p + '/') for p in TREES)
            path = root / entry.name
            total += entry.size
            if (not allowed or '\\' in entry.name or '..' in PurePosixPath(entry.name).parts
                    or PurePosixPath(entry.name).as_posix() != entry.name
                    or not entry.isfile() or entry.name in seen or len(seen) >= 100000
                    or entry.size > 512 * 1024 * 1024
                    or total > 2 * 1024**3 or not path.resolve().is_relative_to(root.resolve())
                    or path.is_symlink()):
                raise ValueError('Unsafe prepared component archive')
            seen.add(entry.name)
            path.parent.mkdir(parents=True, exist_ok=True)
            with path.open('wb') as output:
                shutil.copyfileobj(archive.extractfile(entry), output)
            path.chmod(entry.mode & 0o777)
    data = json.loads((root / MANIFEST).read_text())
    actual = {name: digest(root / name) for name in files(root)}
    recipes = {name: identity(name, root) for name in registry(root)}
    if (data.get('version') != 1 or data['source_tree'] != source_tree or data['files'] != actual
            or data['recipes'] != recipes):
        raise ValueError('Prepared components do not match the pinned source tree and file hashes')
    print('Prepared component source identity and all output hashes verified.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('recipe')
    parser.add_argument('--archive', type=Path, default=ROOT / 'app/build/prepared-components.tar.gz')
    parser.add_argument('--tree')
    args = parser.parse_args()
    if args.recipe == 'plan':
        for name in registry():
            value = identity(name)
            print(f'{name}: {value}')
            if os.environ.get('GITHUB_OUTPUT'):
                with open(os.environ['GITHUB_OUTPUT'], 'a') as stream:
                    stream.write(f'{name}={value}\n')
    elif args.recipe == 'tools':
        prepare_tools(os.environ.get('COMPONENTS_TO_BUILD', '').split())
    elif args.recipe == 'pack':
        pack(args.archive)
    elif args.recipe == 'unpack':
        unpack(args.archive, args.tree)
    else:
        prepare(args.recipe)
