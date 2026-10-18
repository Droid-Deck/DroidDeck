"""Prepare the audited Windows runtime from a pinned APK and rebuilt replacements.

The donor was built from a CI merge, not just the PR head. Only unchanged
components are reused. Session helpers come from this checkout, libblsession
must be rebuilt for all three architectures, Gamescope comes from the current
checksum-pinned release, and the VR components are rebuilt in Docker (or reused
from a verified build manifest). Store engines are omitted (build with skipRust).
"""
import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import shutil
import struct
import subprocess
import sys
import tarfile
import tempfile
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools/release'))
import runtime_inputs as runtime
from check_session_assets import source_assets
sys.path.insert(0, str(ROOT / 'tools/vr'))
import build_windows as vr_build


DONOR_SHA256 = 'b7efef6442f384446bdd1750cec21f0eda61b370bc21d9f55187448e5c256760'
DONOR_PR_SOURCE = 'bdf876d47cc9bb6fa810d014370da5bbb3c28039'
DONOR_MAIN_SOURCE = '46fac69438ed428f2ca43ea935cd47790264f380'
DONOR_TREE = 'ddbca4d572b809fb969be0cf80ef83d6ef6580ce'
DONOR_REVISION = 'cf24c5f2c771633ea426046006c2b977c7bd7d32'
DONOR_CERT_SHA256 = 'b241ea7d77822d39b89d3227e06f3ecf62adec6cbfd38ad06196787991b5d3f0'
DONOR_URL = 'https://github.com/Droid-Deck/DroidDeck-CI/releases/download/pr-732/DroidDeck-PR.732-bdf876d.apk'
AUDITED_SOURCE = '1dc66bf9be64a404c80400049c0803614814461b'
LOCAL_BUILD_PLUMBING = {'app/build.gradle', 'tools/release/runtime_inputs.py'}
# These Android-only inputs are compiled by Gradle, never taken from the donor.
# Keep this list explicit so unrelated native or Linux runtime edits still fail.
LOCAL_ANDROID_SOURCES = {'app/src/main/cpp/CMakeLists.txt',
                         'app/src/main/cpp/viture/sdk_bridge.cpp',
                         'app/src/main/cpp/viture/display_modes.h',
                         'app/src/main/cpp/viture/stereo_frame_gate.h',
                         'app/src/main/cpp/viture/uvc_bridge.cpp',
                         'app/src/main/cpp/waylandcomp/src/cardboard.c',
                         'app/src/main/cpp/waylandcomp/src/cardboard.h',
                         'app/src/main/cpp/waylandcomp/src/cardboard.frag',
                         'app/src/main/cpp/waylandcomp/src/cardboard_frag.h',
                         'app/src/main/cpp/waylandcomp/src/waylandcomp_jni.c',
                         'app/src/main/cpp/waylandcomp/src/spatialpose.c',
                         'app/src/main/cpp/waylandcomp/CMakeLists.txt'}
# These audited helper updates are copied from this checkout by runtime.record().
# Permit them only while source_assets() still guarantees that staging path.
LOCAL_SESSION_HELPERS = {'tools/linuxfs/overlay/usr/local/bin/droiddeck-steam-compat',
                         'tools/linuxfs/overlay/usr/local/bin/droiddeck-steamvr'}
VR_PREFIX = 'assets/linuxfs/usr/local/lib/droiddeck-vr/'
CHECK_PATHS = list(dict.fromkeys(runtime.SOURCE_PATHS + [
    'app/src/main/cpp', 'app/src/main/jniLibs', 'app/src/main/rust/stores', 'tools/vr']))
