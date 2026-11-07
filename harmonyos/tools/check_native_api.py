#!/usr/bin/env python3
"""Checks the native module's declarations against the module itself.

`entry/src/main/cpp/types/libdroiddeck_native/index.d.ts` is the contract
between the ArkTS shell and `libdroiddeck_native.so`. Nothing in the build
connects the two, so a function that is renamed on one side and not the
other would compile here and then fail at runtime on the device, as
`undefined is not a function`.

This tool compares three things:

* the functions the native module registers, read from the property
  descriptor tables in the C++ sources;
* the functions the declaration file declares;
* the strings actually present in the built library, which is the ground
  truth for what the module exports.

Usage: tools/check_native_api.py [--library <path to libdroiddeck_native.so>]
"""

import pathlib
import re
import sys

PROJECT = pathlib.Path(__file__).resolve().parent.parent
CPP = PROJECT / 'entry/src/main/cpp'
TYPES = CPP / 'types/libdroiddeck_native/index.d.ts'
DEFAULT_LIBRARY = PROJECT / 'entry/libs/arm64-v8a/libdroiddeck_native.so'

# The property descriptor tables of the two modules that register
# functions: napi_init.cpp and the compositor's own entry point.
SOURCES = (CPP / 'napi_init.cpp', CPP / 'compositor/compositor_ohos.c')

# The property descriptors are written with `nullptr` in napi_init.cpp and
# with `NULL` in the compositor's C entry point.
DESCRIPTOR = re.compile(r'\{"([A-Za-z0-9_]+)",\s*(?:nullptr|NULL),')
DECLARED = re.compile(r'^export\s+const\s+([A-Za-z0-9_]+)\s*:', re.MULTILINE)


def main() -> int:
    library = DEFAULT_LIBRARY
    if '--library' in sys.argv:
        library = pathlib.Path(sys.argv[sys.argv.index('--library') + 1])

    failures = 0

    exported = set()
    for source in SOURCES:
        if not source.exists():
            print(f'FAIL: {source} is missing')
            failures += 1
            continue
        exported.update(DESCRIPTOR.findall(source.read_text()))

    if not TYPES.exists():
        print(f'FAIL: the declarations are missing: {TYPES}')
        return 1
    declared = set(DECLARED.findall(TYPES.read_text()))

    print(f'==> the native module registers {len(exported)} functions')
    print(f'==> the declarations describe {len(declared)} functions')

    missing = sorted(exported - declared)
    if missing:
        print('FAIL: these are registered but not declared, so ArkTS cannot call them:')
        for name in missing:
            print(f'    {name}')
        failures += len(missing)

    extra = sorted(declared - exported)
    if extra:
        print('FAIL: these are declared but not registered, so calling them throws:')
        for name in extra:
            print(f'    {name}')
        failures += len(extra)

    if library.exists():
        strings = subprocess_strings(library)
        for name in sorted(exported):
            if name not in strings:
                print(f'FAIL: {name} is not present in {library.name}')
                failures += 1
        print(f'==> every registered name is present in {library.name}')
    else:
        print(f'note: {library} is not built, so the library was not checked')

    if failures:
        print(f'{failures} problem(s) found between the module and its declarations.')
        return 1
    print('==> the declarations match the native module')
    return 0


def subprocess_strings(path: pathlib.Path) -> set:
    """The printable strings of a shared library, as a set."""
    import subprocess
    result = subprocess.run(['strings', '-n', '4', str(path)],
                            capture_output=True, text=True)
    return set(result.stdout.split())


if __name__ == '__main__':
    sys.exit(main())
