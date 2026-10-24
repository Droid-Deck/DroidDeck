"""Record the Amazon engine's build inputs in its APK-pinned add-on pack."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]


def inputs(root=ROOT):
    paths = ['app/src/main/rust/stores/amazon/Cargo.toml', 'app/src/main/rust/stores/Cargo.lock', 'app/src/main/rust/stores/Cargo.toml',
             'app/src/main/rust/stores/src/lib.rs', 'app/src/main/rust/stores/src/stores_jni.rs',
             'app/src/main/rust/stores/src/amazon', 'app/src/main/rust/stores/src/fetch_core.rs',
             'app/src/main/rust/stores/src/fetch_tuning.rs', 'app/src/main/rust/stores/src/priority.rs',
             'app/src/main/rust/stores/src/md5_small.rs',
             'tools/stores/build-amazon.sh', 'tools/stores/amazon-manifest.py']
    files = []
    for name in paths:
        path = root / name
        files.extend(path.rglob('*') if path.is_dir() else [path])
    return {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(set(files)) if p.is_file() and '__pycache__' not in p.parts}


if __name__ == '__main__':
    out, ndk = map(Path, sys.argv[1:])
    data = {'schema': 1, 'engine': 'amazon', 'abi': 1, 'target': 'aarch64-linux-android',
            'platform': 26, 'inputs': inputs(),
            'rustc': subprocess.check_output(['rustup', 'run', 'stable', 'rustc', '-Vv'], text=True),
            'ndk': (ndk / 'source.properties').read_text(),
            'library_sha256': hashlib.sha256((out / 'libdroiddeckamazon.so').read_bytes()).hexdigest()}
    (out / 'engine.json').write_text(json.dumps(data, sort_keys=True, indent=2) + '\n')
