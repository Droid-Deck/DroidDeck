"""Install the components a main CI run prepared when they match this tree.

The run's prepared-components archive is used only when every recipe identity it records equals
the identity of the local tree, the inputs CI builds outside the recipes are unchanged from the
run's source tree, and no recipe submodule has local edits. Files are verified in a staging
directory before any local output is replaced.
"""
import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import sys
import tarfile
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
import prepare  # noqa: E402

# CI records identities under its runner labels and pinned NDK, not this host's.
RUNNER_ARCHITECTURES = ('X64', 'ARM64')
# The components job builds these outside the recipe registry.
UNREGISTERED_INPUTS = ['.github/workflows/build.yml', 'tools/linuxfs/xclipse-layer', 'tools/venus/glprobe.c',
                       'tools/quest']


def ci_environment(architecture, root):
    ndk = next(line.split('=', 1)[1].strip()
               for line in (root / 'tools/ci/toolchains.env').read_text().splitlines()
               if line.startswith('CI_ANDROID_NDK_VERSION='))
    return dict(RUNNER_OS='Linux', RUNNER_ARCH=architecture, NDK_VERSION=ndk)


@contextmanager
def environ(values):
    saved = {key: os.environ.get(key) for key in values}
    os.environ.update(values)
    try:
        yield
    finally:
        for key, value in saved.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value


def identities(environment, root):
    with environ(environment):
        return {name: prepare.identity(name, root) for name in prepare.registry(root)}


def git(root, *args):
    return subprocess.run(['git', *args], cwd=root, capture_output=True, text=True)


def unregistered_changes(source_tree, root):
    if git(root, 'cat-file', '-e', source_tree + '^{tree}').returncode:
        return ['source tree ' + source_tree + ' (fetch origin main)']
    changed = git(root, 'diff', '--name-only', source_tree, '--', *UNREGISTERED_INPUTS).stdout.split()
    changed += git(root, 'ls-files', '--others', '--exclude-standard', '--', *UNREGISTERED_INPUTS).stdout.split()
    return sorted(set(changed))


def edited_submodules(root):
    inputs = [path for spec in prepare.registry(root).values()
              for path in spec.get('inputs', []) + spec.get('source_inputs', [])]
    staged = git(root, 'ls-files', '--stage', '--', *inputs).stdout.splitlines()
    links = [line.split('\t', 1)[1] for line in staged if line.startswith('160000 ')]
    return [link for link in links
            if git(root, 'diff', '--quiet', '--ignore-submodules=none', '--', link).returncode]


def extract(archive_path, stage):
    allowed = set(prepare.LIBRARIES + [prepare.MANIFEST])
    total = 0
    with tarfile.open(archive_path, mode='r:gz') as archive:
        for entry in archive:
            name = PurePosixPath(entry.name)
            total += entry.size
            if (not entry.isfile() or name.is_absolute() or '..' in name.parts or '\\' in entry.name
                    or name.as_posix() != entry.name or total > 2 * 1024**3
                    or not (entry.name in allowed or any(entry.name.startswith(t + '/') for t in prepare.TREES))):
                raise ValueError('Unsafe prepared component archive: ' + entry.name)
            path = stage / entry.name
            path.parent.mkdir(parents=True, exist_ok=True)
            with path.open('xb') as output:
                shutil.copyfileobj(archive.extractfile(entry), output)
            path.chmod(entry.mode & 0o777)


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def install(stage, root):
    for name in prepare.TREES:
        shutil.rmtree(root / name, ignore_errors=True)
        (root / name).parent.mkdir(parents=True, exist_ok=True)
        os.replace(stage / name, root / name)
    for name in prepare.LIBRARIES + [prepare.MANIFEST]:
        (root / name).parent.mkdir(parents=True, exist_ok=True)
        os.replace(stage / name, root / name)


def reuse(archive_path, root=prepare.ROOT):
    with tarfile.open(archive_path, mode='r:gz') as archive:
        data = json.load(archive.extractfile(prepare.MANIFEST))
    if data.get('version') != 1:
        raise ValueError('Unknown prepared component archive version')
    recorded = data.get('recipes') or {}
    environment = next((ci_environment(a, root) for a in RUNNER_ARCHITECTURES
                        if identities(ci_environment(a, root), root) == recorded), None)
    if environment is None:
        local = identities(ci_environment(RUNNER_ARCHITECTURES[0], root), root)
        changed = sorted(name for name in local.keys() | recorded.keys() if local.get(name) != recorded.get(name))
        print('CI run ' + str(data.get('run')) + ' prepared different components: ' + ', '.join(changed))
        return False
    differences = unregistered_changes(data['source_tree'], root) + edited_submodules(root)
    if differences:
        print('CI run ' + str(data.get('run')) + ' built from other sources: ' + ', '.join(differences))
        return False
    (root / 'app/build').mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=root / 'app/build', prefix='.reuse-') as stage:
        stage = Path(stage)
        extract(archive_path, stage)
        files = sorted(path.relative_to(stage).as_posix() for path in stage.rglob('*')
                       if path.is_file() and path.relative_to(stage).as_posix() != prepare.MANIFEST)
        if data.get('files') != {name: digest(stage / name) for name in files}:
            raise ValueError('Prepared components do not match their recorded file hashes')
        missing = [name for name in prepare.TREES if not (stage / name).is_dir()]
        missing += [name for name in prepare.LIBRARIES + [prepare.MANIFEST] if not (stage / name).is_file()]
        if missing:
            raise ValueError('Prepared component archive is missing ' + ', '.join(missing))
        install(stage, root)
    return True


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('archive', type=Path)
    args = parser.parse_args()
    try:
        sys.exit(0 if reuse(args.archive) else 3)
    except (ValueError, OSError, KeyError, tarfile.TarError) as error:
        sys.exit('Cannot reuse CI components: ' + str(error))
