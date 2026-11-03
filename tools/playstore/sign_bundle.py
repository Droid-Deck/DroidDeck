import argparse
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile


def sign(source, destination, keystore, alias, expected_signer):
    if source.resolve() == destination.resolve() or destination.exists():
        raise ValueError('Signing requires a new output file')
    if not re.fullmatch(r'[0-9a-f]{64}', expected_signer):
        raise ValueError('Invalid pinned signing certificate')
    password = os.environ.get('RELEASE_STORE_PASSWORD')
    if not password:
        raise ValueError('Signing password is unavailable')
    if not alias:
        raise ValueError('Signing alias is unavailable')
    with tempfile.TemporaryDirectory(prefix='play-signing-') as temporary:
        unsigned = Path(temporary) / 'unsigned.aab'
        with zipfile.ZipFile(source) as original:
            if len(original.namelist()) != len(set(original.namelist())):
                raise ValueError('Bundle contains duplicate ZIP entries')
            needs_sanitizing = any(signature(entry.filename) for entry in original.infolist())
        if needs_sanitizing:
            sanitize(source, unsigned)
        else:
            unsigned = source
        environment = dict(os.environ, RELEASE_KEY_ALIAS=alias, RELEASE_KEYSTORE_PATH=str(keystore.resolve()))
        result = subprocess.run(['java', '--add-modules', 'jdk.jartool', str(Path(__file__).with_name('SignBundle.java')),
                                 str(unsigned), str(destination), expected_signer], env=environment, capture_output=True, text=True)
        if result.returncode == 2:
            raise ValueError('Signing certificate differs from the pinned release identity')
        if result.returncode:
            raise ValueError('Bundle signing or signature verification failed')
        print('Bundle signature and payload coverage verified')


def signature(name):
    name = name.upper()
    return name == 'META-INF/MANIFEST.MF' or name.startswith('META-INF/') and '/' not in name[9:] and (
        name.endswith(('.SF', '.RSA', '.DSA', '.EC')) or name[9:].startswith('SIG-'))


def sanitize(source, destination):
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(destination, 'x') as output:
        if len(original.namelist()) != len(set(original.namelist())):
            raise ValueError('Bundle contains duplicate ZIP entries')
        for entry in original.infolist():
            if signature(entry.filename):
                continue
            with original.open(entry) as source_stream, output.open(entry, 'w', force_zip64=True) as target:
                while block := source_stream.read(1024 * 1024):
                    target.write(block)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('source', type=Path)
    parser.add_argument('destination', type=Path)
    parser.add_argument('--keystore', type=Path, required=True)
    parser.add_argument('--signer', type=Path, default=Path('keystore/release-signer.sha256'))
    arguments = parser.parse_args()
    sign(arguments.source, arguments.destination, arguments.keystore, os.environ.get('RELEASE_KEY_ALIAS'), arguments.signer.read_text().strip())


if __name__ == '__main__':
    main()
