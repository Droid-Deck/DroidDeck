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

`.github/workflows/build-desktop-kde.yml` builds it and, run with a new tag, publishes it as a
release in Droid-Deck/DroidDeck-Components. `release.env` pins the tag, sha256 and size the apk
installs (`DesktopCatalog.desktopEntry`); a published tag is never replaced, so a changed package
is a new tag and a `release.env` update.

At run time the desktop is started by `tools/linuxfs/desktop/droiddeck-desktop` (the app stages it
at every session): Plasma's own `startplasma-wayland`, with KWin nested in the app's compositor
through `kwin_wayland_wrapper`. KWin composites in software (QPainter) - the Adreno node is not a
DRM device - so games and emulators from the menu go through `droiddeck-gpu`, and Steam goes back
to the app as a Steam session.