REBUILT = {'libblsession.so': 183, 'x86_64/libblsession.so': 62, 'i386/libblsession.so': 3}
REBUILT_SHA256 = {
    'libblsession.so': '1093ce33ca6d0a17a2eec7b3a3cc53969b01ffc459aef16b320cd3857825b738',
    'x86_64/libblsession.so': 'd0a558fee1ac4d5b898170a96b03e15610d9835e7097425027b8c31728da870a',
    'i386/libblsession.so': '785d8d9bf1fa20d083a7fed65d064c7580684d7065530d95a648e2d32901d2a2',
}
JNI = ('libproot.so', 'libproot-loader.so', 'libdirectaudiorelay.so')
PREPARED = ('pulseaudio.tzst',
            'graphics_driver/adrenotools-turnip25.1.0-quest1.tzst',
            'graphics_driver/adrenotools-turnip26.3.0-a6xx.tzst',
            'graphics_driver/adrenotools-turnip26.3.0-quest2.tzst')


def git(*args):
    return subprocess.check_output(['git', '-C', str(ROOT), *args])


def changed(base, target=None):
    args = ['diff', '--no-ext-diff', '--ignore-cr-at-eol', '--name-only', '-z', base]
    if target:
        args.append(target)
    return set(filter(None, git(*args, '--', *CHECK_PATHS).decode().split('\0')))


def check_sources():
    # A branch label alone is insufficient: CI merged this main commit into it.
    if git('merge-tree', '--write-tree', DONOR_PR_SOURCE, DONOR_MAIN_SOURCE).decode().splitlines()[0] != DONOR_TREE:
        raise ValueError('The donor CI merge does not match the audited source tree')
    helper_sources = {path.relative_to(ROOT).as_posix() for path in source_assets()}
    reviewed = LOCAL_BUILD_PLUMBING | LOCAL_ANDROID_SOURCES | (LOCAL_SESSION_HELPERS & helper_sources)
    unexpected = {p for p in changed(AUDITED_SOURCE, 'HEAD') - reviewed if not vr_build.is_source(p)}
    if unexpected:
        raise ValueError('Sources changed since the component audit:\n' + '\n'.join(sorted(unexpected)))
    local = changed('HEAD')
    untracked = set(filter(None, git('ls-files', '--others', '--exclude-standard', '-z',
                                    '--', *CHECK_PATHS).decode().split('\0')))
    unexpected = {p for p in (local - reviewed) | (untracked - LOCAL_ANDROID_SOURCES)
                  if not vr_build.is_source(p)}
    if unexpected:
        raise ValueError('Unreviewed runtime/native/Rust changes:\n' + '\n'.join(sorted(unexpected)))
    classifications = {}
    for path in sorted(changed(DONOR_TREE, 'HEAD') | local | untracked):
        if vr_build.is_source(path):
            action = 'VR component rebuilt from current source and verified; never imported from donor'
        elif path in LOCAL_ANDROID_SOURCES:
            action = 'Android native source compiled by Gradle; not imported from donor'
        elif path.startswith('app/src/main/rust/stores/'):
            action = 'store engines omitted; Gradle must use skipRust=true'
        elif path in helper_sources:
            action = 'restaged from current source with LF line endings'
        elif path in {'tools/linuxfs/preload/net.c', 'tools/linuxfs/preload/webhelper.c'}:
            action = 'all three libblsession libraries rebuilt from current source'
        elif path == 'tools/gamescope/release.env':
            action = 'current checksum-pinned Gamescope archive replaces donor'
        elif path in {'tools/build_local.sh', 'tools/directaudio/fetch.sh'}:
            action = 'audited download/build cache plumbing; component inputs unchanged'
        elif path == 'app/src/main/cpp/waylandcomp/src/compositor.c':
            action = 'Android native library rebuilt by Gradle; not imported'
        elif path in LOCAL_BUILD_PLUMBING:
            action = 'local Windows build plumbing; changes recorded below'
        else:
            raise ValueError('Unclassified donor source difference: ' + path)
        classifications[path] = action
    return classifications


