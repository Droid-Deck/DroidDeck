#!/usr/bin/env python3
import gzip
import hashlib
import json
import os
import stat
import subprocess
import sys

SKIP = {"proc", "sys", "dev", "run", "tmp", "var/tmp", "var/log", "var/cache/apt", "var/lib/apt/lists",
        "etc/resolv.conf", "etc/machine-id", "var/lib/dbus/machine-id"}
META = "usr/share/droiddeck/desktop"


def walk(root):
    out = {}
    for top, dirs, files in os.walk(root):
        rel_top = os.path.relpath(top, root)
        rel_top = "" if rel_top == "." else rel_top
        dirs[:] = [d for d in dirs if os.path.join(rel_top, d) not in SKIP]
        for name in dirs + files:
            rel = os.path.join(rel_top, name)
            if rel in SKIP or name.startswith(".l2s."):
                continue
            st = os.lstat(os.path.join(root, rel))
            if stat.S_ISDIR(st.st_mode):
                out[rel] = ("d", 0, st.st_mode & 0o7777, "", 0)
            elif stat.S_ISLNK(st.st_mode):
                out[rel] = ("l", 0, 0, os.readlink(os.path.join(root, rel)), 0)
            elif stat.S_ISREG(st.st_mode):
                out[rel] = ("f", st.st_size, st.st_mode & 0o7777, "%d.%d" % (st.st_ino, st.st_mtime_ns), 0)
    return out


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def save(manifest, path):
    with open(path, "w") as f:
        for rel, (kind, size, mode, extra, _) in sorted(manifest.items()):
            f.write("%s\t%d\t%o\t%s\t%s\n" % (kind, size, mode, extra, rel))


def load(path):
    out = {}
    with open(path) as f:
        for line in f:
            kind, size, mode, extra, rel = line.rstrip("\n").split("\t", 4)
            if os.path.basename(rel).startswith(".l2s."):
                continue
            out[rel] = (kind, int(size), int(mode, 8), extra, 0)
    return out


def describe(root, rel, entry):
    kind, size, _, extra, _ = entry
    if kind == "f":
        return "F\t%d\t%s" % (size, sha256(os.path.join(root, rel)))
    if kind == "l":
        return "L\t0\t%s" % extra
    return "D\t0\t-"


def pack(root, before_path, runtime_tar, out_tar, meta_root):
    before = load(before_path)
    after = walk(root)
    added = sorted(p for p in after if p not in before)
    changed = sorted(p for p in after if p in before and after[p][:4] != before[p][:4] and after[p][0] != "d")
    removed = sorted(p for p in before if p not in after)
    if any(p.startswith(META + "/") or p == META for p in added + changed + removed):
        sys.exit("the desktop stage must not write " + META)
    meta = os.path.join(meta_root, META)
    originals = os.path.join(meta, "originals")
    os.makedirs(originals, exist_ok=True)
    restore = [p for p in changed + removed if before[p][0] != "d"]
    if restore:
        listing = os.path.join(meta_root, "restore.list")
        with open(listing, "w") as f:
            f.write("".join("./%s\n" % p for p in restore))
        subprocess.run(["tar", "-xf", runtime_tar, "-C", originals, "--numeric-owner", "--no-recursion", "-T", listing], check=True)
        os.unlink(listing)
    with gzip.open(os.path.join(meta, "files.tsv.gz"), "wt") as f:
        for p in added:
            f.write("N\t%s\t%s\n" % (describe(root, p, after[p]), p))
        for p in changed:
            f.write("C\t%s\t%s\n" % (describe(root, p, after[p]), p))
        for p in removed:
            f.write("R\t%s\t%s\n" % (describe(originals, p, before[p]) if before[p][0] != "d" else "D\t0\t-", p))
    listing = os.path.join(meta_root, "pack.list")
    with open(listing, "w") as f:
        f.write("".join("./%s\n" % p for p in added + changed))
    metalist = os.path.join(meta_root, "meta.list")
    with open(metalist, "w") as f:
        for top, dirs, files in os.walk(meta):
            for name in dirs + files:
                f.write("./%s\n" % os.path.relpath(os.path.join(top, name), meta_root))
        f.write("./%s\n" % META)
    subprocess.run(["tar", "--numeric-owner", "--no-recursion", "--hard-dereference", "-cf", out_tar, "-C", root, "-T", listing,
                    "-C", meta_root, "-T", metalist], check=True)
    os.unlink(listing)
    os.unlink(metalist)
    print("desktop pack: %d added, %d changed, %d removed" % (len(added), len(changed), len(removed)))


def describe_pack(archive, url, tag, out):
    digest = sha256(archive)
    size = os.path.getsize(archive)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w") as f:
        json.dump({"tag": tag, "url": url, "sha256": digest, "size": size}, f, indent=2)
        f.write("\n")


if __name__ == "__main__":
    command = sys.argv[1] if len(sys.argv) > 1 else ""
    if command == "manifest" and len(sys.argv) == 4:
        save(walk(sys.argv[2]), sys.argv[3])
    elif command == "pack" and len(sys.argv) == 7:
        pack(*sys.argv[2:])
    elif command == "describe" and len(sys.argv) == 6:
        describe_pack(*sys.argv[2:])
    else:
        sys.exit("usage: desktop-pack.py manifest ROOT OUT | pack ROOT BEFORE RUNTIME_TAR OUT_TAR META_ROOT | describe ARCHIVE URL TAG OUT_JSON")
