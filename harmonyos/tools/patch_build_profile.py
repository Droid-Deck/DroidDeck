#!/usr/bin/env python3
"""Patch the Hvigor build profiles for a command line HAP build.

The two edits the build needs are small but mechanical, and they have to be
reverted afterwards, so they live here instead of being smeared through the
shell script:

``set-sdk-version``
    Put the same SDK version into ``compileSdkVersion``,
    ``compatibleSdkVersion`` and ``targetSdkVersion``. Hvigor rejects a
    version string that does not name an installed SDK, and the exact
    spelling depends on which SDK the machine has, so the build script tries
    the candidates this project knows about.

``drop-native``
    Remove ``externalNativeOptions`` from a module's build profile. The
    native libraries are shipped prebuilt in ``entry/libs/arm64-v8a``;
    rebuilding them in place would replace the prebuilt third party
    libraries (PulseAudio, Wayland) that the HAP needs, because Hvigor
    packages that directory as it stands when it assembles the HAP.

The files are JSON5, so they are edited as text rather than parsed: the
comments and the trailing commas are part of them.
"""

import argparse
import re
import sys

SDK_KEYS = ("compileSdkVersion", "compatibleSdkVersion", "targetSdkVersion")


def set_sdk_version(text: str, version: str) -> str:
    """Give every SDK version key the same value."""
    for key in SDK_KEYS:
        pattern = re.compile(r'("%s"\s*:\s*)"[^"]*"' % key)
        text, count = pattern.subn(lambda match: match.group(1) + '"%s"' % version, text, count=1)
        if count == 0:
            text = _insert_into_first_product(text, key, version)
    return text


def _insert_into_first_product(text: str, key: str, version: str) -> str:
    """Add a missing version key to the first product of the app profile."""
    anchor = text.find('"products"')
    if anchor < 0:
        return text
    brace = text.find("{", anchor)
    if brace < 0:
        return text
    return text[: brace + 1] + '\n        "%s": "%s",' % (key, version) + text[brace + 1 :]


def drop_native(text: str):
    """Remove the ``externalNativeOptions`` block, returning the new text."""
    key = '"externalNativeOptions"'
    index = text.find(key)
    if index < 0:
        return text, False
    brace = text.find("{", index)
    if brace < 0:
        return text, False

    depth = 0
    position = brace
    in_string = False
    escaped = False
    while position < len(text):
        char = text[position]
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
        elif char == '"':
            in_string = True
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                position += 1
                break
        position += 1

    end = position
    while end < len(text) and text[end] in " \t\r\n":
        end += 1
    if end < len(text) and text[end] == ",":
        end += 1

    return text[:index] + text[end:], True


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("set-sdk-version", "drop-native"))
    parser.add_argument("path")
    parser.add_argument("value", nargs="?")
    arguments = parser.parse_args()

    with open(arguments.path, encoding="utf-8") as handle:
        text = handle.read()

    if arguments.command == "set-sdk-version":
        if not arguments.value:
            print("set-sdk-version needs a version", file=sys.stderr)
            return 2
        text = set_sdk_version(text, arguments.value)
    else:
        text, changed = drop_native(text)
        if not changed:
            print("no externalNativeOptions block in %s" % arguments.path)
            return 0

    with open(arguments.path, "w", encoding="utf-8") as handle:
        handle.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
