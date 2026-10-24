# Amazon engine pilot

The APK keeps GOG and Epic in `libdroiddeckstores.so`. Amazon is compiled separately
as `libdroiddeckamazon.so`, using the same reviewed Rust sources and shared download
implementation. Cargo features determine the exported stores; the source is not copied.
Both crates share one Cargo workspace and the existing lockfile/target cache.
The separate library duplicates some linked Rust dependencies. Measure combined
download size before extending this model to other stores.

`tools/stores/build-amazon.sh` builds Android ARM64/API 26 with the selected NDK and
locked Cargo dependencies. `engine.json` records interface version 1, library digest,
source/recipe input digests, Rust compiler and NDK metadata. `tools/packs/build.sh`
includes exactly that manifest and library in the Amazon pack. APK validation checks
the engine's ARM64 ELF header, JNI exports, dependency closure and digest, and rejects
Amazon exports left in the bundled store library. Runtime input checks reject stale
pack bytes. CI runs the standalone engine's host tests as a required step.

The signed APK pins the complete pack SHA-256. Existing main-only publication produces
an immutable content-addressed release; PR publication attaches the exact same-run pack
to the PR release. This metadata documents the build; it is not an independent signed
attestation or a reproducibility claim. Cargo's existing incremental cache is used;
this pilot does not introduce independent prebuilt engine reuse or faster CI guarantees.

Amazon sign-in downloads support on a worker before opening the browser. Signed-in
library sync and installation prepare it as needed. Other stores and ordinary startup
do not request the pack. Existing library cache, credentials, game paths, sidecars and
installed games are retained. Once installed, the pinned pack is usable offline.
New APKs reuse an existing pack when its digest is unchanged; changed pins download
their exact version rather than loading an arbitrary older engine. Loading verifies
the manifest/library and JNI interface before configuring the engine. Unpinned local
builds intentionally cannot borrow an arbitrary prior Amazon pack.
The existing local `DROIDDECK_SKIP_RUST=1` mode omits Amazon too. CI always requires
the engine and pack even when that local flag is present.

Device validation still needs a fresh Amazon sign-in, existing-account upgrade, failed
download/retry, offline relaunch, and cancel/resume installation. Robolectric integrity
tests and ELF checks do not substitute for Android native loading and live account tests.
