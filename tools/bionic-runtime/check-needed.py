#!/usr/bin/env python3
"""Checks that every library an ELF file in the bionic runtime pack needs is in the pack, in --available
(the Proton layer's lib/, on the library path at run time) or on Android.

usage: check-needed.py <llvm-readelf> <pack dir> [--available DIR]... [--fill IMAGEFS_LIB] [--prune SUBDIR]
  --fill   copies missing libraries (and the links on the way to them) from IMAGEFS_LIB into <pack>/lib, repeatedly
  --prune  deletes ELF files under <pack>/SUBDIR that still miss a library (plugins nothing else links to)
Exits 1 when anything is still missing."""
import argparse
import os
import shutil
import subprocess
import sys

# Android's own libraries (/system/lib64, /apex) the pack may rely on.
ANDROID = {
    "libc.so", "libm.so", "libdl.so", "liblog.so", "libz.so", "libandroid.so", "libnativewindow.so", "libsync.so",
    "libEGL.so", "libGLESv1_CM.so", "libGLESv2.so", "libGLESv3.so", "libvulkan.so", "libaaudio.so", "libOpenSLES.so",
    "libmediandk.so", "libjnigraphics.so", "libcamera2ndk.so", "libbinder_ndk.so", "ld-android.so",
}
# Known gaps, loaded only for features the first version does not cover.
OPTIONAL = {
    "libGL.so.1",  # OpenGL games; a dangling link in the pinned imagefs
    "libodbc.so",  # odbc32
}


def elf_files(pack):
    for root, _, files in os.walk(pack):
        for f in files:
            path = os.path.join(root, f)
            if os.path.islink(path) or not os.path.isfile(path):
                continue
            with open(path, "rb") as fh:
                if fh.read(4) == b"\x7fELF":
                    yield path


def needed(readelf, path):
    run = subprocess.run([readelf, "-d", path], capture_output=True, text=True)
    if run.returncode != 0:  # a damaged ELF must fail the check, not pass with no dependencies
        raise SystemExit("check-needed: %s -d %s failed (%d): %s" % (readelf, path, run.returncode, run.stderr.strip()))
    return [l.split("[", 1)[1].split("]", 1)[0] for l in run.stdout.splitlines() if "(NEEDED)" in l]


def names_in(*dirs):
    names = set()
    for d in dirs:
        for root, _, files in os.walk(d):
            # a link whose target is missing provides nothing
            names.update(f for f in files if os.path.isfile(os.path.join(root, f)))
    return names


def missing_map(readelf, pack, available):
    have = names_in(pack, *available)
    missing = {}
    for path in elf_files(pack):
        for lib in needed(readelf, path):
            if lib not in have and lib not in ANDROID and lib not in OPTIONAL:
                missing.setdefault(lib, []).append(path)
    return missing


def copy_from(img, name, dest):
    """Copies img/name into dest, keeping relative links; False when absent or one of Android's (absolute link)."""
    src = os.path.join(img, name)
    if not os.path.lexists(src):
        return False
    while os.path.islink(src):
        target = os.readlink(src)
        if target.startswith("/"):
            return False
        link = os.path.join(dest, os.path.basename(src))
        if not os.path.lexists(link):
            os.symlink(os.path.basename(target), link)
        src = os.path.join(os.path.dirname(src), target)
    if not os.path.exists(src):
        return False
    out = os.path.join(dest, os.path.basename(src))
    if not os.path.exists(out):
        shutil.copy2(src, out)
    return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("readelf")
    ap.add_argument("pack")
    ap.add_argument("--available", action="append", default=[])
    ap.add_argument("--fill")
    ap.add_argument("--prune")
    a = ap.parse_args()

    if a.fill:
        added = []
        while True:
            missing = missing_map(a.readelf, a.pack, a.available)
            new = [lib for lib in sorted(missing) if copy_from(a.fill, lib, os.path.join(a.pack, "lib"))]
            if not new:
                break
            added += new
        print(f"added from the imagefs: {len(added)} ({', '.join(added)})" if added else "added from the imagefs: none")

    if a.prune:
        prune_root = os.path.join(a.pack, a.prune)
        while True:
            missing = missing_map(a.readelf, a.pack, a.available)
            doomed = {p for users in missing.values() for p in users if p.startswith(prune_root + os.sep)}
            if not doomed:
                break
            for p in sorted(doomed):
                libs = sorted(l for l, users in missing.items() if p in users)
                print(f"pruned {os.path.relpath(p, a.pack)}: missing {', '.join(libs)}")
                os.remove(p)

    missing = missing_map(a.readelf, a.pack, a.available)
    for lib, users in sorted(missing.items()):
        print(f"missing {lib}: needed by {', '.join(sorted(os.path.relpath(u, a.pack) for u in users)[:5])}")
    if missing:
        sys.exit(1)
    print("all needed libraries present")


if __name__ == "__main__":
    main()
