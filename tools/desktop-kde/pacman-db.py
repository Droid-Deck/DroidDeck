#!/usr/bin/env python3
import base64
import hashlib
import io
import os
import re
import subprocess
import sys
import tarfile
import time

REPOS = ("core", "extra", "alarm")
FIELDS = ("NAME", "VERSION", "BASE", "DESC", "URL", "ARCH", "BUILDDATE", "INSTALLDATE", "PACKAGER", "SIZE",
          "GROUPS", "LICENSE", "VALIDATION", "REPLACES", "DEPENDS", "OPTDEPENDS", "CONFLICTS", "PROVIDES", "XDATA")
PKGINFO = {"pkgname": "NAME", "pkgbase": "BASE", "pkgver": "VERSION", "pkgdesc": "DESC", "url": "URL",
           "builddate": "BUILDDATE", "packager": "PACKAGER", "size": "SIZE", "arch": "ARCH", "license": "LICENSE",
           "replaces": "REPLACES", "group": "GROUPS", "conflict": "CONFLICTS", "provides": "PROVIDES",
           "depend": "DEPENDS", "optdepend": "OPTDEPENDS", "xdata": "XDATA"}
BLOCKED = re.compile(r"^(sudo|opendoas|doas|mkinitcpio.*|linux|linux-.*|.*-firmware|firmware-.*|grub|uboot-.*|"
                     r"raspberrypi-.*|archlinuxarm-keyring|pacman|pacman-mirrorlist)$")
ALARM = "http://mirror.archlinuxarm.org/aarch64"
RELEASES = "https://github.com/Droid-Deck/DroidDeck-Components/releases/download"
BUILDER = "68B3537F39A313B3E574D06777193F152BDBE6A6"


def read_entry(path):
    fields, key = {}, None
    for name in ("desc", "depends"):
        file = os.path.join(path, name)
        if not os.path.exists(file):
            continue
        for line in open(file, encoding="utf-8", errors="replace"):
            line = line.rstrip("\n")
            if line.startswith("%") and line.endswith("%") and len(line) > 2:
                key = line.strip("%")
                fields[key] = []
            elif line == "":
                key = None
            elif key:
                fields[key].append(line)
    return fields


def write_fields(fields, order):
    out = []
    for key in order:
        values = fields.get(key)
        if values:
            out.append("%" + key + "%\n" + "".join(v + "\n" for v in values))
    return "\n".join(out)


def dep_name(dep):
    return re.split(r"[<>=]", dep, maxsplit=1)[0]


def asset_name(name):
    return re.sub(r"[^A-Za-z0-9._-]", ".", name)


def package_members(path):
    if path.endswith(".zst"):
        data = subprocess.run(["zstd", "-dc", path], check=True, stdout=subprocess.PIPE).stdout
        return tarfile.open(fileobj=io.BytesIO(data), mode="r:")
    return tarfile.open(path)


def local_record(path, installed):
    with package_members(path) as tar:
        info, mtree, install, files = None, None, None, []
        for member in tar:
            name = member.name.removeprefix("./")
            if name == ".PKGINFO":
                info = tar.extractfile(member).read().decode("utf-8", "replace")
            elif name == ".MTREE":
                mtree = tar.extractfile(member).read()
            elif name == ".INSTALL":
                install = tar.extractfile(member).read()
            elif name in (".BUILDINFO", ".CHANGELOG") or not name:
                continue
            else:
                files.append(name.rstrip("/") + ("/" if member.isdir() else ""))
    if info is None or mtree is None:
        raise SystemExit(f"{path}: no .PKGINFO or .MTREE")
    fields, backup = {}, []
    for line in info.splitlines():
        if " = " not in line or line.startswith("#"):
            continue
        key, value = line.split(" = ", 1)
        if key == "backup":
            backup.append(value)
        elif key in PKGINFO:
            fields.setdefault(PKGINFO[key], []).append(value)
    fields["INSTALLDATE"] = [str(installed)]
    fields["VALIDATION"] = ["pgp"]
    for dir_ in sorted({f.rsplit("/", 1)[0] + "/" for f in files if "/" in f.rstrip("/")}):
        parts = dir_.rstrip("/").split("/")
        for i in range(1, len(parts) + 1):
            entry = "/".join(parts[:i]) + "/"
            if entry not in files:
                files.append(entry)
    sums = {}
    if backup:
        with package_members(path) as tar:
            for member in tar:
                name = member.name.removeprefix("./")
                if name in backup and member.isfile():
                    sums[name] = hashlib.md5(tar.extractfile(member).read()).hexdigest()
    listing = "%FILES%\n" + "".join(f + "\n" for f in sorted(set(files)))
    if backup:
        listing += "\n%BACKUP%\n" + "".join(f"{b}\t{sums.get(b, '')}\n" for b in backup)
    return fields, listing, mtree, install


