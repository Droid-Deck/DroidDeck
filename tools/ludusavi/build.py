"""Bundle a CLI-only ludusavi build. Bump VERSION and rebase cli-only.patch on the new upstream tag."""

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


def main():
    work = Path(os.environ.get("DROIDDECK_LUDUSAVI_BUILD_DIR", tempfile.gettempdir())) / f"ludusavi-{VERSION}"
    archive = work.parent / f"ludusavi-{VERSION}.tar.gz"
    source = work / f"ludusavi-{VERSION}"
    work.mkdir(parents=True, exist_ok=True)
    if not archive.is_file():
        urllib.request.urlretrieve(URL, archive)
    if not source.is_dir():
        with tarfile.open(archive) as tar:
            tar.extractall(work)
    if not (source / MARKER).is_file():
        subprocess.run(["git", "apply", str(PATCH)], cwd=source, check=True)
        (source / MARKER).write_text(VERSION)
    subprocess.run([
        "cargo", "build", "--release", "--no-default-features", "--features", "cli",
    ], cwd=source, check=True)
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source / "target/release/ludusavi", OUTPUT)
    print(f"wrote {OUTPUT}")


if __name__ == "__main__":
    sys.exit(main())
