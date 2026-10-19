"""Prepare the pinned VITURE Android SDK from an authorized archive."""
import argparse
import hashlib
from http.client import HTTPException
import json
import os
from pathlib import Path
import re
import shutil
import stat
import sys
import tempfile
from urllib.error import URLError
from urllib.parse import urlsplit
from urllib.request import build_opener, HTTPRedirectHandler
from zipfile import BadZipFile, ZipFile


RELEASE = Path(__file__).with_name('release.json')
REQUIRED_FILES = (
    'include/viture_camera_provider.h',
    'include/viture_device.h',
    'include/viture_device_carina.h',
    'include/viture_glasses_provider.h',
    'include/viture_macros_public.h',
    'include/viture_protocol_public.h',
    'include/viture_result.h',
    'include/viture_stat_reporter.h',
    'include/viture_version.h',
    'android/arm64-v8a/libcarina_vio.so',
    'android/arm64-v8a/libcloud_protocol.so',
    'android/arm64-v8a/libglasses.so',
    'android/LICENSE',
    'android/NOTICE',
)


class PreparationError(Exception):
    pass


def require_https(url):
    try:
        parsed = urlsplit(url)
        valid = parsed.scheme == 'https' and bool(parsed.hostname)
    except ValueError:
        valid = False
    if not valid:
        raise PreparationError('VITURE SDK downloads require an HTTPS URL.')


class HttpsRedirectHandler(HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        require_https(new_url)
        return super().redirect_request(request, response, code, message, headers, new_url)


def download_archive(destination):
    url = os.environ.get('VITURE_SDK_URL', '')
    if not url:
        raise PreparationError('Set VITURE_SDK_URL to an authorized SDK archive URL, or use --archive.')
    require_https(url)
    try:
        with build_opener(HttpsRedirectHandler()).open(url, timeout=60) as response:
            require_https(response.geturl())
            with destination.open('wb') as output:
                shutil.copyfileobj(response, output)
    except (URLError, OSError, ValueError, HTTPException):
        # Signed archive URLs contain credentials and must not enter CI logs.
        raise PreparationError('VITURE SDK download failed; check VITURE_SDK_URL and network access.') from None


def read_release():
    release = json.loads(RELEASE.read_text(encoding='utf-8'))
    if not re.fullmatch(r'\d+\.\d+\.\d+', release['version']):
        raise PreparationError('Invalid pinned VITURE SDK version.')
    if not re.fullmatch(r'[0-9a-f]{64}', release['sha256']):
        raise PreparationError('Invalid pinned VITURE SDK SHA-256.')
    return release


def digest(path):
    checksum = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            checksum.update(chunk)
    return checksum.hexdigest()


def extract_sdk(archive, destination, version):
    with ZipFile(archive) as package:
        selected = {}
        for entry in package.infolist():
            if entry.filename not in REQUIRED_FILES:
                continue
            if entry.filename in selected:
                raise PreparationError(f'Duplicate SDK archive entry: {entry.filename}')
            if entry.is_dir() or stat.S_ISLNK(entry.external_attr >> 16) or entry.file_size == 0:
                raise PreparationError(f'Invalid or empty SDK archive entry: {entry.filename}')
            selected[entry.filename] = entry
        missing = set(REQUIRED_FILES) - selected.keys()
        if missing:
            raise PreparationError('SDK archive is missing required files: ' + ', '.join(sorted(missing)))
        for name in REQUIRED_FILES:
            target = destination / name
            target.parent.mkdir(parents=True, exist_ok=True)
            with package.open(selected[name]) as source, target.open('wb') as output:
                shutil.copyfileobj(source, output)
    header = (destination / 'include/viture_version.h').read_text(encoding='utf-8')
    parts = [re.search(r'^#define\s+VITURE_VERSION_' + part + r'\s+(\d+)\s*$', header, re.MULTILINE)
             for part in ('MAJOR', 'MINOR', 'PATCH')]
    if not all(parts) or '.'.join(part.group(1) for part in parts) != version:
        raise PreparationError('SDK header version does not match the pinned release.')


def prepare(output, archive=None):
    output = Path(output).absolute()
    if output.exists() or output.is_symlink():
        raise PreparationError('SDK output already exists; choose a new output directory.')
    release = read_release()
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.viture-sdk-', dir=output.parent) as temporary:
        temporary = Path(temporary)
        if archive is None:
            archive = temporary / 'sdk.zip'
            download_archive(archive)
        else:
            archive = Path(archive)
        if digest(archive) != release['sha256']:
            raise PreparationError('VITURE SDK archive SHA-256 does not match the pinned release.')
        staging = temporary / 'sdk'
        extract_sdk(archive, staging, release['version'])
        if output.exists() or output.is_symlink():
            raise PreparationError('SDK output already exists; choose a new output directory.')
        staging.rename(output)
    return release['version']


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path, help='New SDK output directory')
    parser.add_argument('--archive', type=Path, help='Authorized local SDK ZIP instead of VITURE_SDK_URL')
    args = parser.parse_args(argv)
    try:
        version = prepare(args.output, args.archive)
    except PreparationError as error:
        print(f'VITURE SDK preparation failed: {error}', file=sys.stderr)
        return 1
    except (OSError, BadZipFile, ValueError, KeyError, RuntimeError):
        print('VITURE SDK preparation failed: could not read or stage the pinned SDK archive.', file=sys.stderr)
        return 1
    print(f'Prepared VITURE Android SDK {version}.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