def validate_relative(name):
    path = PurePosixPath(name)
    if path.is_absolute() or not path.parts or any(part in {'.', '..'} or ':' in part for part in path.parts):
        raise ValueError('Unsafe archive member: ' + name)
    if '\\' in name:
        raise ValueError('Backslash in archive member: ' + name)
    return path


def checked_output(relative):
    validate_relative(relative)
    target = (ROOT / relative).resolve()
    if not target.is_relative_to(ROOT.resolve()):
        raise ValueError('Output escapes the checkout: ' + relative)
    if git('ls-files', '--', relative).strip():
        raise ValueError('Refusing to overwrite tracked file: ' + relative)
    return target


def read_gamescope(path):
    pins = dict(line.split('=', 1) for line in (ROOT / 'tools/gamescope/release.env').read_text().splitlines()
                if line and not line.startswith('#'))
    if runtime.digest(path) != pins['GAMESCOPE_SHA256']:
        raise ValueError('Gamescope archive checksum does not match tools/gamescope/release.env')
    data = subprocess.run(['zstd', '-dc', str(path)], capture_output=True, check=True).stdout
    files = {}
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        for member in archive:
            name = member.name.removeprefix('./')
            validate_relative(name)
            if member.isdir():
                continue
            if not member.isfile():
                raise ValueError('Gamescope archive contains a non-regular member: ' + name)
            if name in files:
                raise ValueError('Duplicate Gamescope archive member: ' + name)
            files[name] = archive.extractfile(member).read()
    if set(files) != {'usr/local/bin/gamescope'}:
        raise ValueError('Unexpected Gamescope archive payload: ' + ', '.join(files))
    return files, pins


def validate_rebuilt(path, machine):
    data = path.read_bytes()
    if len(data) < 20 or data[:4] != b'\x7fELF' or data[5] != 1 or struct.unpack('<H', data[18:20])[0] != machine:
        raise ValueError('Replacement has the wrong ELF architecture: ' + str(path))
    return data


def validate_tracked_assets(package, llvm_strip):
    checked = {}
    for prefix, apk_prefix in [('app/src/main/jniLibs/', 'lib/'),
                               ('app/src/main/assets/desktop-removal/', 'assets/desktop-removal/')]:
        tracked = filter(None, git('ls-files', '-z', '--', prefix).decode().split('\0'))
        for name in tracked:
            archive_name = apk_prefix + name.removeprefix(prefix)
            content = (ROOT / name).read_bytes()
            donor_content = package.read(archive_name)
            verification = 'identical bytes'
            if content != donor_content:
                if prefix != 'app/src/main/jniLibs/':
                    raise ValueError('Tracked bytes differ from the donor: ' + name +
                                     ' (text inputs must be checked out with LF)')
                # AGP strips prebuilt JNI libraries during packaging. Verify that
                # deterministic transformation on a temporary copy, never the source.
                with tempfile.TemporaryDirectory(prefix='droiddeck-strip-check-') as temp:
                    stripped = Path(temp) / 'library.so'
                    subprocess.run([str(llvm_strip), '--strip-unneeded', '-o', str(stripped),
                                    str(ROOT / name)], check=True, capture_output=True)
                    if stripped.read_bytes() != donor_content:
                        raise ValueError('Stripped tracked binary differs from donor: ' + name)
                verification = 'identical after NDK llvm-strip --strip-unneeded'
            checked[name] = {'source_sha256': hashlib.sha256(content).hexdigest(),
                             'packaged_sha256': hashlib.sha256(donor_content).hexdigest(),
                             'verification': verification}
    return checked


