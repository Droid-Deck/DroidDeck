#!/usr/bin/env python3
"""Prepare the pinned pinyin engine and its runtime dependencies."""

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request
import xml.etree.ElementTree as ElementTree

PACKAGES = Path(__file__).with_name('packages.json')
LIBRARY_DIR = 'usr/local/lib/droiddeck-ime'
ENGINE_NAME = 'ibus-engine-pinyin'
ENGINE_MEMBERS = ('usr/libexec/' + ENGINE_NAME, 'usr/bin/' + ENGINE_NAME)
COMPONENT = 'usr/share/ibus/component/pinyin.xml'
PROFILE = 'usr/local/share/droiddeck/ibus.profile'
MANIFEST = 'usr/local/share/droiddeck/ime-files.json'
LICENSES = 'usr/local/share/licenses/droiddeck-ime'
PROFILE_TEXT = 'user-db:user\nsystem-db:ibus\n'
# The engine links these by soname, so the dereferenced copies keep the RPM member names.
LIBRARIES = {'pyzy': ('usr/lib64/libpyzy-1.0.so.0',),
             'lua-libs': ('usr/lib64/liblua-5.4.so', 'usr/lib64/liblua-5.4.so.0')}
DATA = {'ibus-pinyin': ('usr/share/ibus-pinyin/base.lua', 'usr/share/ibus-pinyin/phrases.txt',
                        'usr/share/ibus-pinyin/db/english.db'),
        'pyzy': ('usr/share/pyzy/phrases.txt',),
        'pyzy-db-android': ('usr/share/pyzy/db/android.db',)}
NOTICES = ('COPYING', 'LICENSE', 'COPYRIGHT', 'README', 'NOTICE')


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def specifications():
    return json.loads(PACKAGES.read_text())['packages']


def fetch(directory, spec):
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / spec['filename']
    pending = path.with_name(path.name + '.pending')
    with urllib.request.urlopen(spec['url'], timeout=60) as response, pending.open('wb') as stream:
        shutil.copyfileobj(response, stream)
    if digest(pending) != spec['sha256']:
        pending.unlink()
        raise ValueError('SHA256 mismatch for downloaded ' + spec['filename'])
    pending.replace(path)
    return path


def verified(directory, download=False):
    sources = {}
    for name, spec in specifications().items():
        path = directory / spec['filename']
        if download and not (path.is_file() and digest(path) == spec['sha256']):
            path = fetch(directory, spec)
        if not path.is_file():
            raise ValueError('missing RPM ' + spec['filename'])
        if digest(path) != spec['sha256']:
            raise ValueError('SHA256 mismatch for ' + spec['filename'])
        sources[name] = path
    return sources


def extract(name, rpm, destination):
    for tool in ('rpm2cpio', 'cpio'):
        if shutil.which(tool) is None:
            raise ValueError(tool + ' is required to unpack the RPM payload')
    destination.mkdir(parents=True)
    process = subprocess.Popen(['rpm2cpio', str(rpm)], stdout=subprocess.PIPE)
    try:
        subprocess.run(['cpio', '-idm', '--quiet', '--no-absolute-filenames'],
                       stdin=process.stdout, cwd=destination, check=True)
    finally:
        process.stdout.close()
    if process.wait():
        raise ValueError('rpm2cpio failed for ' + name)


def member(tree, name):
    path = (tree / name).resolve()
    if not path.is_relative_to(tree.resolve()) or not path.is_file():
        raise ValueError(f'{tree.name}: RPM payload is missing {name}')
    return path


def install(source, target, output, files, executable=False):
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)
    target.chmod(0o755 if executable else 0o644)
    files.add(target.relative_to(output).as_posix())


def engine(tree):
    for candidate in ENGINE_MEMBERS:
        if (tree / candidate).is_file():
            return candidate
    raise ValueError('ibus-pinyin RPM has no ' + ENGINE_NAME + ' executable')


def component(tree):
    root = ElementTree.parse(member(tree, COMPONENT)).getroot()
    for element in list(root.iter()):
        for child in list(element):
            if child.tag == 'setup':
                element.remove(child)
    executable = root.find('exec')
    if executable is None:
        raise ValueError('pinyin.xml has no engine exec element')
    executable.text = f'/{LIBRARY_DIR}/{ENGINE_NAME} --ibus'
    engines = {node.findtext('name', '').strip() for node in root.iter('engine')}
    if not {'pinyin', 'bopomofo'} <= engines:
        raise ValueError('pinyin.xml no longer defines the pinyin and bopomofo engines')
    return root


