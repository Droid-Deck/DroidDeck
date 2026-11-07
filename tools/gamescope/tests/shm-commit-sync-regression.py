#!/usr/bin/env python3
"""Compile the real commit-queue dispatch block alongside its sync helper tests."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile


def main():
    source_dir = Path(sys.argv[1]).resolve()
    source = (source_dir / "steamcompmgr.cpp").read_text()
    function = source.index("void update_wayland_res(")
    start = source.index("\tif ( !gamescope::DispatchBufferCommitSync(", function)
    end = source.index("\tw->commit_queue.push_back( std::move(newCommit) );", start)
    end = source.index("\n", end)
    with tempfile.TemporaryDirectory(prefix="gamescope-commit-sync-") as temporary:
        directory = Path(temporary)
        block = directory / "commit-queue.inc"
        block.write_text(source[start:end] + "\n")
        binary = directory / "test-commit-sync"
        subprocess.run([
            os.environ.get("CXX", "g++"), "-std=c++17", "-Wall", "-Wextra", "-Werror", "-UNDEBUG",
            "-I", str(source_dir), f'-DDROIDDECK_PRODUCTION_QUEUE_BLOCK="{block}"',
            str(Path(__file__).with_name("shm-commit-sync.cpp")), "-o", str(binary),
        ], check=True)
        subprocess.run([str(binary)], check=True)


if __name__ == "__main__":
    main()
