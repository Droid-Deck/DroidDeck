#!/usr/bin/python3
"""Select a staged DXVK payload for one DroidDeck Proton game launch.

Place this script beside dxvk/x32/ and dxvk/x64/, then select its absolute
path with DROIDDECK_PROTON_WRAPPER in the game's environment settings.
Run `python3 run-proton.py --check /path/to/proton` to check the payload and
Proton source layout without starting Proton or changing a game prefix.

This wrapper does not install the graphics runtime, select Vulkan layers,
change synchronization settings, or provide game-specific workarounds.
"""

import argparse
import os
from pathlib import Path
import sys


DLL_NAMES = ("d3d8.dll", "d3d9.dll", "d3d10core.dll", "d3d11.dll", "dxgi.dll")
PREFIX_MARKER = "droiddeck-dxvk-profile-v1"


def prepare(proton, dxvk):
    """Validate all inputs and compile an in-memory copy of the Proton script."""
    for architecture in ("x32", "x64"):
        for name in DLL_NAMES:
            dll = dxvk / architecture / name
            if not dll.is_file():
                raise ValueError(f"missing DXVK file: {dll}")
            with dll.open("rb") as stream:
                if not stream.read(1):
                    raise ValueError(f"empty DXVK file: {dll}")

    source = proton.read_text(encoding="utf-8")
    for is32, architecture in (("False", "x64"), ("True", "x32")):
        needle = f'g_proton.arch_pe_dir("wine/dxvk", {is32}) + f + ".dll"'
        if source.count(needle) != 1:
            raise ValueError(f"unsupported Proton layout: expected one {architecture} DXVK copy expression")
        directory = str(dxvk / architecture) + "/"
        source = source.replace(needle, repr(directory) + ' + f + ".dll"', 1)

    needle = "            # check whether any prefix config has changed"
    if source.count(needle) != 1:
        raise ValueError("unsupported Proton layout: expected one prefix configuration marker")
    # The supported Proton copies DXVK DLLs on every launch. This marker records
    # profile activation; it does not need changing whenever the payload changes.
    marker = repr("\n" + PREFIX_MARKER)
    source = source.replace(needle, f"            prefix_info += {marker}\n" + needle, 1)
    return compile(source, str(proton), "exec")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="validate inputs without running Proton")
    parser.add_argument("proton", help="selected Proton Python script")
    parser.add_argument("proton_args", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if not args.check and (len(args.proton_args) < 2 or args.proton_args[0] != "waitforexitandrun"):
        parser.error("expected: PROTON waitforexitandrun GAME [ARGUMENTS...]")

    proton = Path(args.proton)
    # A sibling payload directory by name (dxvk-2.6.2 for Xclipse); never a path.
    name = os.environ.get("DROIDDECK_HYBRIS_DXVK") or "dxvk"
    if not name.startswith("dxvk") or "/" in name or name.startswith("."):
        print(f"DroidDeck DXVK profile: invalid payload name {name!r}", file=sys.stderr)
        return 1
    dxvk = Path(__file__).resolve().parent / name
    try:
        code = prepare(proton, dxvk)
    except (OSError, ValueError, SyntaxError) as error:
        print(f"DroidDeck DXVK profile: {error}", file=sys.stderr)
        return 1

    if args.check:
        print(f"DroidDeck DXVK profile: payload present and Proton layout supported ({dxvk})")
        return 0

    print(f"DROIDDECK_GRAPHICS profile={PREFIX_MARKER} dxvk={dxvk}", flush=True)
    # Preserve the selected Proton path, including symlinks, and its arguments.
    # Proton uses argv[0] and sibling Python imports to locate its own depot.
    sys.argv = [args.proton, *args.proton_args]
    sys.path.insert(0, str(proton.absolute().parent))
    exec(code, {"__name__": "__main__", "__file__": args.proton,
                "__package__": None, "__spec__": None})
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