def fetch(dest, *urls):
    for url in urls:
        if subprocess.run(["curl", "-fsSL", "--retry", "6", "--retry-delay", "5", "--retry-all-errors",
                           "-o", dest, url]).returncode == 0:
            return
    raise SystemExit(f"could not download {os.path.basename(dest)}")


def main():
    dbdir, cache, pkglist, runtime_file, mirror_file, tag, root, repo = sys.argv[1:9]
    here = os.path.dirname(os.path.abspath(__file__))
    installed = int(os.environ.get("SOURCE_DATE_EPOCH", time.time()))

    entries = {}
    for source, path in [("extra", os.path.join(here, "pins"))] + [(r, os.path.join(dbdir, "x_" + r)) for r in REPOS]:
        for entry in sorted(os.listdir(path)):
            fields = read_entry(os.path.join(path, entry))
            name = fields.get("NAME", [None])[0]
            if name and name not in entries:
                entries[name] = (source, fields)
    by_file = {fields["FILENAME"][0]: name for name, (_, fields) in entries.items()}

    runtime = dict(line.split() for line in open(runtime_file) if len(line.split()) == 2)
    shipped = {}
    for line in open(pkglist):
        file = line.split()[0].split("/", 1)[1]
        shipped[by_file[file]] = file

    local = os.path.join(root, "var/lib/pacman/local")
    os.makedirs(local, exist_ok=True)
    for name, file in sorted(shipped.items()):
        fields, listing, mtree, install = local_record(os.path.join(cache, file), installed)
        if fields["NAME"][0] != name:
            raise SystemExit(f"{file}: .PKGINFO names {fields['NAME'][0]}, not {name}")
        record = os.path.join(local, f"{name}-{fields['VERSION'][0]}")
        os.makedirs(record, exist_ok=True)
        with open(os.path.join(record, "desc"), "w") as out:
            out.write(write_fields(fields, FIELDS))
        with open(os.path.join(record, "files"), "w") as out:
            out.write(listing)
        with open(os.path.join(record, "mtree"), "wb") as out:
            out.write(mtree)
        if install:
            with open(os.path.join(record, "install"), "wb") as out:
                out.write(install)

    system = set(runtime) | set(shipped)
    available = {n for n in entries if not BLOCKED.match(n)} | system
    providers = {}
    for name in available:
        if name in entries:
            for p in entries[name][1].get("PROVIDES", []):
                providers.setdefault(dep_name(p), name)
    satisfied = set(system)
    for name in system:
        if name in entries:
            satisfied.update(dep_name(p) for p in entries[name][1].get("PROVIDES", []))

    wanted = [l.strip() for l in open(mirror_file) if l.strip() and not l.startswith("#")]
    mirrored, queue = set(), list(wanted)
    while queue:
        dep = dep_name(queue.pop())
        if dep in satisfied:
            continue
        name = dep if dep in available and dep in entries else providers.get(dep)
        if name is None:
            raise SystemExit(f"mirror.txt: nothing in the repository provides {dep}")
        if name in mirrored or name in system:
            continue
        mirrored.add(name)
        queue.extend(entries[name][1].get("DEPENDS", []))

    os.makedirs(repo, exist_ok=True)
    for name in sorted(mirrored):
        source, fields = entries[name]
        file = fields["FILENAME"][0]
        dest = os.path.join(repo, asset_name(file))
        if not os.path.exists(dest) or hashlib.sha256(open(dest, "rb").read()).hexdigest() != fields["SHA256SUM"][0]:
            fetch(dest, f"{ALARM}/{source}/{file}")
        if hashlib.sha256(open(dest, "rb").read()).hexdigest() != fields["SHA256SUM"][0]:
            raise SystemExit(f"{file}: sha256 differs from the repository database's")
        signature = fields.get("PGPSIG", [None])[0]
        sig = dest + ".sig"
        if signature:
            with open(sig, "wb") as out:
                out.write(base64.b64decode(signature))
        else:
            fetch(sig, f"{ALARM}/{source}/{file}.sig")
        check = subprocess.run(["gpgv", "--status-fd", "1", "--keyring", os.path.join(here, "archlinuxarm-builder.gpg"),
                                sig, dest], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True).stdout
        if not re.search(rf"^\[GNUPG:\] VALIDSIG .* {BUILDER}$", check, re.M):
            raise SystemExit(f"{file}: no valid Arch Linux ARM Build System signature")
        if not signature:
            fields["PGPSIG"] = [base64.b64encode(open(sig, "rb").read()).decode()]
        os.remove(sig)
    print(f"pacman: {len(shipped)} install records, {len(mirrored)} mirrored packages, "
          f"{sum(os.path.getsize(os.path.join(repo, asset_name(entries[n][1]['FILENAME'][0]))) for n in mirrored) // 1048576} MiB")

    order = ["FILENAME", "NAME", "BASE", "VERSION", "DESC", "GROUPS", "CSIZE", "ISIZE", "MD5SUM", "SHA256SUM", "PGPSIG",
             "URL", "LICENSE", "ARCH", "BUILDDATE", "PACKAGER", "REPLACES", "CONFLICTS", "PROVIDES", "DEPENDS",
             "OPTDEPENDS", "MAKEDEPENDS", "CHECKDEPENDS", "XDATA"]
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w:gz", format=tarfile.GNU_FORMAT) as db:
        for name in sorted(available):
            if name in entries and (name not in runtime or name in shipped or runtime[name] == entries[name][1]["VERSION"][0]):
                fields = dict(entries[name][1])
            else:
                fields = {"FILENAME": [f"{name}-{runtime[name]}-aarch64.pkg.tar.xz"], "NAME": [name],
                          "VERSION": [runtime[name]], "ARCH": ["aarch64"]}
            if name in mirrored:
                fields["FILENAME"] = [asset_name(fields["FILENAME"][0])]
            data = write_fields(fields, order).encode()
            folder = f"{name}-{fields['VERSION'][0]}"
            info = tarfile.TarInfo(folder)
            info.type, info.mode, info.mtime = tarfile.DIRTYPE, 0o755, installed
            db.addfile(info)
            info = tarfile.TarInfo(folder + "/desc")
            info.size, info.mode, info.mtime = len(data), 0o644, installed
            db.addfile(info, io.BytesIO(data))
    with open(os.path.join(repo, "droiddeck.db"), "wb") as out:
        out.write(buffer.getvalue())

    share = os.path.join(root, "usr/share/droiddeck/pacman")
    os.makedirs(os.path.join(share, "hooks"), exist_ok=True)
    with open(os.path.join(share, "droiddeck.db"), "wb") as out:
        out.write(buffer.getvalue())
    with open(os.path.join(share, "system-packages.txt"), "w") as out:
        out.write("".join(n + "\n" for n in sorted(system)))
    with open(os.path.join(share, "desktop-packages.txt"), "w") as out:
        out.write("".join(f"{n} {entries[n][1]['VERSION'][0]}\n" for n in sorted(shipped)))
    with open(os.path.join(share, "hooks", "droiddeck-system.hook"), "w") as out:
        out.write("[Trigger]\nOperation = Upgrade\nOperation = Remove\nType = Package\n")
        out.write("".join(f"Target = {n}\n" for n in sorted(system)))
        out.write("\n[Action]\nDescription = Keeping the packages DroidDeck installed...\nWhen = PreTransaction\n"
                  "Exec = /usr/share/droiddeck/pacman/refuse\nNeedsTargets\nAbortOnFail\n")
    server = f"{RELEASES}/{tag}" if tag else f"{RELEASES}/unpublished"
    template = open(os.path.join(here, "pacman.conf.in")).read()
    with open(os.path.join(share, "pacman.conf"), "w") as out:
        out.write(template.replace("@SERVER@", server).replace("@ALARM@", ALARM))
    print(f"pacman: repository {server}, {len(available)} packages ({len(system)} installed by DroidDeck)")


if __name__ == "__main__":
    main()
