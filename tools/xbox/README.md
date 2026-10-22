# Xbox backend for DroidDeck

DroidDeck uses the Nexus backend protocol with a native Linux AArch64 CLI and
service. It does not install the Nexus desktop launcher or x86 Linux Wine.
`backend.lock.json` pins Xodus, the Nexus commit, Rust, and the ordered patch
checksums. `runtime.lock.json` pins the Nexus Windows runtime release and each
extracted PE DLL. `prepare.py` verifies both source revisions and all Nexus patch
checksums before applying the local transport, launch and library patches.

`droiddeck-library-subscriptions.patch` queries fresh subscription membership
alongside product entitlements using the same Store session, account and market.
Android can validate a Game Pass child's parent plan when that plan is absent
from the product response. A failed membership query fails the refresh and
preserves the previous library. Empty membership results add no plan evidence;
valid plans already present in the product response retain their existing checks.

Build on AArch64 Ubuntu 24.04 with Rust from the lock, a C/C++ compiler, OpenSSL
development headers, pkg-config, protoc, CMake, Git, Python 3 and xz:

```sh
bash tools/xbox/build.sh
```

On macOS, `bash tools/xbox/build-local.sh` uses an AArch64 Ubuntu container.
`tools/build_local.sh` includes this step and caches it by recipe inputs. CI
builds the same recipe on `ubuntu-24.04-arm` and transfers the result to the APK
job. Cache hits still download that build's Xbox artifact before APK assembly.
The build runs account-free CLI, credential and streaming tests. It retains the
patched backend source archive and the checksum-verified runtime source archive
in the build cache and publishes both in CI's `xbox-corresponding-source`
artifact; distribute corresponding sources with published binaries.

`droiddeck-credentials.patch` replaces Linux Secret Service with a bounded,
Android abstract socket restricted to the backend process started by the host. The Android host encrypts every persistent
write using its existing Keystore cipher, verifies backend read-backs, and
rejects writes when Keystore is unavailable. No token values enter process
arguments, external storage, application logs or a plaintext credential file.
Account-generation invalidation prevents a signed-out child's later writes from
restoring credentials. Each backend gets its own socket. The host checks the peer UID, the PRoot tracer
owned by its live child, and an executable mapping of the backend file with a matching inode and
SHA-256 against the APK asset. PRoot's loader can occupy `/proc/<pid>/exe`, so
authentication checks the mapped backend image instead. A socket name alone grants no credential operations, including to another
copy of Xodus started by the guest.

Licensed launch-view preparation runs in an Android-owned Xodus child, which
never starts Wine or a game. Steam and DroidDeck launch paths can request
preparation for an installed Xbox title during a live session with an Xbox
account. The reply contains a random launch lease, a materialized executable
path, a game-service socket and the existing PlayFab game token; it contains no
account credential endpoint.
Leases bind product, account and session, expire without heartbeats, and release
idempotently. Cancellation, session stop and account changes revoke leases;
closing the child's stdin lets it remove its private view. Suspension preserves
live leases.
The Proton initialization and game processes do not inherit the raw credential
channel or command-file environment variables. The authenticated GDK bridge
remains available to the game. Like the other stores, games share DroidDeck's
Linux environment and Android UID. The process checks close the direct
credential-socket route; they do not isolate writable runtime files, prevent
same-UID process tampering, or provide a sandbox against malicious games.

Microsoft sign-in uses Nexus's private stdio protocol in an isolated Android
WebView process. Its page bridge is restricted to the allowed Microsoft/Xbox
HTTPS origins and the main frame; it has no credential-store or launcher API.
Cookies and page storage are cleared for each attempt. This protocol requires
an embedded host for CloudExperienceHost messages; opening its URL in an ordinary
Android browser does not complete it. Passkey behavior needs device testing.

`droiddeck-launch-view.patch` materializes all licensed encrypted images,
including DLLs, in a private temporary directory. Original package files remain
encrypted, and the temporary view is removed after the Wine child exits. Wine
receives normal files with no Xodus FD-map requirement. This costs temporary
storage proportional to the encrypted images. The app keeps the account service
alive for the session; an authenticated loopback bridge carries GDK requests.
Wine's builtin PE marker makes `WINEDLLOVERRIDES=n` reject the Nexus modules.
The extractor verifies their original hashes, then changes only that 16-byte DOS
stub marker to `DroidDeck native`; code, imports and exports stay unchanged. This
lets ARM64EC Wine load the AMD64 modules through its normal native DLL path.
Only each Xbox game's prefix receives these Windows modules. Existing
Steam games and other store prefixes retain their selected runtimes.

Cloud saves and in-game identity use the reused XGameRuntime/service interfaces;
those are game-dependent. Entitlements distinguish purchases from Game Pass
access, and the backend performs a fresh license check for downloads and launch.
The store lists PC MSIXVC games only. It does not emulate Xbox consoles or add
support for UWP/APPX packages. Update / verify refreshes an installed package through the normal licensed
streaming path. Manage content resolves manifest-related licensed PC DLC into
separate content roots and checks its parent identity after download. These
flows still require game/account validation; runtime API presence does not
establish that they work on Thor.

Upstream contribution restrictions are respected: these are launcher, storage,
build and file-lifecycle adapters in DroidDeck, not submitted reverse-engineering
contributions to Xodus.

Sources and licenses:

- Xodus: https://github.com/xodus-gaming/xodus (GPL-3.0, `LICENSE.xodus`).
- Nexus patches: https://github.com/domsvoboda/xodus-nexus (MIT, `LICENSE.nexus`).
- XGameRuntime/Wine modules: the exact source archive is identified in
  `runtime.lock.json` (LGPL-2.1, `LICENSE.LGPL-2.1`).
- `libnghttp2.dll`: nghttp2, MIT; its license is retained in the runtime source
  archive and is packaged as `LICENSE.nghttp2`.
