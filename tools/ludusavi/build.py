"""Bundle a CLI-only ludusavi build. Bump VERSION and rebase cli-only.patch on the new upstream tag.

Set DROIDDECK_LUDUSAVI_TARGET for cross builds; the resulting binary must still be aarch64."""

import hashlib
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
from pathlib import Path

VERSION = "0.31.0"
URL = f"https://codeload.github.com/mtkennerly/ludusavi/tar.gz/refs/tags/v{VERSION}"
ROOT = Path(__file__).resolve().parents[2]
PATCH = Path(__file__).resolve().parent / "cli-only.patch"
OUTPUT = ROOT / "tools/linuxfs/ludusavi/linuxfs/usr/local/lib/droiddeck-ludusavi/ludusavi"
MARKER = ".droiddeck-cli-only-applied"
AARCH64_EM = 183


def source_identity():
    return f"{VERSION}\n{hashlib.sha256(PATCH.read_bytes()).hexdigest()}"


def assert_aarch64(binary):
    with binary.open("rb") as stream:
        header = stream.read(20)
    if (len(header) < 20 or header[:4] != b"\x7fELF" or header[4] != 2 or header[5] != 1
            or int.from_bytes(header[18:20], "little") != AARCH64_EM):
        raise SystemExit(f"{binary} is not an aarch64 ELF; set DROIDDECK_LUDUSAVI_TARGET=aarch64-unknown-linux-gnu")


def main():
    work = Path(os.environ.get("DROIDDECK_LUDUSAVI_BUILD_DIR", tempfile.gettempdir())) / f"ludusavi-{VERSION}"
    archive = work.parent / f"ludusavi-{VERSION}.tar.gz"
    source = work / f"ludusavi-{VERSION}"
    work.mkdir(parents=True, exist_ok=True)
    if not archive.is_file():
        urllib.request.urlretrieve(URL, archive)
    marker = source / MARKER
    identity = source_identity()
    if not marker.is_file() or marker.read_text() != identity:
        if source.exists():
            shutil.rmtree(source)
        with tarfile.open(archive) as tar:
            tar.extractall(work)
        subprocess.run(["git", "apply", str(PATCH)], cwd=source, check=True)
        marker.write_text(identity)
    target = os.environ.get("DROIDDECK_LUDUSAVI_TARGET")
    command = ["cargo", "build", "--release", "--no-default-features", "--features", "cli"]
    if target:
        command += ["--target", target]
    subprocess.run(command, cwd=source, check=True)
    binary = source / "target" / target / "release/ludusavi" if target else source / "target/release/ludusavi"
    assert_aarch64(binary)
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(binary, OUTPUT)
    print(f"wrote {OUTPUT}")


if __name__ == "__main__":
    sys.exit(main())
