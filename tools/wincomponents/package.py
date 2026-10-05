#!/usr/bin/env python3
"""Builds the Windows component packages and their catalog (wincomponents.json).

Each package is a .tzst that extracts over the runtime's root like a desktop package:
opt/droiddeck/wincomponents/<id>/ with component.json and the DLLs in system32/ (64-bit) and
syswow64/ (32-bit), which droiddeck-wincomponents copies into a game's prefix at launch.

    package.py <bannerlator wincomponents dir> <openal-soft-X-bin.zip> <out dir> <release url>

The eight Bannerlator components are its own archives (assets/wincomponents) re-rooted; their
overrides are Bannerlator's wincomponents.json. OpenAL is OpenAL Soft's release: soft_oal.dll
placed as OpenAL32.dll, which is how the project says to install it system-wide.
"""
import hashlib
import io
import json
import subprocess
import sys
import tarfile
import zipfile
from pathlib import Path

REV = "r1"
# id: (name, notes) - the app shows these.
COMPONENTS = {
    "openal": ("OpenAL", "OpenAL Soft %s, for games that use OpenAL32.dll for sound."),
    "direct3d": ("DirectX (d3dx9/10/11)", "Microsoft's D3DX and D3DCompiler helper DLLs that older DirectX games ship an installer for."),
    "xaudio": ("XAudio2 / XACT", "Microsoft's XAudio2, X3DAudio, XAPOFX and XACT audio DLLs."),
    "directsound": ("DirectSound", "Microsoft's dsound.dll instead of Wine's."),
    "directmusic": ("DirectMusic", "Microsoft's DirectMusic DLLs, for games with music in .sgt/.dls files."),
    "directshow": ("DirectShow", "Microsoft's DirectShow DLLs (quartz, amstream...), for some intro videos."),
    "directplay": ("DirectPlay", "Microsoft's DirectPlay, for old LAN/online multiplayer."),
    "ddraw": ("DirectDraw (32-bit)", "A replacement ddraw.dll for old 2D games."),
    "vcrun2010": ("Visual C++ 2010", "The Visual C++ 2010 runtime (msvcr100, msvcp100)."),
}


def read_zst(path):
    return subprocess.run(["zstd", "-dc", str(path)], check=True, capture_output=True).stdout


def add(tar, name, data, mode=0o644):
    info = tarfile.TarInfo(name)
    info.size = len(data)
    info.mode = mode
    info.mtime = 1790000000
    tar.addfile(info, io.BytesIO(data))


def build(cid, overrides, files, out, version):
    """files: {"system32/x.dll": bytes}. Writes <out>/wincomponent-<id>-<rev>.tzst, returns its path."""
    base = "opt/droiddeck/wincomponents/%s/" % cid
    raw = io.BytesIO()
    with tarfile.open(fileobj=raw, mode="w", format=tarfile.GNU_FORMAT) as tar:
        meta = {"id": cid, "rev": REV, "version": version, "overrides": overrides}
        add(tar, base + "component.json", json.dumps(meta, indent=1).encode())
        for rel in sorted(files):
            add(tar, base + rel, files[rel])
    target = out / ("wincomponent-%s-%s.tzst" % (cid, REV))
    subprocess.run(["zstd", "-19", "-q", "-f", "-o", str(target)], input=raw.getvalue(), check=True)
    return target


def bannerlator(src, cid):
    files = {}
    with tarfile.open(fileobj=io.BytesIO(read_zst(src / (cid + ".tzst")))) as tar:
        for member in tar.getmembers():
            if not member.isfile():
                continue
            arch, _, name = member.name.lstrip("./").partition("/")
            if arch not in ("system32", "syswow64") or "/" in name:
                raise SystemExit("%s: unexpected %s" % (cid, member.name))
            files[arch + "/" + name.lower()] = tar.extractfile(member).read()
    return files


def openal(zip_path):
    with zipfile.ZipFile(zip_path) as z:
        top = z.namelist()[0].split("/")[0]
        version = top.replace("openal-soft-", "").replace("-bin", "")
        files = {
            "syswow64/openal32.dll": z.read(top + "/bin/Win32/soft_oal.dll"),
            "system32/openal32.dll": z.read(top + "/bin/Win64/soft_oal.dll"),
            "syswow64/soft_oal.dll": z.read(top + "/bin/Win32/soft_oal.dll"),
            "system32/soft_oal.dll": z.read(top + "/bin/Win64/soft_oal.dll"),
        }
    return files, version


def main():
    src, zip_path, out, url = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3]), sys.argv[4].rstrip("/")
    out.mkdir(parents=True, exist_ok=True)
    overrides = json.loads((src / "wincomponents.json").read_text())
    packages = []
    for cid, (name, notes) in COMPONENTS.items():
        if cid == "openal":
            files, version = openal(zip_path)
            names, notes = ["openal32", "soft_oal"], notes % version
        else:
            files, version = bannerlator(src, cid), "bannerlator"
            # ddraw has no row in Bannerlator's list: it is the one DLL it ships.
            names = overrides.get(cid, ["ddraw"])
        missing = {n.lower() for n in names if not n.endswith(".exe")} - {Path(f).stem for f in files}
        if missing:
            raise SystemExit("%s: overrides without a file: %s" % (cid, sorted(missing)))
        target = build(cid, names, files, out, version)
        data = target.read_bytes()
        packages.append({
            "id": cid, "name": name, "version": "%s-%s" % (version, REV), "kind": "tar", "notes": notes,
            "url": "%s/%s" % (url, target.name), "sha256": hashlib.sha256(data).hexdigest(), "size": len(data),
        })
        print("%-12s %9d bytes  %d files" % (cid, len(data), len(files)))
    (out / "wincomponents.json").write_text(json.dumps({"version": 1, "packages": packages}, indent=2) + "\n")


if __name__ == "__main__":
    main()
