#!/usr/bin/env python3
"""Checks the port's ArkTS sources against the OpenHarmony 7.0 SDK.

Two layers, because the ArkUI declarative DSL is not valid TypeScript:

1. A real semantic type check of the plain-TypeScript modules (common,
   model, native, service, state, entryability) using the SDK's own
   TypeScript compiler, with the SDK's declarations as ambient types.
   The two ArkTS-only constructs are rewritten for the check:
   `struct Foo` becomes `class Foo`, and the SDK's `export @interface Foo`
   becomes `export interface Foo`.

2. Structural checks for the ArkUI files (components, pages): balanced
   delimiters, imports that resolve to real files, and resource
   references that resolve to the string and colour tables.

Run with --selftest to prove layer 1 really inspects the sources before
trusting a clean result.
"""

import json
import os
import pathlib
import re
import shutil
import subprocess
import sys

# The SDK and the project root are both configurable, so the port can be
# checked out anywhere: OHOS_SDK_HOME points at the OpenHarmony SDK and the
# project is always the directory this script lives in.
SDK = pathlib.Path(os.environ.get('OHOS_SDK_HOME', '/Volumes/Download-1/ohos-sdk/sdk'))
PROJECT = pathlib.Path(__file__).resolve().parent.parent
WORK = pathlib.Path('/tmp/arkts-check')
TSC = SDK / 'ets/build-tools/ets-loader/node_modules/typescript/lib/tsc.js'

# ArkTS decorators and globals that the ArkTS compiler injects.
AMBIENT = '''
// ArkTS decorators injected by the ArkTS compiler.
declare function Component(target: Object): void;
declare function Entry(target: Object): void;
declare function Builder(target: Object): void;
declare function Observed(target: Object): void;
declare function State(target: Object, property: string): void;
declare function Prop(target: Object, property: string): void;
declare function Link(target: Object, property: string): void;
declare function ObjectLink(target: Object, property: string): void;
declare function Provide(target: Object, property: string): void;
declare function Consume(target: Object, property: string): void;
declare function Require(target: Object, property: string): void;
declare function Param(target: Object, property: string): void;
declare function Once(target: Object, property: string): void;
declare function Event(target: Object, property: string): void;
declare function Watch(target: Object, property: string): void;
declare function Reusable(target: Object): void;
declare function Concurrent(target: Object): void;
declare function CustomDialog(target: Object): void;
declare function Sendable(): ClassDecorator;
declare function ObservedV2(target: Object): void;
declare function Trace(target: Object, property: string): void;
declare function Monitor(target: Object, property: string): void;
declare function Local(target: Object, property: string): void;
declare function Computed(target: Object, property: string): void;

// The Node-API module the native layer builds is declared in
// entry/src/main/cpp/types/libdroiddeck_native/index.d.ts and resolved
// through the compiler's path mapping, so the native calls are checked
// against the real signatures rather than against `any`.

// The SDK exposes these as ambient globals. Plain TypeScript sees them as
// module exports instead, so they are declared here for the check.
declare type Want = Object;
declare namespace AbilityConstant {
  type LaunchParam = Object;
}

// The ArkUI component event globals.
//
// The SDK ships these in ets/component/common.d.ts and
// ets/component/enums.d.ts, which plain TypeScript does not always resolve
// the way the ArkTS compiler does. Declaring the handful the port uses
// keeps this check faithful to the real build.
//
// The enums themselves live in a .ts file, see ENUMS below: an ambient enum
// is type-only in plain TypeScript, while the ArkTS compiler keeps its
// members accessible as values.
declare interface TouchObject {
  type: TouchType;
  id: number;
  x: number;
  y: number;
  displayX: number;
  displayY: number;
  windowX: number;
  windowY: number;
}
declare interface TouchEvent {
  type: TouchType;
  touches: TouchObject[];
  changedTouches: TouchObject[];
}
declare interface KeyEvent {
  type: KeyType;
  keyCode: number;
  keyText: string;
}

// The error type every HarmonyOS API rejects with.
declare class BusinessError extends Error {
  code: number;
  message: string;
}

// The ArkUI resource globals and the timers the ArkTS runtime provides.
declare type Resource = string | number | boolean | Object | ArrayBuffer | Resource[];
declare function $r(param: string): Resource;
declare function setTimeout(handler: () => void, timeout?: number): number;
declare function clearTimeout(handle?: number): void;
declare function setInterval(handler: () => void, timeout?: number): number;
declare function clearInterval(handle?: number): void;
'''

