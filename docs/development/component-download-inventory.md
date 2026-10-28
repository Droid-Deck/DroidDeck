# Component download discovery and GPU versioning

Saved: 2026-10-18.

## Inventory snapshot

This preserves the component discovery table discussed on 2026-10-18. It describes
`wallentx/powervr` at `e74c1271d2db65ff9c19c598a8edafad260759d4`, compared with upstream
`a97a7d90581d52f1aff057fb93d7861b43b29ce0`. Source links below are pinned to that snapshot.
It is a record of the earlier table, not a claim that every row was re-audited at the
latest head. The GPU release assessment below was checked separately at
`cc5b720a6aacdc50961977ba59b258f2ef14a5bc`.

The inventory below covers installable component families and their dependencies, excluding individual games and artwork. A catalog row represents all eligible entries in that catalog.

For compactness:

- **Release scan** means `GET /repos/{owner}/{repo}` to resolve identity, followed by `GET /repositories/{id}/releases?per_page=30&page=1..3`.
- **C/** means `https://raw.githubusercontent.com/Droid-Deck/DroidDeck-Components/main/`.
- The release scanner excludes drafts and ignores assets without a GitHub SHA-256 digest. **Release titles generally do not select components.**

### Repository Sources editor

| Component | Default repository/query | Exact discovery rule |
|---|---|---|
| FEX WCP | Release scan: `the412banner/nightlies` | Exact tag **`FexCore-Linux`**; assets ending `.wcp`, case-insensitive |
| DXVK WCP variants | Same repository | Exact tags **`Dxvk-Linux`**, **`Dxvk-arm64ec-Linux`**, **`Dxvk-gplasync-Linux`**; `.wcp` assets |
| VKD3D-Proton WCP | Same repository | Exact tag **`Vkd3d-proton-Linux`**; `.wcp` assets |
| Custom WCP packages | Release scan of each added repository | Any tag containing exact sidecar **`droiddeck-component.json`** or **`droiddeck-component-<component>.json`**. JSON declares `fex`, `dxvk`, or `vkd3d` and exact archive names, sizes, and hashes |
| GE-Proton ARM64 | `gloriouseggroll/proton-ge-custom` | Non-prerelease release; asset ends **`aarch64.tar.gz`** or **`aarch64.tar.xz`** |
| CachyOS Proton ARM64 | `cachyos/proton-cachyos` | Non-prerelease release; asset ends **`arm64.tar.gz`** or **`arm64.tar.xz`** |
| DroidDeck Proton | `droid-deck/droiddeck-proton` | Non-prerelease release; asset ends **`aarch64.tar.gz`** or **`aarch64.tar.xz`** |
| Custom Proton | Release scan of added repository | Legacy archives additionally require prefix **`GE-Proton`**, **`proton-cachyos`**, or **`DroidDeck-Proton`**, respectively. Alternatively, exact sidecar **`droiddeck-component-proton.json`** or **`droiddeck-proton.json`** declares the archive |
| Banners Turnip | `the412banner/banners-turnip` | Asset starts **`Turnip-<release tag>`**, ends `.zip`; excludes `*-Wayland.zip`. `-Linux` identifies the Linux half |
| WinNative Turnip | `winnative-emu/drivers` | Asset regex **`^WN-(Linux-)?Turnip-[^-]+-([a-z]+)_(\w+)\.zip$`** |
| DroidDeck Turnip bundle | `droid-deck/drivers-ci` | Exact asset shape **`DD-Turnip-v<major>.<minor>.<patch>.zip`**; release tag must end in numeric `major.minor.patch`; prereleases excluded |
| System Vulkan: PowerVR, Xclipse, Mali | Default `droid-deck/droiddeck`, plus added repositories | **No tag/title prefix.** Exact sidecar **`droiddeck-component-system-vulkan-<profile>.json`**, declaring exact archive **`system-vulkan-<profile>.tar.zst`**. Profile, requirements, provenance, size, and hashes must match. Prereleases allowed |
| App Stable / Preview / PR Test updates | Default `droid-deck/droiddeck-ci`; selected repository's **`catalog.json`** through the Contents API | Reads catalog fields **`stable`**, **`preview`**, and **`tests`**. No client-side release enumeration or tag-prefix matching. Blank branch follows repository default; explicit branch supplies `ref` |

For added Turnip repositories, the code tries those same filename adapters. The Proton filename checks above are case-sensitive. WCP and System Vulkan listings permit prereleases.

**System Vulkan automatic first installation has an additional rule:** it requests the **exact repository identity, release tag, and sidecar hash pinned into the APK**. That differs from refreshing the list of all compatible bundles. Preview 2 pins the fork's `system-vulkan-2eec15...` release.

Sources: [WCP selectors](https://github.com/wallentx/DroidDeck/blob/e74c1271d2db65ff9c19c598a8edafad260759d4/app/src/main/java/com/droiddeck/launcher/session/ComponentsManager.kt#L1130), [Proton selectors](https://github.com/wallentx/DroidDeck/blob/e74c1271d2db65ff9c19c598a8edafad260759d4/app/src/main/java/com/droiddeck/launcher/session/ProtonExtras.kt#L426), [Turnip adapters](https://github.com/wallentx/DroidDeck/blob/e74c1271d2db65ff9c19c598a8edafad260759d4/app/src/main/java/com/droiddeck/launcher/gpu/TurnipReleases.kt#L35), [System Vulkan discovery](https://github.com/wallentx/DroidDeck/blob/e74c1271d2db65ff9c19c598a8edafad260759d4/app/src/main/java/com/droiddeck/launcher/gpu/SystemVulkanComponentStore.kt#L369).

### Fixed catalogs, pins, and Steam services

| Component | What is queried | Selection rule |
|---|---|---|
| Linux runtime/rootfs | **C/`linuxfs.json`** | Single entry's `version`, `url`, `sha256`, and `size`; no release scan |
| Native FEX for x86 Steam | **C/`fex.json`** | Declared release must be `fex-<digits>-r<digits>`, or `fex` with a matching version field; exact asset **`fex.tzst`** |
| FEX fallback | Fixed Components release **`fex-2610-r5`** | Exact `fex.tzst` and pinned checksum |
| FEX Ubuntu x86 rootfs | Fixed Components release **`fex-rootfs-ubuntu-24.04-2026-08-11`** | Exact **`Ubuntu_24_04.sqsh`** and pinned checksum |
| Initial ARM64 Proton seed | **C/`steam-seed.json`** | Exact package ID **`proton-arm64`** |
| ARM64 Steam client | Valve CDN manifest **`steam_client_steamdeck_publicbeta_linuxarm64`** | Manifest blocks ending **`_all`** or **`_linuxarm64_linuxarm64`** |
| x86-64 Steam client | Valve CDN manifest **`steam_client_steamdeck_publicbeta_ubuntu12`** | Manifest blocks ending **`_all`** or **`_ubuntu12`** |
| Steam-managed Proton/FEX/runtimes | Steam client installation/update APIs | Required app IDs: **4427310** Proton Experimental ARM64, **3127680** FEX, **1628350** Sniper, **4183110** Steam Linux Runtime 4. Proton 11 ARM64, **4628740**, is also recognized |
| SteamVR ARM64 | Fixed `steamdeck-packages.steamos.cloud` package URL | Exact **`deckard-steamvr-rel-r25358740+28b72a4f-1-aarch64.pkg.tar.zst`**, pinned checksum; no catalog |
| KDE desktop | Fixed Components release **`desktop-kde-r2`** | Exact **`desktop-kde.tar.zst`**, build-pinned checksum and size |
| Windows redistributables/components | **C/`components.json`** | Valid entries in `components[]`; installation eligibility depends on readiness and supported actions |
| Trebuchet MS / Verdana | `pushcx/corefonts`, raw `master` files | Exact **`trebuc32.exe`** and **`verdan32.exe`**, pinned checksums |
| eSync compatibility packs | Components release **`droiddeck-esync-index`**, files **`index.json`** and **`index.json.sig`** | Signed index selects compatible packs; payload URLs must use the **`droiddeck-esync-`** release-tag prefix and match declared hashes |

The selectable **installed Proton list** is also a filesystem scan: directories containing both `proton` and `files/lib/wine`, with names/version information read locally. It is not another GitHub release query. Mono and Gecko are treated as Proton-provided rather than separate downloadable components.

Sources: [runtime catalog](https://github.com/wallentx/DroidDeck/blob/e74c1271d2db65ff9c19c598a8edafad260759d4/app/src/main/java/com/droiddeck/launcher/runtime/LinuxRuntimeInstaller.java#L35), [FEX catalog](https://github.com/wallentx/DroidDeck/blob/e74c1271d2db65ff9c19c598a8edafad260759d4/app/src/main/java/com/droiddeck/launcher/session/FexCatalog.kt#L17), [desktop/Proton seed catalogs](https://github.com/wallentx/DroidDeck/blob/e74c1271d2db65ff9c19c598a8edafad260759d4/app/src/main/java/com/droiddeck/launcher/runtime/DesktopCatalog.kt#L15), [Windows component policy](https://github.com/wallentx/DroidDeck/blob/e74c1271d2db65ff9c19c598a8edafad260759d4/app/src/main/java/com/droiddeck/launcher/session/WinComponents.kt#L24).

### Optional applications and supporting downloads

| Component | What is queried | Selection rule |
|---|---|---|
| Desktop packages/emulators/catalog AppImages | **C/`desktop.json`** | Every `packages[]` entry; uses `pages[device page size]` when supplied |
| Decky Loader | `GET /repos/Droid-Deck/decky-loader/releases?per_page=30` | Exact **`PluginLoader-arm64`**; fallback **`PluginLoader`** when FEX is available. Stable/prerelease channels separated; checksum required |
| Decky plugin binaries | Imported plugin ZIP's **`remote_binary[]`** metadata | Explicit filenames, HTTPS URLs, and checksums; no app-owned release scan |
| GitHub AppImages | User repository: `GET /repos/<repo>/releases?per_page=15` | `.AppImage` assets, case-insensitive; prefer ARM64, then architecture-neutral, then x86-64. Prefer stable releases, otherwise prerelease |
| Flatpak support dependencies | Arch Linux ARM package databases/mirrors | Named dependencies from the guest installer |
| Flathub applications | `flathub.org/api/v2/collection/<name>` and **POST `/search`** | Filter to **`aarch64`** desktop applications; installation uses the selected Flatpak ID |
| Epic Games Launcher | Epic's **`.../launcher/api/installer/download/EpicGamesLauncher-x86_64.AppImage`** endpoint | HTTPS redirect must contain **`/Builds/EGLv2/<version>/Linux/`** |
| Epic's SteamRT4 dependency | `repo.steampowered.com/steamrt4/images/latest-public-stable/VERSION.txt`, then versioned `SHA256SUMS` | Version starts **`4.`**; exact **`com.valvesoftware.SteamRuntime.Platform-amd64,i386-steamrt4-runtime.tar.gz`** |
| Epic's GLES dependency | Fixed Debian package URL | Exact **`libgles2_1.7.0-1+b2_amd64.deb`**, pinned checksum |

Source references: [Decky](https://github.com/wallentx/DroidDeck/blob/e74c1271d2db65ff9c19c598a8edafad260759d4/app/src/main/java/com/droiddeck/launcher/runtime/DeckyManager.kt#L82), [AppImage selection](https://github.com/wallentx/DroidDeck/blob/e74c1271d2db65ff9c19c598a8edafad260759d4/app/src/main/java/com/droiddeck/launcher/runtime/UserApps.kt#L303), [Flathub](https://github.com/wallentx/DroidDeck/blob/e74c1271d2db65ff9c19c598a8edafad260759d4/app/src/main/java/com/droiddeck/launcher/store/FlathubApi.kt#L12), [Epic dependencies](https://github.com/wallentx/DroidDeck/blob/e74c1271d2db65ff9c19c598a8edafad260759d4/app/src/main/java/com/droiddeck/launcher/stores/epic/EpicClientInstaller.kt#L23).

## GPU release boundaries

Vendor tag namespaces approved on 2026-10-19, after checking upstream's published
component tags. The per-vendor publisher and GitHub channel handling are implemented on
`wallentx/powervr`. The shared-input namespace proposal below remains separate.

### What the existing tags mean

"No tag/title prefix" in the inventory describes list discovery. It does not mean
that the downloads have no GitHub release tag. The published releases currently
put three separate archives and three separate sidecar manifests under one
`system-vulkan-<revision>` tag. For example,
[the existing release](https://github.com/wallentx/DroidDeck/releases/tag/system-vulkan-2eec15a54924a996a50380cafd7af7ecd26c5e48)
contains a distinct archive and manifest for each vendor.

The client selects the exact profile sidecar, validates its profile, archive,
requirements, provenance, size, and hashes, and records its release tag. Bootstrap
already has separate tag and manifest-hash fields for each profile. At the recorded pre-change commit, the publisher assigned one version and tag
to every profile. The current publisher takes one vendor and derives its tag
independently.

### Independently versioned vendor bundles

Use separate immutable tag namespaces and version histories:

| Installable component | Agreed tag pattern | Archive | Required discovery sidecar |
| --- | --- | --- | --- |
| Mali runtime bundle | `system-vulkan-mali-<version>` | `system-vulkan-mali.tar.zst` | `droiddeck-component-system-vulkan-mali.json` |
| PowerVR runtime bundle | `system-vulkan-powervr-<version>` | `system-vulkan-powervr.tar.zst` | `droiddeck-component-system-vulkan-powervr.json` |
| Xclipse runtime bundle | `system-vulkan-xclipse-<version>` | `system-vulkan-xclipse.tar.zst` | `droiddeck-component-system-vulkan-xclipse.json` |

`<version>` is a human-readable component version, independent of the APK version
and the other vendors. Use `v<major>.<minor>.<patch>` numbering, following the
upstream Turnip bundle style; for example, `system-vulkan-mali-v0.1.0`. This is a
bundle version, not a claim about the underlying Android driver or DXVK version.
The full producer commit and every input hash remain in provenance.

### Alignment with upstream tags

Checked against published upstream releases on 2026-10-19. Upstream uses several
component-specific formats rather than one universal tag grammar:

| Upstream component | Published example | Naming pattern |
| --- | --- | --- |
| Turnip bundle | [`DD-Turnip-v0.3.0`](https://github.com/Droid-Deck/Drivers-CI/releases/tag/DD-Turnip-v0.3.0) | Component name plus `v` and a three-part version |
| Gamescope | [`gamescope-3.16.29-p10`](https://github.com/Droid-Deck/DroidDeck-Components/releases/tag/gamescope-3.16.29-p10) | Component name, source version, and patch revision |
| FEX | [`fex-2610-r5`](https://github.com/Droid-Deck/DroidDeck-Components/releases/tag/fex-2610-r5) | Component name, source version, and package revision |
| Linux runtime | [`linuxfs-r9`](https://github.com/Droid-Deck/DroidDeck-Components/releases/tag/linuxfs-r9) | Component name and package revision |
| KDE desktop | [`desktop-kde-r2`](https://github.com/Droid-Deck/DroidDeck-Components/releases/tag/desktop-kde-r2) | Component name and package revision |

The agreed System Vulkan tags follow the same component-first structure and use
Turnip's version suffix style. Including the GPU family makes each independently
updated bundle explicit. This is a compatible extension of the observed upstream
conventions, not a claim that upstream has adopted these System Vulkan tag names.
Existing sidecar and checksum validation remains the discovery contract.

A Mali-only DXVK change should create a new Mali component version. It should not
create new PowerVR or Xclipse versions just because the packager can build all three.
A synchronized release is useful only as an explicitly tested compatibility set
that references independent component versions. It should not replace their tags.

### Shared build inputs also keep their own identities

A vendor runtime is assembled from common libhybris/WSI and its selected DXVK build.
The installer currently installs that verified bundle atomically; separate input
tags do not imply independently swapping its internal libraries on the device.

| Build input | Proposed release namespace | Consumer |
| --- | --- | --- |
| libhybris bridge and layers | `hybris-<version>` | Any vendor bundle whose compatibility has been verified |
| WSI | `wsi-<version>` | Any vendor bundle whose compatibility has been verified |
| PowerVR DXVK | `dxvk-powervr-<version>` | PowerVR bundle |
| Xclipse DXVK | `dxvk-xclipse-<version>` | Xclipse bundle |
| Mali DXVK | `dxvk-mali-<version>` | Mali bundle |

The pin file already identifies these inputs separately by URL, size, checksum,
and build evidence. Some existing inputs have component-specific tags; others are
assets attached to app or graphics aggregate releases. New independently published
inputs should consistently use their own tag namespaces. Existing published tags
and bytes remain available for installed APKs and older pinned builds.

```text
hybris version + WSI version + vendor DXVK version
                      |
                      v
            validated vendor runtime bundle
                      |
                      v
        vendor-specific immutable release tag
                      |
                      v
        that profile's bootstrap / selected version
```

### Implemented publication and channel behavior

1. Select `variant` and `version` in the publisher. Packaging derives
   `system-vulkan-<variant>-<version>` and emits only that vendor's archive and
   sidecar. It requires an empty output directory.
2. The candidate bootstrap file preserves the other profiles' pins for the same
   producer. Adopt it after verifying the release; regenerate from newer pins if
   another profile changed after packaging.
3. GitHub's existing `prerelease` flag determines the channel. The workflow defaults
   to `true`; select `false` for Stable. Both channels are listed, with the app's
   existing Stable and Preview labels. Cached and installed metadata retain the
   flag; older entries without it stay unlabeled.
4. Existing shared-tag releases still match their profile sidecars. Exact manifest,
   repository, size, and checksum validation remains in place. Auto continues to
   use the APK's explicit bootstrap recommendation rather than selecting the newest
   prerelease.

The existing generic release scan is bounded to three pages of 30 releases. Splitting
component histories adds entries to that shared history, so a component index or
another explicit discovery strategy should be considered before the history grows
past that window. Filtering tag prefixes locally does not reduce the number of
release-list API requests by itself.

### Evidence at the recorded pre-change GPU branch head

- [Publisher](https://github.com/wallentx/DroidDeck/blob/cc5b720a6aacdc50961977ba59b258f2ef14a5bc/.github/workflows/system-vulkan-components.yml)
  constructs one `system-vulkan-${COMPONENT_VERSION}` release.
- [Packager](https://github.com/wallentx/DroidDeck/blob/cc5b720a6aacdc50961977ba59b258f2ef14a5bc/tools/powervr/package_components.py)
  defines three variants, selects their input archives, and gives all profile pins
  the same `args.release_tag`.
- [Client component store](https://github.com/wallentx/DroidDeck/blob/cc5b720a6aacdc50961977ba59b258f2ef14a5bc/app/src/main/java/com/droiddeck/launcher/gpu/SystemVulkanComponentStore.kt)
  has per-profile bootstrap tags and discovers candidates by exact sidecar name.
- [Build input pins](https://github.com/wallentx/DroidDeck/blob/cc5b720a6aacdc50961977ba59b258f2ef14a5bc/tools/powervr/release.json)
  keep libhybris, WSI, and the three DXVK payloads separately identifiable.
