import gzip
import hashlib
from pathlib import Path
import subprocess


def prepare(source, destination, definition):
    if definition['method'] != 'gzip-to-zstd-19':
        raise ValueError('Unsupported component preparation method')
    version = subprocess.check_output(['zstd', '--version'], text=True)
    if 'v' + definition['zstd_version'] + ',' not in version:
        raise ValueError('Component preparation requires the pinned zstd version')
    digest = hashlib.sha256()
    size = 0
    with gzip.open(source, 'rb') as original, Path(destination).open('xb') as output:
        process = subprocess.Popen(['zstd', '-19', '-T4', '-c'], stdin=subprocess.PIPE, stdout=output)
        try:
            for block in iter(lambda: original.read(1024 * 1024), b''):
                size += len(block)
                if size > definition['tar_size']:
                    raise ValueError('Prepared archive exceeds its pinned tar size')
                digest.update(block)
                process.stdin.write(block)
            process.stdin.close()
            if process.wait():
                raise ValueError('Component archive compression failed')
        except BaseException:
            process.stdin.close()
            if process.poll() is None:
                process.terminate()
            process.wait()
            raise
    if size != definition['tar_size'] or digest.hexdigest() != definition['tar_sha256']:
        raise ValueError('Prepared archive changed its pinned tar content')