# The ArkUI event enums.
#
# They are written to a .ts file, not to the .d.ts above, because an ambient
# enum is type-only in plain TypeScript: `TouchType.Down` would not compile.
# A real enum in a .ts file carries both the type and the value, the way the
# ArkTS compiler sees it, and the port's sources use the members as values.
ENUMS = '''
// The ArkUI component event enums.
enum TouchType { Down, Up, Move, Cancel }
enum KeyType { Down, Up }
'''

# The ArkUI files use the declarative DSL, which plain TypeScript cannot
# parse, so they are checked structurally instead of semantically.
DSL_DIRECTORIES = ('components', 'pages')


def to_typescript(text: str, is_sdk: bool) -> str:
    """Rewrites the two ArkTS-only constructs into plain TypeScript."""
    if is_sdk:
        # export @interface Foo { ... }  ->  export interface Foo { ... }
        return re.sub(r'@interface\b', 'interface', text)
    # export struct Foo { ... }  ->  export class Foo { ... }
    return re.sub(r'\bstruct\s+([A-Za-z_]\w*)', r'class \1', text)


def stage_sources() -> pathlib.Path:
    src_root = WORK / 'src'
    if src_root.exists():
        shutil.rmtree(src_root)
    ets_root = PROJECT / 'entry/src/main/ets'
    for source in ets_root.rglob('*.ets'):
        relative = source.relative_to(ets_root)
        target = src_root / relative.with_suffix('.ts')
        target.parent.mkdir(parents=True, exist_ok=True)
        text = to_typescript(source.read_text(), is_sdk=False)
        # The ArkTS compiler resolves a Node-API module through the
        # module's oh-package.json5 and checks the calls against the
        # declared signatures. Plain TypeScript does not know about that
        # mechanism, and downgrades an unverified native module to `any`,
        # so the import is pointed at the declarations directly. That way
        # the native calls are checked here as well.
        prefix = '../' * len(relative.parts)
        text = text.replace("'libdroiddeck_native.so'", f"'{prefix}types/libdroiddeck_native'")
        target.write_text(text)

    # The unit tests are checked too: they are the port's own tests, and a
    # test that cannot compile is not a test.
    test_root = PROJECT / 'entry/src/test'
    if test_root.is_dir():
        for source in test_root.rglob('*.ets'):
            relative = source.relative_to(test_root)
            target = src_root / 'test' / relative.with_suffix('.ts')
            target.parent.mkdir(parents=True, exist_ok=True)
            prefix = '../' * (len(relative.parts) + 1)
            text = to_typescript(source.read_text(), is_sdk=False)
            text = text.replace("'libdroiddeck_native.so'", f"'{prefix}types/libdroiddeck_native'")
            # The staged tree flattens main/ets into the source root, so
            # the tests' imports of '../../main/ets/...' become '../...'.
            text = text.replace("'../../main/ets/", "'../")
            target.write_text(text)
    return src_root


def stage_native_types() -> pathlib.Path:
    """Copies the native module's Node-API declarations.

    The declarations are the contract between the ArkTS shell and
    libdroiddeck_native.so, so the check resolves the module to them
    instead of accepting the import as an untyped bag of properties.
    """
    types_root = WORK / 'types'
    if types_root.exists():
        shutil.rmtree(types_root)
    source_root = PROJECT / 'entry/src/main/cpp/types'
    if not source_root.exists():
        return types_root
    for source in source_root.rglob('*.d.ts'):
        relative = source.relative_to(source_root)
        target = types_root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    return types_root


