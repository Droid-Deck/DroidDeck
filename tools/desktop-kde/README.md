# The desktop package (KDE Plasma)

The desktop the app installs into its Linux runtime the first time the desktop is opened: KDE Plasma
set up as SteamOS's desktop mode, with Valve's Vapor theme, the Steam Deck wallpaper and launcher
icon, Plasma's default bottom panel, and "Return to Gaming Mode" and Steam on the desktop.

- `seeds.txt`: the packages asked for. `build.sh` takes their closure over Arch Linux ARM's
  repositories (`closure.py`) and drops what the runtime already has.
- `runtime-packages.txt`: the runtime's own packages, `name version` from its pacman database
  (`ls /var/lib/pacman/local` in the rootfs). Refresh it when the runtime is rebuilt.
- `overlay/`: our files on top - system-wide KDE defaults in `/etc/xdg`, the Vapor theme and its
  layout script, the menu entries Steam and Return to Gaming Mode use.

`.github/workflows/build-desktop-kde.yml` builds it; run it with a tag to publish a release, then
point the `desktop-kde` row of the catalog (`desktop.json`) at the asset's URL, size and sha256.
The app looks for that row (`DesktopCatalog.DESKTOP_ID`).

At run time the desktop is started by `tools/linuxfs/desktop/droiddeck-desktop` (the app stages it
at every session): Plasma's own `startplasma-wayland`, with KWin nested in the app's compositor
through `kwin_wayland_wrapper`. KWin composites in software (QPainter) - the Adreno node is not a
DRM device - so games and emulators from the menu go through `droiddeck-gpu`, and Steam goes back
to the app as a Steam session.
