"""Reject an APK with missing or stale session helpers, including cached local bundles."""
from pathlib import Path
import importlib.util
import re
import sys
from zipfile import ZipFile


OVERLAY = Path(__file__).resolve().parents[1] / 'linuxfs/overlay'
SESSION_FILES = OVERLAY.parents[2] / 'app/src/main/java/com/droiddeck/launcher/session/SessionFiles.kt'


def check_extraction(overlay, source):
    # Extracted into usr/local/bin under any name, or (droiddeck-steam-taskset, which PATH puts in
    # front of Steam's own taskset only for the client) into usr/local/libexec/droiddeck-steam.
    deployed = set(re.findall(
        r'"(usr/local/bin/droiddeck-[^"]+)"\s+to\s+"usr/local/(?:bin|libexec/droiddeck-steam)/[^"/]+"', source))
    return ['not extracted at session start: ' + path.relative_to(overlay).as_posix()
            for path in sorted((overlay / 'usr/local/bin').glob('droiddeck-*'))
            if path.relative_to(overlay).as_posix() not in deployed]


def source_assets(overlay=OVERLAY):
    sources = list((overlay / 'usr/local/bin').glob('droiddeck-*'))
    sources += [path for path in [overlay / 'usr/local/bin/steam-compatibility'] if path.is_file()]
    sources += [path for path in (overlay / 'usr/bin').rglob('*') if path.is_file()]
    result = {source: 'assets/linuxfs/' + source.relative_to(overlay).as_posix() for source in sources}
    if overlay == OVERLAY:
        desktop = overlay.parent / 'desktop'
        for name in ['droiddeck-desktop', 'droiddeck-gpu', 'droiddeck-desktop-gpu', 'kwin_wayland_wrapper']:
            result[desktop / name] = 'assets/linuxfs/usr/local/bin/' + name
        result[desktop / 'firefox-droiddeck.js'] = 'assets/linuxfs/usr/lib/firefox/defaults/pref/droiddeck.js'
        result[desktop / 'droiddeck-clipboard.desktop'] = 'assets/linuxfs/etc/xdg/autostart/droiddeck-clipboard.desktop'
        result[overlay.parent.parent / 'mangoapp/mangoapp'] = 'assets/linuxfs/usr/local/bin/mangoapp'
    return result


def check(apk, overlay=OVERLAY):
    errors = check_extraction(overlay, SESSION_FILES.read_text()) if overlay == OVERLAY else []
    with ZipFile(apk) as package:
        for source, asset in source_assets(overlay).items():
            try:
                content = package.read(asset)
            except KeyError:
                errors.append('missing ' + asset)
                continue
            if content != source.read_bytes():
                errors.append('stale ' + asset)
        if overlay == OVERLAY:
            spec = importlib.util.spec_from_file_location('powervr_fetch',
                    OVERLAY.parents[1] / 'powervr/fetch.py')
            runtime = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(runtime)
            try:
                runtime.verify_archive(package.read('assets/hybris/runtime.tzst'),
                                       package.read('assets/hybris/version.txt').decode('ascii'))
            except (KeyError, ValueError, OSError) as error:
                errors.append('invalid PowerVR runtime: ' + str(error))
    return errors


if __name__ == '__main__':
    errors = check(sys.argv[1])
    if errors:
        sys.exit('\n'.join(errors))
    print('APK session helpers match source')
