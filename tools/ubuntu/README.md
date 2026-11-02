# The Linux runtime (Ubuntu)

DroidDeck's Linux runtime: Ubuntu 26.04 LTS for arm64, with Kubuntu's Plasma desktop as a separate download, set up as
SteamOS's desktop mode (Valve's Vapor theme, the Steam Deck wallpaper, "Return to Gaming Mode" and
Steam on the desktop). The app installs it into `files/linuxfs` and runs it under its patched proot.

- `build-debs.sh` (run in an `ubuntu:26.04` container on an arm64 runner) rebuilds two Ubuntu source
  packages: Mesa with `-Dfreedreno-kmds=msm,kgsl`, so Turnip and freedreno reach Android's
  `/dev/kgsl-3d0`, and KWin with `patches/kwin` (nested desktop scale, an explicit render node,
  scroll source/discrete forwarding). Both get a `+droiddeck` version. `patches/mesa` adds KGSL
  UBWC 5/6 (backported from upstream Mesa) and the KGSL device numbers for WSI (from
  [Droid-Deck/Drivers](https://github.com/Droid-Deck/Drivers)).
- The runtime's built-in Vulkan driver is DroidDeck's DD-Turnip Linux build (`DD_TURNIP_URL` and
  `DD_TURNIP_SHA256` in `base.env`): Ubuntu's Turnip is diverted to `libvulkan_freedreno.so.distrib`
  because it lacks the Adreno 8xx fixes. Zink, EGL and llvmpipe stay Ubuntu's.
- `build.sh` verifies Ubuntu's base image (`base.env`: signed `SHA256SUMS` from cdimage plus the
  pinned hash), chroots into it and runs `setup.sh` twice. The `runtime` stage is everything gaming
  mode needs; it is packed as `<tag>.tar.zst`. The `desktop` stage then adds Plasma on top, and
  `desktop-pack.py` packs only what that stage added or changed as `<tag>-desktop.tar.zst`, so the
  desktop shares every runtime file instead of shipping its own copy. The runtime carries
  `usr/share/droiddeck/desktop-pack.json` (tag, URL, hash, size) naming its matching desktop pack.
- `setup.sh runtime` installs `packages-runtime.txt` without recommends and the `+droiddeck` debs from
  a local repository in `/var/lib/droiddeck/apt` (pinned at 1001 and held), then builds the
  `droiddeck-runtime` package from `overlay-runtime/`. `setup.sh desktop` installs
  `packages-desktop.txt`, Firefox from Mozilla's apt repository (`mozilla.asc`, fingerprint
  35BAA0B33E9EB396F59CA838C0BA5CE6DC6315A3) and builds `droiddeck-desktop` from `overlay-desktop/`.
  Both packages are Protected, depend on `protected-*.txt`, own our files and divert any Ubuntu file
  they replace, so apt neither removes the essentials nor overwrites our settings on upgrade.
- The desktop pack's `usr/share/droiddeck/desktop/files.tsv.gz` lists each path the desktop added
  (N), changed (C) or removed (R), and `originals/` keeps the runtime's copies of changed and removed
  files. Removing the desktop restores those and deletes the added files the user has not changed;
  a runtime update replaces the tree, and the desktop is downloaded again when it is next opened.

At run time:

- `sudo`, `su`, `pkexec` and apt/aptitude (for commands that change the system) go through
  `droiddeck-root`: the app runs them in a separate proot with `-0 --link2symlink`, passing the
  caller's terminal, so dpkg gets root ownership emulation and hard links while the desktop keeps
  the fast path. PackageKit (Discover) and polkit are started the same way by the session's system
  bus (`/usr/share/droiddeck/system-services`).
- `apt/remember` (a `DPkg::Post-Invoke` hook) keeps the packages, sources and keys the user added in
  `~/.local/share/droiddeck/apt`; `apt/restore` (autostarted) puts them back after a runtime update.
- The installer turns the image's hard links into proot's link2symlink layout, and proot shows the
  linked name as `/proc/self/exe` (patch 0020), so Ubuntu's multi-call coreutils work.

`.github/workflows/build-ubuntu-runtime.yml` builds it and, run with a new tag (`ubuntu-rN`),
publishes the runtime and its desktop pack in Droid-Deck/DroidDeck-Components; `linuxfs-ubuntu.json` there names the release the
app installs.
