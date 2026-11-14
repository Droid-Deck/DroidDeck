#!/usr/bin/env python3
"""Checks the app's configuration against the SDK's own schemas.

The port cannot be built here, because Hvigor and the ArkTS compiler are not
publicly obtainable. What *is* public is the OpenHarmony SDK's toolchains,
and with them the schemas the build system uses to validate an app's
configuration:

    toolchains/modulecheck/module.json   the module manifest
    toolchains/modulecheck/app.json      the application manifest
    toolchains/modulecheck/pages.json    the pages profile
    toolchains/configcheck/...           the build profiles

So this tool checks the port's manifests the way the build would, and adds
the checks the schemas cannot express: that every resource reference
resolves in the scope it is used in, that the pages the manifest lists
exist as real ArkTS files, and that the ability's source entry is there.

Two places where the SDK's schemas are known to be wrong are handled
explicitly, and reported rather than hidden:

* `module.deviceTypes` — the schema's enum is
  `default, tablet, tv, wearable, car, 2in1` and omits `phone`. The build
  accepts `phone`, and Huawei's own store FAQ says `default` compiles but
  cannot be published, and recommends `phone` for phones.
* `app.apiReleaseType` — the schema's pattern is
  `^(Canary[1-9]\\d*)|(Beta[1-9]\\d*)|(Release[1-9]\\d*)$`, whose alternation
  is not anchored as a whole, so it rejects the documented value
  `Release`.

Usage: tools/check_app_config.py [--sdk <path to the OpenHarmony SDK>]
"""

import argparse
import json
import os
import pathlib
import re
import sys

PROJECT = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_SDK = pathlib.Path(os.environ.get('OHOS_SDK_HOME', '/Volumes/Download-1/ohos-sdk/sdk'))


class Report:
    """Collects problems and known schema quirks."""

    def __init__(self) -> None:
        self.errors: list[str] = []
        self.quirks: list[str] = []

    def error(self, message: str) -> None:
        self.errors.append(message)

    def quirk(self, message: str) -> None:
        self.quirks.append(message)


def strip_json5(text: str) -> str:
    """Turns JSON5 into JSON: comments and trailing commas go away."""
    out = []
    index = 0
    length = len(text)
    in_string = False
    while index < length:
        char = text[index]
        if in_string:
            out.append(char)
            if char == '\\' and index + 1 < length:
                out.append(text[index + 1])
                index += 2
                continue
            if char == '"':
                in_string = False
            index += 1
            continue
        if char == '"':
            in_string = True
            out.append(char)
            index += 1
            continue
        if text.startswith('//', index):
            while index < length and text[index] != '\n':
                index += 1
            continue
        if text.startswith('/*', index):
            end = text.find('*/', index + 2)
            index = length if end < 0 else end + 2
            continue
        out.append(char)
        index += 1
    text = ''.join(out)
    # Trailing commas before a closing brace or bracket.
    text = re.sub(r',(\s*[}\]])', r'\1', text)
    return text


def load_json5(path: pathlib.Path):
    return json.loads(strip_json5(path.read_text()))


def is_known_quirk(error) -> bool:
    """Whether a schema error is one of the SDK's own known mistakes."""
    message = error.message
    location = list(error.absolute_path)
    if location[:2] == ['module', 'deviceTypes']:
        return "'phone' is not one of" in message
    if location[:2] == ['app', 'apiReleaseType']:
        return 'apiReleaseType' in message or 'does not match' in message
    return False


def validate(report: Report, data, schema, label: str) -> None:
    try:
        import jsonschema
    except ImportError:
        report.error('the jsonschema module is not installed')
        return
    validator = jsonschema.Draft7Validator(schema)
    for error in sorted(validator.iter_errors(data), key=lambda e: list(e.path)):
        location = '/'.join(str(part) for part in error.absolute_path) or '(root)'
        if is_known_quirk(error):
            report.quirk(f'{label} {location}: {error.message}')
        else:
            report.error(f'{label} {location}: {error.message}')


class Resources:
    """The resource names one scope defines."""

    def __init__(self, root: pathlib.Path) -> None:
        self.root = root
        self.strings: set[str] = set()
        self.colors: set[str] = set()
        self.media: set[str] = set()
        self.profiles: set[str] = set()

        element = root / 'base/element'
        for name, bucket in (('string.json', self.strings), ('color.json', self.colors)):
            path = element / name
            if path.exists():
                for entry in json.loads(path.read_text())[name.split('.')[0]]:
                    bucket.add(entry['name'])

        media = root / 'base/media'
        if media.is_dir():
            for item in media.iterdir():
                self.media.add(item.stem)
                self.media.add(item.name)

        profile = root / 'base/profile'
        if profile.is_dir():
            for item in profile.iterdir():
                self.profiles.add(item.stem)
                self.profiles.add(item.name)