def prepare(apk, rebuilt_dir, gamescope, llvm_strip=None, rebuilt_vr=None, vk_include=None, vr_image=vr_build.IMAGE):
    classifications = check_sources()
    if runtime.digest(apk) != DONOR_SHA256:
        raise ValueError('Donor APK checksum mismatch')
    gamescope_files, pins = read_gamescope(gamescope)
    replacements = {name: validate_rebuilt(rebuilt_dir / name, machine) for name, machine in REBUILT.items()}
    for name, data in replacements.items():
        if hashlib.sha256(data).hexdigest() != REBUILT_SHA256[name]:
            raise ValueError('Replacement does not match the audited local rebuild: ' + name)
    if vk_include is None:
        if llvm_strip is None:
            raise ValueError('Set --vk-include or --llvm-strip to locate the NDK Vulkan headers')
        vk_include = llvm_strip.parent.parent / 'sysroot/usr/include'
    if rebuilt_vr is None:
        vr_files, vr_manifest = vr_build.build(ROOT / 'build/windows-setup/vr', vk_include, root=ROOT, image=vr_image)
    else:
        vr_files, vr_manifest = vr_build.validate(rebuilt_vr, vk_include, root=ROOT)
    helpers = source_assets()
    helper_assets = set(helpers.values())
    plan = {}
    origins = {}
    with ZipFile(apk) as package:
        names = package.namelist()
        if len(set(names)) != len(names):
            raise ValueError('Donor APK has duplicate ZIP entries')
        tracked = validate_tracked_assets(package, llvm_strip)
        excluded = {'assets/linuxfs/' + name for name in REBUILT}
        excluded.update('assets/linuxfs/' + name for name in gamescope_files)
        excluded.update(helper_assets)
        for name in names:
            if name.endswith('/') or name in excluded or name.startswith(VR_PREFIX):
                continue
            if name.startswith(('assets/linuxfs/', 'assets/directaudio/', 'assets/droiddeck-esync/')):
                target = 'app/src/main/' + name
                checked_output(target)
                plan[target] = package.read(name)
                origins[target] = 'verified donor APK'
        for name in JNI:
            target = 'app/src/main/jniLibs/arm64-v8a/' + name
            checked_output(target)
            plan[target] = package.read('lib/arm64-v8a/' + name)
            origins[target] = 'verified donor APK'
        for name in PREPARED:
            target = 'app/build/prepared-assets/' + name
            checked_output(target)
            plan[target] = package.read('assets/' + name)
            origins[target] = 'verified donor APK'
    for name, data in replacements.items():
        target = 'app/src/main/assets/linuxfs/' + name
        checked_output(target)
        plan[target] = data
        origins[target] = 'locally rebuilt libblsession'
    for name, data in gamescope_files.items():
        target = 'app/src/main/assets/linuxfs/' + name
        checked_output(target)
        plan[target] = data
        origins[target] = 'checksum-pinned Gamescope release'
    for name, data in vr_files.items():
        target = 'app/src/main/' + VR_PREFIX + name
        checked_output(target)
        plan[target] = data
        origins[target] = 'VR rebuilt from current source; source and output checksums verified'
    # Do not silently retain unrelated files from a prior preparation.
    expected = set(plan) | {'app/src/main/' + name for name in helper_assets}
    for tree in ('linuxfs', 'directaudio', 'droiddeck-esync'):
        base = ROOT / 'app/src/main/assets' / tree
        for path in base.rglob('*'):
            if path.is_file() and not any(part.startswith('.') for part in path.relative_to(base).parts):
                if path.relative_to(ROOT).as_posix() not in expected:
                    raise ValueError('Unexpected existing generated asset: ' + str(path))
    for source in helpers:
        data = source.read_bytes()
        normalized = data.replace(b'\r\n', b'\n')
        if data != normalized:
            source.write_bytes(normalized)
    for target, data in plan.items():
        path = checked_output(target)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    runtime.record()
    runtime.verify()
    provenance = {
        'version': 1,
        'donor': {'url': DONOR_URL, 'sha256': DONOR_SHA256, 'signer_sha256': DONOR_CERT_SHA256,
                  'pr_source': DONOR_PR_SOURCE, 'main_source': DONOR_MAIN_SOURCE,
                  'source_tree': DONOR_TREE, 'embedded_revision': DONOR_REVISION},
        'source_commit': git('rev-parse', 'HEAD').decode().strip(),
        'classified_differences': classifications,
        'build_plumbing': {name: {'sha256': runtime.digest(ROOT / name),
                                  'diff': git('diff', 'HEAD', '--', name).decode()}
                           for name in sorted(LOCAL_BUILD_PLUMBING)},
        'android_sources_built_by_gradle': {name: runtime.digest(ROOT / name)
                                           for name in sorted(LOCAL_ANDROID_SOURCES)
                                           if (ROOT / name).is_file()},
        'stores': 'omitted; use -PskipRust=true',
        'gamescope': {'repo': pins['GAMESCOPE_REPO'], 'tag': pins['GAMESCOPE_TAG'],
                      'archive_sha256': pins['GAMESCOPE_SHA256']},
        'tracked_files_verified': tracked,
        'vr_build': vr_manifest,
        'outputs': {name: {'origin': origins[name], 'sha256': hashlib.sha256(data).hexdigest()}
                    for name, data in sorted(plan.items())},
        'preload_sources': {path.relative_to(ROOT).as_posix(): runtime.digest(path)
                            for path in sorted((ROOT / 'tools/linuxfs/preload').glob('*.c'))},
        'preload_build_scripts': {path.relative_to(ROOT).as_posix(): {
            'sha256': runtime.digest(path), 'script': path.read_text()}
            for path in sorted((ROOT / 'build/windows-setup').glob('build-preloads-*.sh'))},
        'runtime_manifest_sha256': runtime.digest(ROOT / runtime.MANIFEST),
    }
    output = ROOT / 'app/build/windows-runtime-provenance.json'
    output.write_text(json.dumps(provenance, indent=2, sort_keys=True) + '\n', encoding='utf-8')
    print('Runtime inputs verified; donor components, rebuilt preloads, rebuilt VR and pinned Gamescope recorded.')
    print('Store engines omitted: build with -PskipRust=true.')
    print(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, default=ROOT / 'build/windows-setup/DroidDeck-PR.732-bdf876d.apk')
    parser.add_argument('--rebuilt-linuxfs', type=Path, default=ROOT / 'build/windows-setup/rebuilt-linuxfs')
    parser.add_argument('--gamescope', type=Path, default=ROOT / 'build/windows-setup/gamescope.tzst')
    parser.add_argument('--llvm-strip', type=Path, default=Path.home() /
                        'AppData/Local/Android/Sdk/ndk/27.3.13750724/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe')
    parser.add_argument('--rebuilt-vr', type=Path, help='Reuse a VR build with vr-build.json; default rebuilds in Docker')
    parser.add_argument('--vk-include', type=Path, help='NDK Vulkan headers; defaults to the sysroot beside --llvm-strip')
    parser.add_argument('--vr-image', default=vr_build.IMAGE, help='Docker compiler image for a fresh VR build')
    parser.add_argument('--check-sources', action='store_true', help='Audit source differences without extracting files')
    args = parser.parse_args()
    zstd = ROOT / 'build/windows-setup/tools/zstd/zstd.exe'
    if not shutil.which('zstd') and zstd.is_file():
        import os
        os.environ['PATH'] = str(zstd.parent) + os.pathsep + os.environ.get('PATH', '')
    try:
        if args.check_sources:
            print(json.dumps(check_sources(), indent=2, sort_keys=True))
        else:
            prepare(args.apk.resolve(), args.rebuilt_linuxfs.resolve(), args.gamescope.resolve(), args.llvm_strip.resolve(),
                    args.rebuilt_vr.resolve() if args.rebuilt_vr else None,
                    args.vk_include.resolve() if args.vk_include else None, args.vr_image)
    except (ValueError, OSError, subprocess.CalledProcessError, KeyError) as error:
        parser.exit(1, str(error) + '\n')


if __name__ == '__main__':
    main()
