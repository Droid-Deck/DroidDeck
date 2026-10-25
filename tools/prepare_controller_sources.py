import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]


def contained(directory, relative):
    path = directory / relative
    if path.is_symlink() or not path.resolve().is_relative_to(directory.resolve()):
        raise ValueError('Controller source path escapes its directory: ' + relative)
    return path


def prepare(root=ROOT, output=None):
    root = root.resolve()
    build = root / 'app/build'
    output = output or build / 'controller-sources'
    if output.is_symlink() or output.resolve() == build.resolve() or not output.resolve().is_relative_to(build.resolve()):
        raise ValueError('Controller sources must be generated below app/build')
    output = output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='controller-sources-', dir=output.parent) as temporary:
        stage = Path(temporary)
        for name in ('sdlbridge', 'sdl3'):
            vendor = root / 'third_party' / name
            manifest = json.loads((vendor / 'upstream.json').read_text(encoding='utf-8'))
            if manifest.get('version') != 1:
                raise ValueError('Unsupported controller source manifest: ' + name)
            source = vendor / 'upstream'
            files = manifest['files']
            actual = {p.relative_to(source).as_posix() for p in source.rglob('*') if p.is_file()}
            if set(files) != actual:
                raise ValueError('Vendored controller source inventory changed: ' + name)
            for relative, metadata in files.items():
                original = contained(source, relative)
                if hashlib.sha256(original.read_bytes()).hexdigest() != metadata['sha256']:
                    raise ValueError('Vendored controller source hash changed: ' + name + '/' + relative)
                destination = contained(stage / name, relative)
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(original, destination)
            shutil.copyfile(vendor / 'upstream.json', stage / name / 'upstream.json')
            for relative in manifest['patches']:
                patch = contained(vendor, relative)
                for line in patch.read_text(encoding='utf-8').splitlines():
                    if line.startswith(('--- ', '+++ ')):
                        filename = line[4:].split('\t', 1)[0]
                        if filename[:2] not in ('a/', 'b/') or filename[2:] not in files:
                            raise ValueError('Patch modifies an unlisted controller source: ' + filename)
                result = subprocess.run(['git', '-c', 'core.autocrlf=false', '-c', 'core.eol=lf',
                                         'apply', '--no-index', '--whitespace=nowarn',
                                         '--directory=' + (stage / name).relative_to(root).as_posix(), str(patch)],
                                        cwd=root, capture_output=True, text=True)
                if result.returncode:
                    raise ValueError(name + '/' + relative + ': ' + result.stderr.strip())
        if output.exists():
            shutil.rmtree(output)
        shutil.copytree(stage, output)
    print('Verified and prepared pinned SDL3 and bridge sources.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Verify vendored controller snapshots and apply local patches.')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    prepare(output=args.output)