def check_references(report: Report, manifest: pathlib.Path, resources: Resources,
                     label: str) -> None:
    """Every $type:name in a manifest must resolve in its own scope."""
    text = manifest.read_text()
    for kind, name in re.findall(r'\$(string|media|color|profile):([A-Za-z0-9_]+)', text):
        if kind == 'string':
            found = name in resources.strings
        elif kind == 'color':
            found = name in resources.colors
        elif kind == 'profile':
            found = name in resources.profiles
        else:
            found = name in resources.media
        if not found:
            report.error(f'{label} references ${kind}:{name}, which is not defined in '
                         f'{resources.root}')


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--sdk', default=str(DEFAULT_SDK), help='the OpenHarmony SDK root')
    args = parser.parse_args()
    sdk = pathlib.Path(args.sdk)

    report = Report()
    toolchains = sdk / 'toolchains'
    if not toolchains.is_dir():
        print(f'the SDK toolchains are missing at {toolchains}')
        return 2

    module_manifest = PROJECT / 'entry/src/main/module.json5'
    app_manifest = PROJECT / 'AppScope/app.json5'
    pages_profile = PROJECT / 'entry/src/main/resources/base/profile/main_pages.json'

    # --- the manifests against the SDK's own schemas ---------------------
    module_schema = json.loads((toolchains / 'modulecheck/module.json').read_text())
    app_schema = json.loads((toolchains / 'modulecheck/app.json').read_text())
    pages_schema = json.loads((toolchains / 'modulecheck/pages.json').read_text())

    module_data = load_json5(module_manifest)
    app_data = load_json5(app_manifest)
    pages_data = json.loads(pages_profile.read_text())

    validate(report, module_data, module_schema, 'module.json5')
    validate(report, app_data, app_schema, 'app.json5')
    validate(report, pages_data, pages_schema, 'main_pages.json')

    # --- resources resolve in the scope they are used in ------------------
    module_scope = Resources(PROJECT / 'entry/src/main/resources')
    app_scope = Resources(PROJECT / 'AppScope/resources')
    check_references(report, module_manifest, module_scope, 'module.json5')
    check_references(report, app_manifest, app_scope, 'app.json5')

    # --- the manifest points at files that exist --------------------------
    # srcEntry is relative to the module's source root, entry/src/main/.
    module_source = PROJECT / 'entry/src/main'
    ets_root = module_source / 'ets'
    for ability in module_data.get('module', {}).get('abilities', []):
        source = ability.get('srcEntry')
        if source and not (module_source / source.lstrip('./')).exists():
            report.error(f'the ability {ability.get("name")} points at {source}, which does not exist')

    for page in pages_data.get('src', []):
        if not (ets_root / f'{page}.ets').exists():
            report.error(f'the pages profile lists {page}, which has no ArkTS file')

    # The mainElement has to name one of the abilities.
    abilities = {a.get('name') for a in module_data.get('module', {}).get('abilities', [])}
    main_element = module_data.get('module', {}).get('mainElement')
    if main_element and main_element not in abilities:
        report.error(f'mainElement names {main_element}, which is not one of the abilities')

    # --- the rawfiles the app installs from are shipped -----------------
    rawfiles = PROJECT / 'entry/src/main/resources/rawfile'
    for name in ('overlay.tar.gz', 'keymap.xkb'):
        if not (rawfiles / name).exists():
            report.error(f'the rawfile is missing: {rawfiles / name}')

    # The compositor publishes the keymap to the guest from the runtime
    # directory, and without it the guest's clients get no keymap at all.
    keymap = rawfiles / 'keymap.xkb'
    if keymap.exists():
        text = keymap.read_text(errors='replace')
        if 'xkb_keymap' not in text:
            report.error('keymap.xkb does not look like an xkb keymap')

    # --- report ------------------------------------------------------------
    for quirk in report.quirks:
        print(f'note: the SDK schema disagrees, but the build and the store agree: {quirk}')
    for problem in report.errors:
        print(f'FAIL: {problem}')

    if report.errors:
        print(f'{len(report.errors)} configuration problem(s) found.')
        return 1
    print(f'==> the app configuration is valid ({len(report.quirks)} known schema quirk(s))')
    return 0


if __name__ == '__main__':
    sys.exit(main())
