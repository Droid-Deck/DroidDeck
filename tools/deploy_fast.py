#!/usr/bin/env python3
"""Deploy session-helper changes by grafting them into the latest main CI APK.

Downloads the newest release-signed main build from DroidDeck-CI that this checkout
descends from, replaces the helper assets whose sources differ from that build, re-signs
the result with the local release signer and optionally installs it. Any other change
(Kotlin, resources, native code, runtime components, deletions) stops the run so a full
build covers it.
"""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools/release'))
from check_session_assets import source_assets

CI_REPO = 'Droid-Deck/DroidDeck-CI'
CACHE = Path.home() / '.cache/droiddeck-donors'
IGNORED_PREFIXES = ('docs/', '.github/', 'tools/tests/', 'tools/ci/')
IGNORED_FILES = {'.gitignore', 'LICENSE', 'tools/deploy_fast.py'}
SIGNATURE_SUFFIXES = ('.SF', '.RSA', '.DSA', '.EC')
SIGNER = Path(os.environ.get('LOCALAPPDATA', '')) / 'DroidDeckSigning/Sign-DroidDeckApk.ps1'
PWSH_FALLBACK = Path.home() / '.cache/codex-runtimes/codex-primary-runtime/dependencies/native/powershell/pwsh.exe'


class Refused(Exception):
    pass


def run_bounded(command, timeout, **options):
    try:
        return subprocess.run(command, timeout=timeout, **options)
    except subprocess.TimeoutExpired:
        raise Refused(' '.join(map(str, command[:4])) + ' timed out')


def git(*args, check=True):
    result = run_bounded(['git', '-C', str(ROOT), *args], 300, capture_output=True, check=False)
    if check and result.returncode:
        raise Refused('git ' + ' '.join(args) + ' failed: ' + result.stderr.decode(errors='replace').strip())
    return result.stdout


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def is_ignored(path):
    return path in IGNORED_FILES or path.endswith('.md') or path.startswith(IGNORED_PREFIXES)


def graft_targets():
    return {source.relative_to(ROOT).as_posix(): asset for source, asset in source_assets().items()}


def changed_paths(donor_commit):
    """Return {path: status} for the checkout, uncommitted and untracked files included."""
    changes = {}
    fields = git('diff', '--name-status', '-z', '--no-renames', donor_commit).decode().split('\0')
    for status, path in zip(fields[0::2], fields[1::2]):
        changes[path] = status
    for path in filter(None, git('ls-files', '-z', '-o', '--exclude-standard').decode().split('\0')):
        changes[path] = 'A'
    return changes


def classify(changes, targets, donor_entries):
    """Split changes into grafts {path: asset}, ignored paths and blockers {path: reason}."""
    grafts, ignored, blocked = {}, [], {}
    for path, status in sorted(changes.items()):
        if is_ignored(path):
            ignored.append(path)
        elif path not in targets:
            blocked[path] = 'not a graftable session helper'
        elif status == 'D':
            blocked[path] = 'deleted helper'
        elif targets[path] not in donor_entries:
            blocked[path] = 'new helper the donor APK does not carry'
        else:
            grafts[path] = targets[path]
    return grafts, ignored, blocked


def helper_bytes(path):
    data = (ROOT / path).read_bytes()
    return data if b'\0' in data else data.replace(b'\r\n', b'\n')


def graft(donor, replacements, output):
    """Copy the donor with entries replaced, dropping its signature so it can be re-signed."""
    with ZipFile(donor) as source, ZipFile(output, 'w') as target:
        for info in source.infolist():
            name = info.filename
            if name.startswith('META-INF/') and (name == 'META-INF/MANIFEST.MF' or name.endswith(SIGNATURE_SUFFIXES)):
                continue
            data = replacements.get(name)
            if data is None:
                data = source.read(name)
            entry = copy.copy(info)
            entry.extra = b''
            target.writestr(entry, data, compress_type=info.compress_type)
    missing = set(replacements) - {i.filename for i in ZipFile(output).infolist()}
    if missing:
        raise Refused('Replacement entries missing from the output: ' + ', '.join(sorted(missing)))


def release_tags(limit=60):
    out = run_bounded(['gh', 'release', 'list', '-R', CI_REPO, '--limit', str(limit), '--json', 'tagName'],
                      120, capture_output=True, text=True)
    if out.returncode:
        raise Refused('Could not list DroidDeck-CI releases: ' + out.stderr.strip())
    return [item['tagName'] for item in json.loads(out.stdout) if item['tagName'].startswith('main-')]


