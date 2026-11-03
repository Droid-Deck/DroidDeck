import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import subprocess
import tarfile


def identity(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
    return {'sha256': digest.hexdigest(), 'size': Path(path).stat().st_size}


def data_file(member, magic):
    if not member.isfile():
        return False
    extension = PurePosixPath(member.name).suffix.lower()
    signatures = {
        '.png': (b'\x89PNG\r\n\x1a\n',), '.jpg': (b'\xff\xd8\xff',),
        '.jpeg': (b'\xff\xd8\xff',), '.ttf': (b'\x00\x01\x00\x00', b'ttcf', b'true'),
        '.otf': (b'OTTO',), '.woff': (b'wOFF',), '.woff2': (b'wOF2',),
        '.ogg': (b'OggS',), '.flac': (b'fLaC',),
        '.mo': (b'\xde\x12\x04\x95', b'\x95\x04\x12\xde'),
    }
    if extension in signatures:
        return magic.startswith(signatures[extension])
    if extension == '.webp':
        return magic.startswith(b'RIFF') and magic[8:12] == b'WEBP'
    if extension == '.wav':
        return magic.startswith(b'RIFF') and magic[8:12] == b'WAVE'
    return False


def inventory(source, zstd):
    process = subprocess.Popen([zstd, '-dc', str(source)], stdout=subprocess.PIPE)
    records = []
    links = set()
    names = set()
    candidates = set()
    try:
        with tarfile.open(fileobj=process.stdout, mode='r|') as archive:
            for member in archive:
                path = PurePosixPath(member.name)
                if path.is_absolute() or '..' in path.parts or member.name in names:
                    raise ValueError('Archive contains an unsafe or repeated path: ' + member.name)
                names.add(member.name)
                digest = hashlib.sha256()
                magic = b''
                if member.isfile():
                    stream = archive.extractfile(member)
                    for block in iter(lambda: stream.read(1024 * 1024), b''):
                        if not magic:
                            magic = block[:16]
                        digest.update(block)
                    if data_file(member, magic):
                        candidates.add(member.name)
                if member.islnk():
                    links.add(member.linkname)
                records.append([member.name, member.type.decode('ascii'), member.size, member.mode,
                                member.uid, member.gid, member.uname, member.gname, member.mtime,
                                member.linkname, member.devmajor, member.devminor,
                                {key: value for key, value in member.pax_headers.items() if key not in
                                 {'path', 'linkpath', 'mtime', 'size', 'uid', 'gid', 'uname', 'gname'}},
                                digest.hexdigest() if member.isfile() else None])
        if process.wait():
            raise ValueError('Archive decompression failed')
    finally:
        process.stdout.close()
        if process.poll() is None:
            process.terminate()
        process.wait()
    candidates -= links
    records.sort(key=lambda item: item[0])
    digest = hashlib.sha256(json.dumps(records, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    return digest, candidates


def prepare(source, code, data, zstd_version='1.5.5'):
    zstd = os.environ.get('DROIDDECK_ZSTD', 'zstd')
    if 'v' + zstd_version + ',' not in subprocess.check_output([zstd, '--version'], text=True):
        raise ValueError('Split archives require the pinned zstd version')
    original, candidates = inventory(source, zstd)
    if not candidates:
        raise ValueError('Archive has no classified data assets')
    segments = []
    for kind, destination in [('code', code), ('data', data)]:
        decoder = subprocess.Popen([zstd, '-dc', str(source)], stdout=subprocess.PIPE)
        with Path(destination).open('xb') as output:
            encoder = subprocess.Popen([zstd, '-19', '-T4', '-c'], stdin=subprocess.PIPE, stdout=output)
            try:
                with tarfile.open(fileobj=decoder.stdout, mode='r|') as archive:
                    for member in archive:
                        if (member.name in candidates) != (kind == 'data'):
                            continue
                        encoder.stdin.write(member.tobuf(format=tarfile.PAX_FORMAT))
                        if member.isfile():
                            stream = archive.extractfile(member)
                            remaining = member.size
                            while remaining:
                                block = stream.read(min(1024 * 1024, remaining))
                                if not block:
                                    raise ValueError('Truncated archive member')
                                encoder.stdin.write(block)
                                remaining -= len(block)
                            encoder.stdin.write(b'\0' * (-member.size % 512))
                    if kind == 'data':
                        encoder.stdin.write(b'\0' * 10240)
                encoder.stdin.close()
                if decoder.wait() or encoder.wait():
                    raise ValueError('Split archive preparation failed')
            finally:
                decoder.stdout.close()
                if not encoder.stdin.closed:
                    encoder.stdin.close()
                for process in [decoder, encoder]:
                    if process.poll() is None:
                        process.terminate()
                    process.wait()
        segments.append(dict(identity(destination), kind=kind))
    return {'zstd_version': zstd_version, 'inventory_sha256': original, 'segments': segments}


def combine(paths, destination):
    with Path(destination).open('xb') as output:
        for path in paths:
            with Path(path).open('rb') as source:
                for block in iter(lambda: source.read(1024 * 1024), b''):
                    output.write(block)


def validate_data(source):
    process = subprocess.Popen([os.environ.get('DROIDDECK_ZSTD', 'zstd'), '-dc', str(source)], stdout=subprocess.PIPE)
    found = False
    try:
        with tarfile.open(fileobj=process.stdout, mode='r|') as archive:
            for member in archive:
                if not member.isfile() or not data_file(member, archive.extractfile(member).read(16)):
                    raise ValueError('Executable or unclassified content in a Play data segment')
                found = True
        if process.wait() or not found:
            raise ValueError('Invalid Play data segment')
    finally:
        process.stdout.close()
        if process.poll() is None:
            process.terminate()
        process.wait()


def build_manifest(manifest_path, cache, work, output):
    from tools.playstore.payloads import catalog_bytes, fetch
    manifest = json.loads(Path(manifest_path).read_text())
    work = Path(work)
    work.mkdir(parents=True, exist_ok=True)
    changes = {}
    selected = {'desktop-ubuntu', 'desktop-legacy-kde', 'steamvr', 'droiddeck-proton', 'runtime-ubuntu'}
    for entry in manifest['entries']:
        if entry['id'] not in selected:
            continue
        source = dict(entry)
        if 'layout' in source:
            raise ValueError('Component is already split')
        original = fetch(source, Path(cache))
        code, data, combined = [work / (entry['id'] + '.' + part) for part in ['code.zst', 'data.zst', 'tar.zst']]
        result_path = work / (entry['id'] + '.json')
        if result_path.exists():
            result = json.loads(result_path.read_text())
            for path, part in zip([code, data], result['segments']):
                if identity(path) != {key: part[key] for key in ['size', 'sha256']}:
                    raise ValueError('Existing prepared segment changed')
        else:
            result = prepare(original, code, data)
            combine([code, data], combined)
            if inventory(combined, os.environ.get('DROIDDECK_ZSTD', 'zstd'))[0] != result['inventory_sha256']:
                raise ValueError('Split component changed its installed files')
            result_path.write_text(json.dumps(result, indent=2) + '\n')
        canonical = identity(combined)
        changes[entry['sha256']] = canonical
        entry.update(canonical)
        source['urls'] = [url for url in source['urls'] if url.startswith('https://')]
        entry['layout'] = dict(result, source=source)
        entry.pop('prepare', None)
        entry.pop('upstream', None)
        print(entry['id'], json.dumps(canonical), flush=True)

    def update(value):
        if isinstance(value, dict):
            digest = value.get('sha256') or str(value.get('digest', '')).removeprefix('sha256:')
            if digest in changes and 'size' in value:
                value['size'] = changes[digest]['size']
            for key in value:
                value[key] = update(value[key])
        elif isinstance(value, list):
            value = [update(item) for item in value]
        elif isinstance(value, str):
            for digest, replacement in changes.items():
                if value == digest:
                    return replacement['sha256']
                if value == 'sha256:' + digest:
                    return 'sha256:' + replacement['sha256']
        return value

    for entry in manifest['entries']:
        if 'content' not in entry:
            continue
        entry['content'] = update(entry['content'])
        content = catalog_bytes(entry['content'])
        entry['sha256'] = hashlib.sha256(content).hexdigest()
        entry['size'] = len(content)
    Path(output).write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + '\n')


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--cache', type=Path, required=True)
    parser.add_argument('--work', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    build_manifest(args.manifest, args.cache, args.work, args.output)