def stage_sdk() -> None:
    """Copies the SDK declarations, rewriting the .d.ets files that plain
    TypeScript cannot parse."""
    sdk_root = WORK / 'sdk'
    if sdk_root.exists():
        shutil.rmtree(sdk_root)

    for area in ('api', 'kits', 'component', 'arkts'):
        source_root = SDK / 'ets' / area
        target_root = sdk_root / area
        target_root.mkdir(parents=True, exist_ok=True)
        if not source_root.exists():
            continue
        for source in source_root.rglob('*'):
            if not source.is_file():
                continue
            relative = source.relative_to(source_root)
            if source.suffix == '.d.ets':
                target = target_root / relative.with_suffix('.d.ts')
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(to_typescript(source.read_text(), is_sdk=True))
            elif source.suffix in ('.d.ts', '.ts'):
                target = target_root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, target)


def write_config() -> pathlib.Path:
    (WORK / 'globals.d.ts').write_text(AMBIENT)
    (WORK / 'arkui-enums.ts').write_text(ENUMS)
    sdk = str(WORK / 'sdk')
    config = {
        'compilerOptions': {
            'target': 'ES2021',
            # Without this, TypeScript adds the DOM library, and the
            # browser's TouchEvent and KeyEvent shadow the ArkUI ones: the
            # port's `event.touches` would be a TouchList and `event.type` a
            # string. HarmonyOS has no DOM, so the check must not have one.
            'lib': ['ES2021'],
            'module': 'ES2020',
            'moduleResolution': 'node',
            'experimentalDecorators': True,
            'emitDecoratorMetadata': False,
            'noEmit': True,
            'skipLibCheck': True,
            'strict': False,
            'noImplicitAny': False,
            'resolveJsonModule': True,
            'baseUrl': str(WORK),
            'typeRoots': [],
            'types': [],
            # The native module is imported as a default import, the way
            # the ArkTS compiler resolves a Node-API module.
            'allowSyntheticDefaultImports': True,
            'paths': {
                '@kit.*': [sdk + '/kits/@kit.*'],
                '@ohos.*': [sdk + '/api/@ohos.*'],
                '@internal.*': [sdk + '/api/@internal.*'],
                # The native module's own declarations.
                'libdroiddeck_native.so': [str(WORK / 'types/libdroiddeck_native/index.d.ts')],
                # The test framework, from the SDK's own copy.
                '@ohos/hypium': [
                    str(SDK / 'ets/build-tools/ets-loader/node_modules/@ohos/hypium/index.d.ts')
                ],
            },
        },
        'include': [
            'src/**/*.ts',
            'globals.d.ts',
            'arkui-enums.ts',
            'sdk/**/*.d.ts',
        ],
        # The ArkUI files use the declarative DSL (`Column() { ... }`), which
        # is not valid TypeScript; only the ArkTS compiler understands it.
        # They are checked structurally instead, by check_ui_sources().
        #
        # The SDK's own component declarations are excluded as well: they
        # declare the same event globals as the ambient block above, and
        # plain TypeScript merges the two into a type-only symbol.
        'exclude': [
            f'src/{name}/**' for name in DSL_DIRECTORIES
        ] + ['sdk/component/**'],
    }
    path = WORK / 'tsconfig.json'
    path.write_text(json.dumps(config, indent=2))
    return path