def donor_commit(tag):
    short = tag.removeprefix('main-')
    commit = git('rev-parse', '--verify', '--quiet', short + '^{commit}', check=False).decode().strip()
    if not commit:
        git('fetch', '--no-tags', 'origin', 'main')
        commit = git('rev-parse', '--verify', short + '^{commit}').decode().strip()
    return commit


def is_ancestor(commit):
    return subprocess.run(['git', '-C', str(ROOT), 'merge-base', '--is-ancestor', commit, 'HEAD']).returncode == 0


def pick_tag(requested):
    if requested:
        return requested
    for tag in release_tags():
        try:
            if is_ancestor(donor_commit(tag)):
                return tag
        except Refused:
            continue
    raise Refused('No recent DroidDeck-CI main build is an ancestor of this checkout; merge main first.')


def fetch_donor(tag):
    folder = CACHE / tag
    apk = folder / f'DroidDeck-{tag}.apk'
    sums = folder / 'SHA256SUMS.txt'
    if not (apk.is_file() and sums.is_file()):
        folder.mkdir(parents=True, exist_ok=True)
        for pattern in (apk.name, sums.name):
            try:
                result = run_bounded(['gh', 'release', 'download', tag, '-R', CI_REPO, '-p', pattern,
                                      '-D', str(folder), '--clobber'], 900, capture_output=True, text=True)
                if result.returncode:
                    raise Refused('Download failed: ' + result.stderr.strip())
            except Refused:
                apk.unlink(missing_ok=True)
                raise
    expected = dict(reversed(line.split(None, 1)) for line in sums.read_text().splitlines() if line.strip())
    if digest(apk) != expected.get(apk.name, '').strip():
        apk.unlink()
        raise Refused('Donor APK does not match SHA256SUMS.txt: ' + apk.name)
    return apk


def sdk_root():
    return Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or Path.home() / 'android-sdk')


def apksigner():
    candidates = sorted((sdk_root() / 'build-tools').glob('*/apksigner*'), reverse=True)
    for path in candidates:
        if path.suffix in ('', '.bat'):
            return str(path)
    raise Refused('apksigner not found under ' + str(sdk_root() / 'build-tools'))


def signer_sha256(apk):
    result = subprocess.run([apksigner(), 'verify', '--print-certs', str(apk)], capture_output=True, text=True)
    if result.returncode:
        raise Refused('APK signature does not verify: ' + (result.stdout + result.stderr).strip())
    for line in result.stdout.splitlines():
        if 'certificate SHA-256 digest:' in line:
            return line.rsplit(':', 1)[1].strip().lower()
    raise Refused('apksigner reported no certificate digest')


def pwsh():
    found = shutil.which('pwsh')
    if found:
        return found
    if PWSH_FALLBACK.is_file():
        return str(PWSH_FALLBACK)
    raise Refused('PowerShell 7 (pwsh) is required for the local signer')


def signing_env():
    """The .signing.env build_local.sh uses: the override, else the checkout's own, else the main checkout's."""
    common = Path(git('rev-parse', '--path-format=absolute', '--git-common-dir').decode().strip())
    for candidate in (os.environ.get('DROIDDECK_SIGNING_ENV'), ROOT / '.signing.env', common.parent / '.signing.env'):
        if candidate and Path(candidate).is_file():
            return Path(candidate).resolve()
    return None


def version_key(path):
    return [int(part) if part.isdigit() else -1 for part in path.parent.name.replace('-', '.').split('.')]


def sign_with_env(env_file, unsigned, signed):
    complete = [path for path in (sdk_root() / 'build-tools').glob('*/aapt')
                if all((path.parent / tool).is_file() for tool in ('zipalign', 'apksigner'))]
    build_tools = sorted(complete, key=version_key, reverse=True)
    if not build_tools:
        raise Refused('no build-tools with aapt, zipalign and apksigner under ' + str(sdk_root() / 'build-tools'))
    # BUILD_TOOLS is assigned after the env file is sourced so the file cannot replace the chosen SDK.
    script = 'set -a; cd "$(dirname "$1")"; . "$1"; set +a; BUILD_TOOLS="$5" exec "$2" "$3" standard "$4"'
    result = subprocess.run(['bash', '-c', script, 'sign', str(env_file), str(ROOT / 'tools/release/sign-apk.sh'),
                             str(unsigned), str(signed), str(build_tools[0].parent.resolve())],
                            capture_output=True, text=True)
    if result.returncode or not signed.is_file():
        raise Refused('Signing failed:\n' + (result.stdout + result.stderr).strip())


