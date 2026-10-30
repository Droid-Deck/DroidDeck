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

pacman works on the desktop. `pacman-db.py` writes an install record for every package the desktop
ships into `/var/lib/pacman/local`, and builds the `droiddeck` repository: Arch Linux ARM's core,
extra and alarm databases as they were at build time, with the runtime's and the desktop's own
packages at the installed versions. The release carries that database (`droiddeck.db`) and the
packages `mirror.txt` names; everything else downloads from Arch Linux ARM as a `CacheServer`, so its
404s never disable a mirror. Installs therefore resolve against the desktop's exact Qt and KDE
versions, and `pacman -Syu` only ever moves packages the user added.

- `/usr/local/bin/pacman` runs the real pacman with `/usr/share/droiddeck/pacman/pacman.conf`, first
  copying in the shipped database and dropping the runtime's records for packages the desktop
  replaced (`prepare`). As root it adds `--needed` to installs, so guides that reinstall
  something the desktop has just skip it.
- `sudo`, `su` and `sudoedit` run commands as root: the session is the app's own Android user, and
  `libdroiddeck-root.so` (`root/root.c`, preloaded by `sudo`) reports uid 0, accepts ownership
  changes and shows the runtime's 0700 directories as 0755. It grants nothing the user lacked.
- `hooks/droiddeck-system.hook` refuses to remove or replace any runtime or desktop package;
  `droiddeck-remember.hook` keeps the user's own packages in
  `~/.local/share/droiddeck/pacman/packages.txt`, and `restore` (autostarted) reinstalls them after a
  runtime update and upgrades them after a desktop update. Hooks that need systemd, udev, kernel
  modules or an initramfs are masked.

`.github/workflows/build-desktop-kde.yml` builds it and, run with a new tag, publishes it as a
release in Droid-Deck/DroidDeck-Components (tags with `-rc` are published as pre-releases for testing
before an app build points at them). `release.env` pins the tag, sha256 and size the apk
installs (`DesktopCatalog.desktopEntry`); a published tag is never replaced, so a changed package
is a new tag and a `release.env` update.

At run time the desktop is started by `tools/linuxfs/desktop/droiddeck-desktop` (the app stages it
at every session): Plasma's own `startplasma-wayland`, with KWin nested in the app's compositor
through `kwin_wayland_wrapper`. The package's `kwin-compositor` marker selects OpenGL through
Zink for KWin 6.8, which removed QPainter. Older packages retain their software compositor.
KWin runs without Gallium's threaded context and without overlay layers. With the threaded context, KWin crashed
uploading windows' shared-memory buffers by reading past their end, and the host shows only a single buffer.
On Android the launcher explicitly selects the KGSL render node; the nested backend can use it
when the host's dmabuf feedback cannot name a DRM device. Games and emulators from the menu go
through `droiddeck-gpu`, and Steam goes back to the app as a Steam session.

`kwin.env` pins the KWin version shared by the build and packaging jobs. `build-kwin.sh` builds that version's
Wayland executable and library on a native ARM64 runner, using a disposable
Arch Linux ARM chroot with every build package signature-checked. It matches the current KWin package
in Arch Linux ARM even when that repository trails KDE's latest release. The source tarball is pinned
by SHA-256 from KDE's official source inventory; `patches/0001-respect-nested-desktop-scale.patch`
lets Display Settings control the inner desktop scale independently of the outer window size.
It keeps layer sizes, pointer/touch coordinates and the cursor in the same physical pixels.
The behavior is enabled only with `DROIDDECK_NESTED_SCALE`, which the desktop script supplies.
`0002-select-explicit-nested-render-node.patch` lets that nested backend use one explicitly
selected `KWIN_RENDER_NODES` device when host feedback cannot identify it. It does not guess
between GPUs or change normal KWin device selection.
`0003-forward-nested-scroll-source.patch` passes the host's scroll source, wheel steps and scroll stops
through the nested backend, which upstream leaves as a TODO. Two-finger touch scrolling then reaches
apps as touchpad scrolling, and wheel steps stay whole clicks.

`pins/mesa/desc` keeps Mesa 26.2.4 and its actual package dependencies ahead of the rolling
repository metadata. Mesa 26.2.5 crashes in Zink shader compilation on Thor with the selected
Turnip driver. `mesa.env` points at immutable copies of the original 26.2.4 package and signature;
both build jobs verify the Arch Linux ARM signature and pinned SHA-256 as for every other package.

The desktop workflow replaces the same-version `kwin_wayland` executable and `libkwin.so`; the rest
comes from the signature-checked Arch packages. `desktop-kde.kwin.txt` records the source, patch,
executable, library and build package hashes. Corresponding source is
https://download.kde.org/stable/plasma/6.8.2/kwin-6.8.2.tar.xz plus the patch in this repository.

The inventories use `.gzip` rather than `.gz`: Android's asset packer expands `.gz` and strips
its suffix. The shared local/CI runtime payload check verifies the exact APK names and bytes.

Desktop removal uses `app/src/main/assets/desktop-removal/`, generated by `removal-manifests.py`
from the checksum-pinned LXQt r1, KDE r1/r2 and emulator r1 archives. KDE candidates are the files
of the desktop applications/themes in `seeds.txt` (their source package hashes verified against
`desktop-kde.packages.txt`) plus our overlay. Shared dependencies, fonts, Xwayland and Qt Wayland
remain available to Steam, native apps and emulators. Regenerate the inventories and add the new
version to `versions.txt` when publishing another desktop pin; unknown versions cannot be removed.

Removal checks file size and SHA-256, or the exact symlink target, before unlinking. Base-runtime
pacman files and emulator bundle paths are protected even when they overlap an application.
It never follows parent symlinks or recursively deletes directories. Home, added apps, custom
files and modified package files stay. A pending marker disables the installed state and allows
retry after interruption. Installing the desktop again repairs its package files and clears it.
The old LXQt applications are cleaned after KDE installation and before the next guest launch on
an already-upgraded runtime, protecting both KDE inventories as well as the runtime and emulators.
Only files owned by `lxqt-applications.txt`'s application packages are candidates; dependencies
stay even when the old runtime's pacman database does not record them. In particular, gamescope
still needs the old package's libseat. The source LXQt application archives are the versions in
the published `steamdeck-desktop-r1/desktop.packages.txt`; verify their `.sig` files with
`gpgv --keyring tools/desktop-kde/archlinuxarm-builder.gpg` and the Build System fingerprint used
by `build.sh`. Put their filenames in `selected.txt` in the LXQt cache. `sources.json` records
their SHA-256 values; file identities still come from the checksum-pinned published desktop.
