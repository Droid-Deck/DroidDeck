"""Install the components a main CI run prepared when they match this tree.

Every recipe identity recorded in the run's prepared-components archive must equal the identity
of the local tree, so the archive holds exactly what this tree would prepare. File hashes are
then verified by prepare.unpack.
"""
import argparse
from contextlib import contextmanager
import json
import os
from pathlib import Path
import shutil
import sys
import tarfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
import prepare  # noqa: E402

# CI records identities under its runner labels and pinned NDK, not this host's.
RUNNER_ARCHITECTURES = ('X64', 'ARM64')


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


def reuse(archive_path, root=prepare.ROOT):
    with tarfile.open(archive_path, mode='r:gz') as archive:
        data = json.load(archive.extractfile(prepare.MANIFEST))
    recorded = data.get('recipes') or {}
    for architecture in RUNNER_ARCHITECTURES:
        environment = ci_environment(architecture, root)
        local = identities(environment, root)
        if local == recorded:
            break
    else:
        local = identities(ci_environment(RUNNER_ARCHITECTURES[0], root), root)
        changed = sorted(name for name in local.keys() | recorded.keys() if local.get(name) != recorded.get(name))
        print('CI run ' + str(data.get('run')) + ' prepared different components: ' + ', '.join(changed))
        return False
    for name in prepare.TREES:
        shutil.rmtree(root / name, ignore_errors=True)
    for name in prepare.LIBRARIES:
        (root / name).unlink(missing_ok=True)
    with environ(environment):
        prepare.unpack(archive_path, data['source_tree'], root)
    return True


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('archive', type=Path)
    args = parser.parse_args()
    try:
        sys.exit(0 if reuse(args.archive) else 3)
    except (ValueError, OSError, KeyError, tarfile.TarError) as error:
        sys.exit('Cannot reuse CI components: ' + str(error))