def run_check(config: pathlib.Path, strict: bool = False) -> int:
    result = subprocess.run(
        ['node', str(TSC), '-p', str(config), '--noEmit'],
        capture_output=True, text=True)

    output = (result.stdout or '') + (result.stderr or '')

    # tsc prints paths relative to the working directory, so match on the
    # staging directory names rather than a fixed prefix.
    ours = []
    sdk = []
    other = []
    for line in output.splitlines():
        if not re.match(r'^[^(]+\(\d+,\d+\): (error|warning)', line):
            continue
        path = line.split('(', 1)[0]
        if 'arkts-check/src/' in path:
            ours.append(line)
        elif 'arkts-check/sdk/' in path:
            sdk.append(line)
        else:
            other.append(line)

    # An error means the port does not compile; a warning means it compiles
    # but something is worth looking at. Only an error fails the check by
    # default, and --strict makes warnings fail too.
    errors = [line for line in ours if ': error' in line]
    warnings = [line for line in ours if ': warning' in line]

    print(f'=== errors in the port sources: {len(errors)} ===')
    for line in errors[:200]:
        print(line)
    print(f'=== warnings in the port sources: {len(warnings)} ===')
    for line in warnings[:200]:
        print(line)
    print(f'=== SDK declaration diagnostics (ignored): {len(sdk)} ===')
    if other:
        print(f'=== other diagnostics: {len(other)} ===')
        for line in other[:20]:
            print(line)

    if errors:
        return 1
    if strict and warnings:
        return 1
    return 0


MEMBER_KEYWORDS = {
    'if', 'for', 'while', 'switch', 'catch', 'return', 'throw', 'new', 'await',
    'function', 'class', 'struct', 'interface', 'enum', 'import', 'export',
    'const', 'let', 'var', 'type', 'else', 'do', 'try', 'case', 'break',
    'continue', 'default', 'finally', 'yield', 'void', 'delete', 'typeof',
    'instanceof', 'in', 'of', 'extends', 'implements', 'super', 'this',
    'constructor', 'get', 'set', 'static', 'public', 'private', 'protected',
    'readonly', 'async', 'override', 'declare', 'from', 'as', 'is', 'in',
}

MEMBER_PATTERN = re.compile(
    r"^(?:@\w+\s+)*"
    r"(?:(?:public|private|protected|static|readonly|async|get|set|override|declare)\s+)*"
    r"([A-Za-z_][A-Za-z0-9_]*)\s*(?:\(|=|<|:)"
)


def duplicate_members(text: str, label: str) -> list[str]:
    """Members a class or struct declares twice.

    The ArkUI DSL cannot be type checked without the ArkTS compiler, so a
    duplicated member is a build error that only shows up when the app is
    compiled. This is the one mistake that has already slipped through the
    structural checks, so they look for it now.
    """
    problems = []
    for declaration in re.finditer(r"\b(?:struct|class)\s+([A-Za-z_][A-Za-z0-9_]*)", text):
        brace = text.find('{', declaration.end())
        if brace < 0:
            continue
        depth = 0
        end = len(text)
        for index in range(brace, len(text)):
            if text[index] == '{':
                depth += 1
            elif text[index] == '}':
                depth -= 1
                if depth == 0:
                    end = index
                    break
        # The body starts after the opening brace, so that the members sit
        # at depth one.
        body = text[brace + 1:end]

        seen: dict[str, int] = {}
        depth = 1
        for line in body.splitlines():
            if depth == 1:
                candidate = MEMBER_PATTERN.match(line.strip())
                if candidate and candidate.group(1) not in MEMBER_KEYWORDS:
                    member = candidate.group(1)
                    seen[member] = seen.get(member, 0) + 1
            depth += line.count('{') - line.count('}')
        for member, count in sorted(seen.items()):
            if count > 1:
                problems.append(
                    f'{label}: {declaration.group(1)} declares {member} {count} times')
    return problems


