#!/usr/bin/env python3
"""Generate removal inventories from checksum-pinned published archives.

Usage: removal-manifests.py <LXQt tar.zst> <KDE r1 tar.zst> <KDE r2 tar.zst>
                           <emulators tar.zst> <verified KDE package cache> <output dir>
                           <verified LXQt package cache>
Removal includes desktop applications/themes and our overlay, not their shared dependencies.
The KDE cache is verified against the published desktop-kde.packages.txt before use.
The LXQt cache contains the signed application packages listed by desktop.packages.txt;
verify their Arch Linux ARM Build System signatures before generating the inventory.
"""
import gzip
import hashlib
import json
import pathlib
import subprocess
import sys
import tarfile

PINS = [
    ('lxqt-r1', 'c2bb860b3b1688287451e2495be5db313ec07f8c8af501dfac2768a9f417bc86'),
    ('desktop-kde-r1', '55747e75d0ec055ab7ea1e92a757f3be227cc3205bb9101f5b53cadef6318579'),
    ('desktop-kde-r2', '9ba832677b498654136c69d8c8330434d17e4d53cd099acac9bde3ba429b35bf'),
    ('emulators-r1', 'a1f4806a9663d28f424b4cab1277e0ede80a92e69de5bc063cdc8b45af220164'),
]
SHARED_SEEDS = {'xorg-xwayland', 'qt6-wayland', 'wayland-utils', 'noto-fonts', 'noto-fonts-emoji', 'xdg-utils'}


def sha(file):
    with open(file, 'rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def inventory(archive, digest):
    if sha(archive) != digest:
        raise ValueError(f'{archive}: checksum differs from the published pin')
    entries = {}
    process = subprocess.Popen(['zstd', '-dc', str(archive)], stdout=subprocess.PIPE)
    try:
        with tarfile.open(fileobj=process.stdout, mode='r|') as tar:
            for entry in tar:
                path = entry.name.removeprefix('./').rstrip('/')
                assert not any(c in path for c in '\t\n\r') and '..' not in path.split('/') and not path.startswith('/')
                if entry.isfile():
                    entries[path] = f'F\t{entry.size}\t{hashlib.file_digest(tar.extractfile(entry), "sha256").hexdigest()}\t{path}'
                elif entry.issym():
                    assert not any(c in entry.linkname for c in '\t\n\r')
                    entries[path] = f'L\t0\t{entry.linkname}\t{path}'
                elif entry.islnk():
                    target = entry.linkname.removeprefix('./')
                    record = entries[target].split('\t', 3)
                    entries[path] = '\t'.join(record[:3] + [path])
    finally:
        process.stdout.close()
        if process.wait() != 0:
            raise ValueError('zstd failed')
    return entries


def write(out, name, records):
    with open(out / name, 'wb') as stream:
        with gzip.GzipFile(fileobj=stream, mode='wb', mtime=0, filename='') as gz:
            gz.write(('\n'.join(sorted(set(records))) + '\n').encode())


def main():
    archives = [pathlib.Path(p) for p in sys.argv[1:5]]
    cache, out, legacy_cache = map(pathlib.Path, sys.argv[5:8])
    here = pathlib.Path(__file__).resolve().parent
    seeds = {line.strip() for line in (here / 'seeds.txt').read_text().splitlines() if line.strip() and not line.startswith('#')} - SHARED_SEEDS
    # Published source package hashes; the caller places this beside the verified cache.
    packages = dict(line.split() for line in (cache / 'desktop-kde.packages.txt').read_text().splitlines())
    selected = set()
    found = set()
    for filename, digest in packages.items():
        package = filename.rsplit('-', 3)[0]
        if package not in seeds:
            continue
        archive = cache / filename
        if sha(archive) != digest:
            raise ValueError(f'{filename}: source package checksum mismatch')
        with tarfile.open(archive) as tar:
            selected.update(e.name.removeprefix('./').rstrip('/') for e in tar if not e.isdir())
        found.add(package)
    assert found == seeds, f'missing desktop packages: {seeds - found}'
    selected.update(str(p.relative_to(here / 'overlay')) for p in (here / 'overlay').rglob('*') if p.is_file())
    inventories = [inventory(p, pin[1]) for p, pin in zip(archives, PINS)]
    out.mkdir(parents=True, exist_ok=True)
    # The old runtime's pacman database omits some gamescope dependencies (e.g. libseat).
    # Own only the LXQt application packages, never their dependency closure.
    legacy_seeds = {line.strip() for line in (here / 'lxqt-applications.txt').read_text().splitlines()
                    if line.strip() and not line.startswith('#')}
    legacy_selected = set()
    legacy_sources = {}
    legacy_found = set()
    for filename in (legacy_cache / 'selected.txt').read_text().splitlines():
        package = filename.rsplit('-', 3)[0]
        if package not in legacy_seeds:
            continue
        archive = legacy_cache / filename
        with tarfile.open(archive) as tar:
            legacy_selected.update(e.name.removeprefix('./').rstrip('/') for e in tar if not e.isdir())
        legacy_sources[filename] = sha(archive)
        legacy_found.add(package)
    assert legacy_found == legacy_seeds, f'missing LXQt applications: {legacy_seeds - legacy_found}'
    legacy_selected.update({'usr/local/bin/steamdeck-desktop', 'usr/local/bin/steamdeck-steam'})
    write(out, 'lxqt-r1.tsv.gzip', [record for path, record in inventories[0].items() if path in legacy_selected])
    write(out, 'kde-remove.tsv.gzip', [record for inv in inventories[1:3] for path, record in inv.items() if path in selected])
    write(out, 'kde-keep.txt.gzip', [path for inv in inventories[1:3] for path in inv])
    write(out, 'emulators-keep.txt.gzip', inventories[3])
    (out / 'versions.txt').write_text('desktop-kde-r1\ndesktop-kde-r2\n')
    (out / 'sources.json').write_text(json.dumps({'archives': dict(PINS), 'desktopApplications': {n: h for n, h in packages.items() if n.rsplit('-', 3)[0] in seeds}, 'legacyApplications': legacy_sources}, indent=2) + '\n')
    for name, digest in PINS:
        print(name, digest)
    print('KDE removable paths', sum(path in selected for path in inventories[2]))
    print('KDE removable bytes', sum(int(record.split('\t')[1]) for path, record in inventories[2].items() if path in selected))
    print('LXQt removable paths', sum(path in legacy_selected for path in inventories[0]))


if __name__ == '__main__':
    main()