def sign(unsigned, signed):
    if not SIGNER.is_file() and (env_file := signing_env()):
        return sign_with_env(env_file, unsigned, signed)
    # Microsoft Store Python virtualizes AppData for child processes too and hides the signer; use a python.org install.
    def quoted(value):
        return "'" + str(value).replace("'", "''") + "'"
    command = (f"if (-not (Test-Path -LiteralPath {quoted(SIGNER)})) {{ Write-Error 'Local signer not found'; exit 1 }}; "
               f"& {quoted(SIGNER)} -InputApk {quoted(unsigned)} -OutputApk {quoted(signed)} -SdkRoot {quoted(sdk_root())}")
    result = subprocess.run([pwsh(), '-NoProfile', '-Command', command], capture_output=True, text=True)
    if result.returncode or not signed.is_file():
        raise Refused('Signing failed:\n' + (result.stdout + result.stderr).strip())


def verify_output(apk, grafts, pinned):
    if signer_sha256(apk) != pinned:
        raise Refused('Output is not signed with the release certificate')
    with ZipFile(apk) as package:
        for path, asset in grafts.items():
            if package.read(asset) != helper_bytes(path):
                raise Refused('Grafted helper does not match its source: ' + asset)
        for path, asset in graft_targets().items():
            if asset in package.namelist() and package.read(asset) != helper_bytes(path):
                raise Refused('Stale helper in the output: ' + asset)


def install(apk):
    adb = Path(os.environ.get('ADB') or sdk_root() / 'platform-tools/adb.exe')
    if not adb.is_file():
        adb = Path(shutil.which('adb') or '')
    if not adb.is_file():
        raise Refused('adb not found; set ADB or ANDROID_HOME')
    serial = subprocess.run([sys.executable, str(ROOT / 'tools/adb_device.py'), '--adb', str(adb)],
                            capture_output=True, text=True)
    if serial.returncode:
        raise Refused(serial.stderr.strip() or 'No device resolved')
    device = serial.stdout.strip()
    result = subprocess.run([str(adb), '-s', device, 'install', '-r', '--no-incremental', str(apk)])
    if result.returncode:
        raise Refused('adb install failed (a different signing certificate needs a manual uninstall)')
    print('Installed on ' + device)


def run(args):
    pinned = (ROOT / 'keystore/release-signer.sha256').read_text().strip().lower()
    tag = pick_tag(args.donor)
    commit = donor_commit(tag)
    if not is_ancestor(commit):
        raise Refused(f'{tag} is not an ancestor of this checkout; merge main or pick an older build')
    donor = fetch_donor(tag)
    if signer_sha256(donor) != pinned:
        raise Refused('Donor APK is not signed with the release certificate')
    with ZipFile(donor) as package:
        entries = set(package.namelist())
    grafts, ignored, blocked = classify(changed_paths(commit), graft_targets(), entries)
    print(f'Donor {tag} ({commit[:7]})')
    for path in ignored:
        print('  ignored', path)
    for path, asset in grafts.items():
        print('  graft  ', path, '->', asset)
    if blocked:
        for path, reason in blocked.items():
            print('  BLOCKED', path, f'({reason})')
        raise Refused('Changes outside the graftable set; run tools/build_local.sh for these.')
    if not grafts:
        print('Nothing to graft; the donor already matches this checkout.')
        if not args.plan:
            print('Donor APK:', donor)
            if args.install:
                install(donor)
        return
    if args.plan:
        return
    out_dir = ROOT / 'app/build/outputs/apk/fast'
    out_dir.mkdir(parents=True, exist_ok=True)
    stamp = f'{tag}-{git("rev-parse", "--short", "HEAD").decode().strip()}-{int(time.time())}'
    unsigned = out_dir / f'unsigned-{stamp}.apk'
    signed = out_dir / f'DroidDeck-fast-{stamp}.apk'
    graft(donor, {asset: helper_bytes(path) for path, asset in grafts.items()}, unsigned)
    try:
        sign(unsigned, signed)
    finally:
        unsigned.unlink(missing_ok=True)
    verify_output(signed, grafts, pinned)
    print('Signed APK:', signed)
    if args.install:
        install(signed)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--donor', help='DroidDeck-CI main tag such as main-4aa19b6; default is the newest ancestor build')
    parser.add_argument('--plan', action='store_true', help='Show what would be grafted without building')
    parser.add_argument('--install', action='store_true', help='Install the signed APK on the connected device')
    try:
        run(parser.parse_args())
    except Refused as error:
        parser.exit(2, str(error) + '\n')


if __name__ == '__main__':
    main()