def check_ui_sources() -> int:
    """Structural checks for the ArkUI files."""
    problems = []
    source_root = PROJECT / 'entry/src/main/ets'
    resource_root = PROJECT / 'entry/src/main/resources/base/element'

    strings = set()
    colors = set()
    for name, bucket in (('string.json', strings), ('color.json', colors)):
        path = resource_root / name
        if path.exists():
            data = json.loads(path.read_text())
            key = 'string' if 'string' in data else 'color'
            for entry in data[key]:
                bucket.add(entry['name'])

    for source in sorted(source_root.rglob('*.ets')):
        text = source.read_text()

        # A member declared twice is a build error the ArkTS compiler would
        # report and the structural checks would not.
        problems.extend(duplicate_members(text, source.name))

        # Balanced delimiters, ignoring strings, comments and regex literals.
        depth = {'{': 0, '(': 0, '[': 0}
        pairs = {'}': '{', ')': '(', ']': '['}
        index = 0
        in_string = None
        in_block_comment = False
        previous = ''
        while index < len(text):
            char = text[index]
            if in_block_comment:
                if text.startswith('*/', index):
                    in_block_comment = False
                    index += 2
                    continue
                index += 1
                continue
            if in_string:
                if char == '\\':
                    index += 2
                    continue
                if char == in_string:
                    in_string = None
                index += 1
                continue
            if text.startswith('/*', index):
                in_block_comment = True
                index += 2
                continue
            if char in ('"', "'", '`'):
                in_string = char
                index += 1
                continue
            if char == '/' and index + 1 < len(text) and text[index + 1] == '/':
                while index < len(text) and text[index] != '\n':
                    index += 1
                continue
            if char == '/' and (previous == '' or previous in '(,=:[!&|?{}+-*%^~<>;'):
                # A regex literal, not a division.
                index += 1
                in_regex = True
                while index < len(text):
                    if text[index] == '\\':
                        index += 2
                        continue
                    if text[index] == '[':
                        while index < len(text) and text[index] != ']':
                            index += 1
                    elif text[index] == '/':
                        break
                    index += 1
                index += 1
                previous = '/'
                continue
            if char in depth:
                depth[char] += 1
            elif char in pairs:
                depth[pairs[char]] -= 1
                if depth[pairs[char]] < 0:
                    problems.append(f'{source.name}: unbalanced {char}')
                    depth[pairs[char]] = 0
            if not char.isspace():
                previous = char
            index += 1
        for opener, count in depth.items():
            if count != 0:
                problems.append(f'{source.name}: {count} unclosed {opener}')

        # Imports must point at files that exist.
        for match in re.finditer(r"from\s+'([^']+)'", text):
            target = match.group(1)
            if target.startswith('.'):
                resolved = (source.parent / target)
                if not resolved.with_suffix('.ets').exists() and not resolved.exists():
                    problems.append(f'{source.name}: import not found: {target}')

        # Resource references must resolve.
        for match in re.finditer(r"\$r\('app\.string\.([A-Za-z0-9_]+)'\)", text):
            if match.group(1) not in strings:
                problems.append(f'{source.name}: unknown string {match.group(1)}')
        for match in re.finditer(r"\$r\('app\.color\.([A-Za-z0-9_]+)'\)", text):
            if match.group(1) not in colors:
                problems.append(f'{source.name}: unknown colour {match.group(1)}')

    print(f'=== UI source problems: {len(problems)} ===')
    for problem in problems:
        print(problem)
    return 0 if not problems else 1


def main() -> int:
    if not TSC.exists():
        print(f'tsc not found at {TSC}')
        return 2

    strict = '--strict' in sys.argv

    WORK.mkdir(parents=True, exist_ok=True)
    src_root = stage_sources()
    stage_native_types()
    stage_sdk()
    config = write_config()

    if '--selftest' in sys.argv:
        # Prove the check actually looks at the port's sources before
        # trusting a clean result.
        target = src_root / 'model/DeviceInfo.ts'
        original = target.read_text()
        target.write_text(original + '\nconst selftest: number = "not a number";\n')
        try:
            found = run_check(config)
        finally:
            target.write_text(original)
        if found == 0:
            print('selftest FAILED: the injected error was not detected')
            return 1
        print('selftest OK: the injected error was detected')
        return 0

    status = run_check(config, strict=strict)
    ui_status = check_ui_sources()
    return status if status else ui_status


if __name__ == '__main__':
    sys.exit(main())
