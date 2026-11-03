import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import zipfile


DIRECTORY = Path('/opt/droiddeck-bundles')
INDEX = 'assets/play-components/index.json'
INDEX_LIMIT = 16 * 1024 * 1024
SHA256 = re.compile(r'[0-9a-f]{64}')
ASSET = re.compile(r'play-components/[0-9a-f]{64}/[0-9]{4,}')


def enabled(directory=DIRECTORY):
    return any(directory.glob('*/*.apk'))


class BundleReader:
    def __init__(self, directory=DIRECTORY):
        self.archives = []
        self.assets = {}
        try:
            paths = sorted(directory.glob('*/*.apk'))
            if not paths or len(paths) > 128:
                raise ValueError('Installed component bundles are unavailable')
            for path in paths:
                archive = zipfile.ZipFile(path)
                self.archives.append(archive)
                for entry in archive.infolist():
                    if not entry.filename.startswith('assets/play-components/'):
                        continue
                    if entry.filename in self.assets:
                        raise ValueError('Duplicate component asset: ' + entry.filename)
                    self.assets[entry.filename] = (archive, entry)
            index = self.read_object(INDEX, 2 * 1024 * 1024)
            if index.get('schema') != 1 or not isinstance(index.get('entries'), dict):
                raise ValueError('Unsupported component index')
            self.entries = index['entries']
        except BaseException:
            self.close()
            raise

    def close(self):
        for archive in self.archives:
            archive.close()

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()

    def read_object(self, path, limit=INDEX_LIMIT):
        archive, entry = self.assets[path]
        if entry.file_size > limit:
            raise ValueError('Component metadata exceeds its size limit')
        with archive.open(entry) as source:
            return json.load(source)

    def copy(self, url, output, limit, expected_sha256=None):
        entry = self.entries.get(url)
        if entry is None:
            raise ValueError('Component is absent from this build: ' + url)
        size, digest = entry['size'], entry['sha256']
        if type(size) is not int or size <= 0 or size > limit or not SHA256.fullmatch(digest):
            raise ValueError('Invalid component metadata')
        if expected_sha256 is not None and digest != expected_sha256.lower():
            raise ValueError('Component version does not match this build')
        recipe = 'play-components/recipes/' + digest + '.json'
        if entry.get('recipe') != recipe:
            raise ValueError('Invalid component recipe path')
        chunks = self.read_object('assets/' + recipe)['chunks']
        if not chunks:
            raise ValueError('Component contains no payload')
        full_digest = hashlib.sha256()
        total = 0
        for chunk in chunks:
            path, chunk_size, chunk_sha256 = chunk['asset'], chunk['size'], chunk['sha256']
            if not ASSET.fullmatch(path) or type(chunk_size) is not int or not 0 < chunk_size <= size - total:
                raise ValueError('Invalid component chunk metadata')
            if not SHA256.fullmatch(chunk_sha256):
                raise ValueError('Invalid component chunk digest')
            archive, asset = self.assets['assets/' + path]
            if asset.file_size != chunk_size:
                raise ValueError('Component chunk size mismatch')
            chunk_digest = hashlib.sha256()
            copied = 0
            with archive.open(asset) as source:
                for block in iter(lambda: source.read(65536), b''):
                    copied += len(block)
                    total += len(block)
                    if copied > chunk_size:
                        raise ValueError('Component chunk exceeds pinned size')
                    chunk_digest.update(block)
                    full_digest.update(block)
                    output.write(block)
            if copied != chunk_size or chunk_digest.hexdigest() != chunk_sha256:
                raise ValueError('Component chunk failed verification')
        if total != size or full_digest.hexdigest() != digest:
            raise ValueError('Component failed verification')
        return total

    def read(self, url):
        output = io.BytesIO()
        self.copy(url, output, INDEX_LIMIT)
        return output.getvalue()

    def fetch(self, url, destination, expected_sha256=None):
        destination = Path(destination)
        destination.parent.mkdir(parents=True, exist_ok=True)
        descriptor, temporary = tempfile.mkstemp(prefix=destination.name + '.', suffix='.component-part', dir=destination.parent)
        try:
            with os.fdopen(descriptor, 'wb') as output:
                self.copy(url, output, sys.maxsize, expected_sha256)
            os.replace(temporary, destination)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=['enabled', 'read', 'fetch'])
    parser.add_argument('url', nargs='?')
    parser.add_argument('destination', nargs='?', type=Path)
    parser.add_argument('--sha256')
    arguments = parser.parse_args()
    if arguments.command == 'enabled':
        return 0 if enabled() else 1
    if not arguments.url or arguments.command == 'fetch' and arguments.destination is None:
        parser.error('Component URL and fetch destination are required')
    with BundleReader() as reader:
        if arguments.command == 'read':
            sys.stdout.buffer.write(reader.read(arguments.url))
        else:
            reader.fetch(arguments.url, arguments.destination, arguments.sha256)
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile) as error:
        print('Bundled component unavailable: ' + str(error), file=sys.stderr)
        sys.exit(1)
