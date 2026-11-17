#!/usr/bin/env python3
import hashlib
import json
from pathlib import Path
import shutil
import sys
import tarfile
import urllib.request

recipe = Path(__file__).resolve().parent
lock = json.loads((recipe / 'runtime.lock.json').read_text())
cache = Path(sys.argv[1]); output = Path(sys.argv[2])
cache.mkdir(parents=True, exist_ok=True)
output.mkdir(parents=True, exist_ok=True)
url = 'https://github.com/' + lock['repository'] + '/releases/download/' + lock['tag'] + '/'

def download(name, expected, algorithm):
    target = cache / name
    def matches():
        if not target.is_file():
            return False
        digest = hashlib.new(algorithm)
        with target.open('rb') as file:
            for block in iter(lambda: file.read(1024 * 1024), b''):
                digest.update(block)
        return digest.hexdigest() == expected
    if not matches():
        part = target.with_suffix(target.suffix + '.part')
        with urllib.request.urlopen(url + name, timeout=90) as response, part.open('wb') as file:
            shutil.copyfileobj(response, file)
        part.replace(target)
    if not matches():
        raise RuntimeError('Xbox runtime checksum mismatch: ' + name)
    return target

archive = download(lock['archive'], lock['sha512'], 'sha512')
source = lock['source']
download(source['name'], source['sha256'], 'sha256')
remaining = set(lock['dlls'])
# Prebuilt runtime-compat files from the same archive (MIT), placed next to windows/ in the pack: the
# PlayFab Game Saves save-folder proxy that droiddeck-xbox stages in front of a title's SDK, and its license.
compat = dict(lock.get('compat', {}))
with tarfile.open(archive) as tar:
    for member in tar:
        relative = member.name.partition('/')[2]
        if relative in compat:
            target, expected = compat.pop(relative)
            if not member.isfile() or member.size > 32 * 1024 * 1024:
                raise RuntimeError('Invalid Xbox runtime compatibility file')
            data = tar.extractfile(member).read()
            if hashlib.sha256(data).hexdigest() != expected:
                raise RuntimeError('Xbox runtime compatibility file checksum mismatch')
            (output.parent / target).parent.mkdir(parents=True, exist_ok=True)
            (output.parent / target).write_bytes(data)
            continue
        name = Path(member.name).name
        if '/files/lib/wine/x86_64-windows/' not in member.name or name not in remaining:
            continue
        if not member.isfile() or member.size > 32 * 1024 * 1024:
            raise RuntimeError('Invalid Xbox Windows runtime module')
        data = tar.extractfile(member).read()
        if hashlib.sha256(data).hexdigest() != lock['dlls'][name] or data[:2] != b'MZ':
            raise RuntimeError('Xbox Windows runtime module checksum mismatch')
        if data[64:80] == b'Wine builtin DLL':
            data = data[:64] + b'DroidDeck native' + data[80:]
        (output / name).write_bytes(data)
        remaining.remove(name)
if remaining:
    raise RuntimeError('Missing Xbox runtime modules')
if compat:
    raise RuntimeError('Missing Xbox runtime compatibility files')