def icons(root, tree, output, files):
    names = set()
    for node in root.iter('icon'):
        text = (node.text or '').strip()
        if text:
            names.add(Path(text).stem if '/' in text else text)
    found = sorted(path for path in (tree / 'usr/share').rglob('*')
                   if path.is_file() and path.suffix in ('.svg', '.png') and path.stem in names)
    if names and not found:
        raise ValueError('pinyin.xml icons are not in the ibus-pinyin RPM: ' + ', '.join(sorted(names)))
    for path in found:
        install(path, output / path.relative_to(tree), output, files)


def notices(name, tree, output, files):
    copied = []
    for folder in ('usr/share/licenses/' + name, 'usr/share/doc/' + name):
        base = tree / folder
        if not base.is_dir():
            continue
        for path in sorted(base.rglob('*')):
            if path.is_file() and path.name.upper().startswith(NOTICES):
                target = output / LICENSES / name / path.relative_to(base)
                install(path, target, output, files)
                copied.append(target.relative_to(output / LICENSES).as_posix())
    return copied


def prepare(directory, output, work=None, download=False):
    specs = specifications()
    sources = verified(directory, download)
    output = output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    created = work is None
    work = Path(tempfile.mkdtemp(prefix='.droiddeck-ime-', dir=output.parent)) if created else Path(work)
    work.mkdir(parents=True, exist_ok=True)
    try:
        trees = {}
        for name, source in sources.items():
            trees[name] = work / name
            shutil.rmtree(trees[name], ignore_errors=True)
            extract(name, source, trees[name])
        files = set()
        pinyin = trees['ibus-pinyin']
        install(member(pinyin, engine(pinyin)), output / LIBRARY_DIR / ENGINE_NAME, output, files, executable=True)
        for name, candidates in LIBRARIES.items():
            installed = [candidate for candidate in candidates if (trees[name] / candidate).exists()]
            if not installed:
                raise ValueError(f'{name}: RPM has none of {list(candidates)}')
            for candidate in installed:
                install(member(trees[name], candidate), output / LIBRARY_DIR / Path(candidate).name, output, files)
        for name, members in DATA.items():
            for relative in members:
                install(member(trees[name], relative), output / relative, output, files)
        root = component(pinyin)
        icons(root, pinyin, output, files)
        target = output / COMPONENT
        target.parent.mkdir(parents=True, exist_ok=True)
        ElementTree.ElementTree(root).write(target, encoding='utf-8', xml_declaration=True)
        files.add(COMPONENT)
        profile = output / PROFILE
        profile.parent.mkdir(parents=True, exist_ok=True)
        profile.write_text(PROFILE_TEXT, newline='\n')
        files.add(PROFILE)
        packages = {}
        for name, spec in specs.items():
            license_files = notices(name, trees[name], output, files)
            packages[name] = {'filename': spec['filename'], 'url': spec['url'], 'sha256': spec['sha256'],
                              'license_files': license_files}
            if not license_files:
                packages[name]['license_note'] = ('RPM bundles no separate license file; '
                                                  'the payload is unmodified Fedora package content')
        manifest = {'version': 1, 'engine': 'pinyin', 'packages': packages,
                    'files': {path: {'sha256': digest(output / path),
                                     'mode': f'{(output / path).stat().st_mode & 0o777:04o}'}
                              for path in sorted(files)}}
        destination = output / MANIFEST
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(json.dumps(manifest, sort_keys=True, indent=2) + '\n', newline='\n')
        return destination, len(files)
    finally:
        if created:
            shutil.rmtree(work, ignore_errors=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--packages', type=Path, required=True, help='Directory with the exact pinned RPMs')
    parser.add_argument('--output', type=Path, required=True,
                        help='Prepared linuxfs root, for example app/build/prepared-assets/linuxfs')
    parser.add_argument('--work', type=Path, help='Kept extraction cache instead of a temporary directory')
    parser.add_argument('--download', action='store_true',
                        help='Fetch missing or mismatched RPMs into --packages and verify their SHA256')
    args = parser.parse_args()
    try:
        destination, count = prepare(args.packages, args.output, args.work, args.download)
    except (OSError, ValueError, subprocess.CalledProcessError, ElementTree.ParseError) as error:
        parser.exit(1, f'error: {error}\n')
    print(f'Wrote {count} IME payload files; manifest {destination}')


if __name__ == '__main__':
    main()
