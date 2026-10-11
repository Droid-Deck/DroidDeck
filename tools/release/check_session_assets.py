"""Reject an APK with missing or stale session helpers, including cached local bundles."""
from pathlib import Path
import sys
from zipfile import ZipFile


OVERLAY = Path(__file__).resolve().parents[1] / 'linuxfs/overlay'


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
    errors = []
    with ZipFile(apk) as package:
        for source, asset in source_assets(overlay).items():
            try:
                content = package.read(asset)
            except KeyError:
                errors.append('missing ' + asset)
                continue
            if content != source.read_bytes():
                errors.append('stale ' + asset)
    return errors


if __name__ == '__main__':
    errors = check(sys.argv[1])
    if errors:
        sys.exit('\n'.join(errors))
    print('APK session helpers match source')
