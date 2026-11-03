import argparse
import hashlib
from pathlib import Path


def prepare(bundle, checksums, output, limit=2_000_000_000):
    bundle, checksums, output = Path(bundle), Path(checksums), Path(output)
    if limit <= 0 or limit >= 2_147_483_648 or output.exists() or bundle.is_symlink():
        raise ValueError('Release assets require a new directory and a safe file limit')
    expected = None
    for line in checksums.read_text().splitlines():
        fields = line.split()
        if len(fields) == 2 and fields[1].lstrip('*') == bundle.name:
            if expected is not None:
                raise ValueError('Duplicate bundle checksum')
            expected = fields[0]
    if expected is None:
        raise ValueError('Signed bundle checksum is missing')
    output.mkdir(parents=True)
    digest = hashlib.sha256()
    parts = []
    remaining = bundle.stat().st_size
    split = remaining > limit
    with bundle.open('rb') as source:
        while remaining:
            name = bundle.name + f'.part{len(parts) + 1:03d}' if split else bundle.name
            part_digest = hashlib.sha256()
            with (output / name).open('xb') as destination:
                size = min(remaining, limit)
                while size:
                    block = source.read(min(size, 1024 * 1024))
                    if not block:
                        raise ValueError('Signed bundle was truncated')
                    destination.write(block)
                    digest.update(block)
                    part_digest.update(block)
                    size -= len(block)
                    remaining -= len(block)
            parts.append((name, part_digest.hexdigest()))
        if source.read(1) or digest.hexdigest() != expected:
            raise ValueError('Signed bundle failed checksum verification')
    (output / 'SHA256SUMS').write_text(expected + '  ' + bundle.name + '\n')
    if split:
        (output / 'PART-SHA256SUMS').write_text(''.join(digest + '  ' + name + '\n' for name, digest in parts))
    return [name for name, _ in parts]


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--checksums', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    prepare(args.bundle, args.checksums, args.output)
