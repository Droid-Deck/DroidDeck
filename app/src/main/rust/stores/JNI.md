# `libdroiddeckstores.so` — the JNI contract

The Kotlin side of the store download engines mirrors this file exactly. Every symbol the library
exports is listed here with the class it binds to, the JNI signature, when it is called and on
which thread. The GOG engine, the shared fetch core and the Steam module (`src/steam/`, see its
`NOTICE.md`) are derived from WinNative's `wn-steam-client` crate (GPL-3.0-or-later,
https://github.com/WinNative-Emu/WinNative). The Epic module (`src/epic/`) and the Amazon module
(`src/amazon/`) are DroidDeck's own, each written from a functional spec (see
`docs/development/epic-cleanroom.md` and `docs/development/amazon-cleanroom.md`); their JNI
surfaces below are new, as is the Steam one.

Load it once with `System.loadLibrary("droiddeckstores")`. The library is built by Gradle
(`buildRustStores`, see `app/build.gradle`) and is absent from a `-PskipRust=true` build, so every
call site must be ready for `UnsatisfiedLinkError` on `loadLibrary` or on the first `external`
call and degrade to "store unavailable".

## Conventions that hold for all engines

- The `external` functions are declared `@JvmStatic` in a Kotlin `object` (a `JClass` arrives as
  the second JNI argument). Declaring them in a plain `object` without `@JvmStatic` also binds —
  the symbol name is the same — but keep `@JvmStatic` so the contract matches Bannerlator's.
- No overloads: the exported names are the short JNI form (`Java_<package>_<Class>_<method>`), so
  adding a second `nativeStart` with a different parameter list would NOT bind.
- Listener objects are only ever reached through `CallVoidMethod` by name + signature on the
  object that was passed to `nativeStart`. The library never does `FindClass`, so the listener's
  class may be any class (interface, nested interface, anonymous object) that declares the listed
  methods with exactly these signatures. The names suggested below are what the Rust doc comments
  use.
- Every listener method runs on a native thread (the run's worker thread, a process-pool thread
  or the throughput reporter) that is attached to the JVM as a daemon. Never touch Views directly;
  post to the main thread. A Java exception thrown by a listener method is cleared and ignored by
  the native side — it does not stop the run.
- `nativeStart` returns an opaque handle (`long`). `0` means the run did not start. The handle is
  a heap pointer: call `nativeRelease(handle)` exactly once per non-zero handle (after
  `onComplete`), never twice, never with a made-up value.
- `nativeCancel(handle)` is idempotent and safe at any time between `nativeStart` and
  `nativeRelease`. The run stops at its next scheduling point; `onComplete` still fires.
- `nativeRelease(handle)` is safe even while a cancelled run is still winding down: the worker
  thread owns its own references (cancel flag, listener `GlobalRef`).
- `caBundlePath`: a PEM bundle the HTTPS client adds as extra root certificates; `""` = the roots
  rustls ships with (webpki). Bannerlator extracts the system CA store to a file for this; on
  DroidDeck `""` is the sensible default unless a CDN fails TLS verification.
- `maxWorkers` is the ceiling of the adaptive in-flight request window; `processWorkers` is the
  number of sync inflate+hash+write threads. Bannerlator passes its "download threads" preference
  for both (8 by default).

## `com.droiddeck.launcher.stores.StoresNative`

| Method | JNI signature | Symbol |
|---|---|---|
| `nativeVersion(): String` | `()Ljava/lang/String;` | `Java_com_droiddeck_launcher_stores_StoresNative_nativeVersion` |

Returns the crate version from `Cargo.toml` (`"0.1.0"`). Call it from Setup, after
`System.loadLibrary("droiddeckstores")`, to prove the library loads and binds; show the string
next to the other component versions. May return `null` only if the JVM cannot allocate a string.

```kotlin
package com.droiddeck.launcher.stores

object StoresNative {
    @JvmStatic external fun nativeVersion(): String
}
```

## `com.droiddeck.launcher.stores.gog.GogNative`

GOG gen2 chunk engine (depot manifests → chunk fetch + zlib inflate + MD5) and gen1 (build manifest
→ per-file HTTP Range GET streamed to disk). One `nativeStart` = one download loop (base install,
DLC install, or a dependency-redist assembly). The Kotlin manager owns everything before (token,
builds, manifest head, product + language depot selection, secure-link resolution, disk guard) and
after it (marker files, exe resolution, DLC markers, redist orchestration).

| Method | JNI signature | Symbol |
|---|---|---|
| `nativeProbe(): Int` | `()I` | `Java_com_droiddeck_launcher_stores_gog_GogNative_nativeProbe` |
| `nativeStart(...)`: Long | `(I[Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;IIZLjava/lang/String;Ljava/lang/Object;)J` (last parameter is the listener; its static type is the app's choice) | `Java_com_droiddeck_launcher_stores_gog_GogNative_nativeStart` |
| `nativeCancel(handle: Long)` | `(J)V` | `Java_com_droiddeck_launcher_stores_gog_GogNative_nativeCancel` |
| `nativeRelease(handle: Long)` | `(J)V` | `Java_com_droiddeck_launcher_stores_gog_GogNative_nativeRelease` |

`nativeProbe()` returns `2`; it exists so `isAvailable()` can catch `UnsatisfiedLinkError`
once at startup instead of mid-download.

`nativeStart` parameters, in order:

| # | Parameter | Meaning |
|---|---|---|
| 1 | `kind: Int` | `0` = `KIND_GEN2_CHUNKS`, `1` = `KIND_GEN1_RANGES`, `2` = GOG maintenance (anything else → gen2) |
| 2 | `depotManifests: Array<String>` | gen2: the inflated depot-manifest JSON strings, in fetch order, already filtered by product + language; gen1: the inflated build manifest (one element) |
| 3 | `cdnBases: Array<String>` | gen2: **every** CDN base of the secure-link answer — one per `urls[]` entry, each resolved the way `parseCdnUrl` resolves `urls[0]` today (query string intact), in GOG's order — or the one unauthenticated dependency store base (an array of one); gen1: `emptyArray()` (file URLs are in the manifest). Blank entries are ignored and a base whose host already appeared is dropped (the first wins). The fetch core spreads the window across the distinct hosts with a per-host cap of `max(6, ceil(maxWorkers / hosts))`, rotating a failed chunk to another CDN; one base behaves exactly like the old single `cdnBase`. The start log line lists them as `hosts=a,b,c`. |
| 4 | `installDir: String` | absolute install directory |
| 5 | `skipPaths: Array<String>` | relative paths already completed by an earlier run of this same download (secure-link refresh re-run): counted done without re-hashing, no progress event |
| 6 | `caBundlePath: String` | see conventions |
| 7 | `maxWorkers: Int` | window ceiling for this loop (Bannerlator: its download-threads preference, 8, or 1 for redists) |
| 8 | `processWorkers: Int` | inflate/hash/write threads |
| 9 | `sortLargestFirst: Boolean` | base install = `true` (largest files first); DLC / dependency = `false` |
| 10 | `label: String` | log prefix, e.g. `"gog base"`; `""` → `"gog"` |
| 11 | `listener` | see below |

Returns `0` without any callback when: the listener is null, the JavaVM cannot be obtained, the
install dir or manifest list is empty, `cdnBases` holds no usable base for gen2, or the worker
thread cannot be spawned. Otherwise the run is on a thread named `gog-dl` and `onComplete` fires exactly once.

Suggested listener: `com.droiddeck.launcher.stores.gog.GogNative.Listener` (an interface).

| Listener method | JNI signature | When |
|---|---|---|
| `onProgress(bytesDone: Long, bytesTotal: Long, filesDone: Int, filesTotal: Int, file: String, fileBytes: Long, verified: Boolean)` | `(JJIILjava/lang/String;JZ)V` | one file reached its final state. `verified = true` = resume-skip (existing file passed size+MD5; no bytes credited, `fileBytes` = its size); `false` = freshly assembled, size+MD5-verified and renamed (`fileBytes` = its decompressed size). `filesDone` counts both, `bytesDone` counts assembled bytes only. Process-pool / worker thread. |
| `onBytes(bytesDone: Long, bytesTotal: Long)` | `(JJ)V` | byte progress between file completions: every 250 ms while the run is fetching, only when the value moved, plus once when the fetch ends. `bytesDone` = bytes of assembled files + bytes of files still in flight (gen2: their finished chunks, decompressed; gen1: what the current attempt has written; a retried attempt starts over, so nothing is counted twice); `bytesTotal` is the same total `onProgress` reports. It never goes past the next `onProgress.bytesDone` by more than the in-flight files and is equal to it once they finish. Drive the progress bar and speed from this; keep `onProgress` for file counts and per-file events. Called from the engine's ticker thread. Optional: a listener without it still works (the call fails quietly). |
| `onLog(line: String)` | `(Ljava/lang/String;)V` | engine diagnostics; the same line already went to logcat under the tag `GogNative`. Any native thread. |
| `onComplete(success: Boolean, cancelled: Boolean, linkExpiry: Boolean, error: String, bytesWritten: Long, filesDone: Int)` | `(ZZZLjava/lang/String;JI)V` | exactly once, from the worker thread, last. `linkExpiry = true` = the run died on an HTTP 401/403/404/500, the codes the GOG secure link returns once it expired: refresh the link and call `nativeStart` again with the finished files in `skipPaths`. `error` is `""` on success. |

On-disk protocol (what a resumed run, by either engine, relies on): a file is assembled into
`<file>.bhtmp`, then renamed over `<file>` once its size and MD5 match the manifest; an existing
`<file>` whose size+MD5 match is skipped. The engine writes nothing else.

Maintenance (`kind=2`) instead takes a single normalized plan in `depotManifests[0]`:
`files`, `oldFiles`, optional exact-build `patches`, per-product `links` and `patchLinks`,
and estimated `downloadBytes`. Credentials and these temporary links stay in memory.
It stages replacements under `.droiddeck-gog/stage`, revalidates completed chunks on
every run, and verifies each file before atomic replacement. `onBytes` counts actual
network bytes, including failed attempts. `onMaintenance(networkBytes, reusedBytes,
appliedBytes)` (`(JJJ)V`) reports the three separate cumulative counters. Existing
gen1/gen2 first-install progress semantics are unchanged.

## `com.droiddeck.launcher.stores.epic.EpicNative`

Everything Epic runs in the Rust `epic` module: the web-service calls (sign-in, refresh, exchange
codes, library, catalog, build information, shop shelves, achievements, cloud saves), manifest
parsing and the install engine. Tokens are passed in per call and never stored or logged by the
library; the Kotlin facade keeps them in the shared Keystore-sealed account store. Requests send
`DroidDeck/<versionName>` (`epic/net.rs::USER_AGENT_PRODUCT`; the version comes from
`nativeConfigure`), except achievements, which Epic only answers for its launcher agent
(`epic/net.rs::EPIC_LAUNCHER_USER_AGENT`).

| Method | JNI signature | Symbol suffix |
|---|---|---|
| `nativeConfigure(appVersion: String)` | `(Ljava/lang/String;)V` | `nativeConfigure` |
| `nativeCall(op: String, request: String): String?` | `(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;` | `nativeCall` |
| `nativeFetch(op: String, request: String): ByteArray?` | `(Ljava/lang/String;Ljava/lang/String;)[B` | `nativeFetch` |
| `nativeSend(op: String, request: String, body: ByteArray): String?` | `(Ljava/lang/String;Ljava/lang/String;[B)Ljava/lang/String;` | `nativeSend` |
| `nativeInspect(manifest: ByteArray, request: String): String?` | `([BLjava/lang/String;)Ljava/lang/String;` | `nativeInspect` |
| `nativeInstall(request: String, listener: InstallListener): Long` | `(Ljava/lang/String;Lcom/droiddeck/launcher/stores/epic/EpicNative$InstallListener;)J` | `nativeInstall` |
| `nativeCancel(handle: Long)` | `(J)V` | `nativeCancel` |
| `nativeRelease(handle: Long)` | `(J)V` | `nativeRelease` |

Full symbols are `Java_com_droiddeck_launcher_stores_epic_EpicNative_<suffix>`. Every call is
synchronous on the calling thread (network calls block: call them off the main thread) except
`nativeInstall`, which returns at once. A Rust panic is caught at the boundary (`null` / 0 result).

### `nativeCall` operations

`request` and the answer are JSON objects. A failure answers `{"error": "…"}`.

| op | request | answer |
|---|---|---|
| `auth.config` | – | `startUrl`, `redirectPrefix`, `webViewUserAgent` for the login WebView |
| `auth.codeFromPage` | `text` (the redirect page body as the WebView returns it) | `code`, or `{}` |
| `auth.signIn` | `code` | `access_token`, `refresh_token`?, `account_id`?, `display_name`?, `expires_at` (epoch ms) |
| `auth.refresh` | `refreshToken` | same as `auth.signIn` (absent fields: keep the stored ones) |
| `auth.exchange` | `accessToken` | `code` (single-use launch exchange code) |
| `library.owned` | `accessToken` | `records[]` of `appName`, `namespace`, `catalogItemId` (all pages; filtered to installable Windows games). A refused token answers `{"error":…, "signIn": true}`; any failed page fails the whole call |
| `catalog.details` | `accessToken`, `namespace`, `catalogItemId` | `details`: `title`, `developer`, `description`, `publisher`, `releaseDate`, `tall`, `wide`, `canRunOffline`, `dlc`, `mainGameId` |
| `catalog.attribute` | `accessToken`, `namespace`, `catalogItemId`, `name` | `value` (`""` = absent), or `unknown: true` when the service could not be asked |
| `build.info` | `accessToken`, `namespace`, `catalogItemId`, `appName` | `info`: the opaque build description (`manifest`, `buildVersion`, `deploymentId`, `cdns[]` of `base`/`dir`/`auth`) |
| `build.cdns` | `info` | `cdns[]` |
| `build.prereq` | `info` | `name`, `path`, `read` (downloads and parses the manifest) |
| `shop.shelves` | `country` | `new`, `deals`, `free`, `trending`: arrays in the `store_featured` item shape plus `allTags`; `art`: cover URLs from every promotion, for the signed-out wall |
| `achievements` | `accessToken`, `namespace`, `accountId`, `locale` | `list[]` of `id`, `name`, `description`, `hidden`, `unlocked`, `unlockedAt` (s), `icon`, `iconLocked` |
| `cloud.list` | `accessToken`, `accountId`, `appName` | `files[]` of `name`, `modified` (ms, -1 unknown), `hash`, `readLink` |
| `cloud.writeLinks` | `accessToken`, `accountId`, `appName`, `names[]` | `links`: name → write link |
| `launch.args` | `displayName`, `accountId`, `namespace`, `locale`, `appName`, `deploymentId`, `additional` | `args[]` |
| `launch.split` | `command` | `args[]` |
| `locale` | `language`, `region` | `key`, `tags[]`, `launchLocale` |

`nativeFetch` ops: `manifest.download` (`info`, optional `cdns`) → the manifest bytes;
`cloud.read` (`url`) → the file. `null` = failed. `nativeSend` op: `cloud.write` (`url`) with the
body → `{"ok": true}` or an error. `nativeInspect` parses manifest bytes: `{"op":"summary"}` →
`buildVersion`, `launchExe`, `prereqName`, `prereqPath`, `prereqRead`, `files[]` (`path`, `size`,
`tags`); `{"op":"select","tags":[…]}` → `indices[]` of the files an install with those tags gets;
`{"op":"launch"}` → the manifest's `buildVersion`, its `command` line and the `args[]` split from it.

### `nativeInstall`

`request` (JSON): `info` (from `build.info`), absolute `gameDir`, `scratchDir` (`""` or the internal
scratch cache for a card install), `storageLabel` (for space messages), `tags[]` (device install
tags), `maxWorkers` (network window, 1–128), `processWorkers` (decode/write threads), `caBundle`
(`""` = the roots rustls ships with).

An unreadable request returns `0` after a synchronous `onComplete(false, false, error, "")`.
Otherwise the run happens on a thread named `epic-install` (background priority) and
`onComplete` fires exactly once, last. `nativeCancel` is idempotent and may be called at any time
before `nativeRelease`; call `nativeRelease` exactly once per non-zero handle, after `onComplete`.
The Kotlin wrapper (`EpicNative.install`) polls its cancel flag every 250 ms, forwards it, and waits
for `onComplete` without a time limit: the run removes its unfinished stage files before it
completes.

| `InstallListener` method | JNI signature | When |
|---|---|---|
| `onStatus(text)` | `(Ljava/lang/String;)V` | manifest-stage status lines |
| `onCheck(doneBytes, totalBytes, files, filesTotal)` | `(JJII)V` | the delta pass, every 16 files and at its end |
| `onSizes(pendingBytes, diskBytes)` | `(JJ)V` | once pending work is known |
| `onProgress(written, total, files, filesTotal)` | `(JJII)V` | pending-file bytes written; at most every 50 ms plus per finished file; may arrive out of order from several threads |
| `onInstall(done, total, files, filesTotal)` | `(JJII)V` | every file is written and verified |
| `onLog(line)` | `(Ljava/lang/String;)V` | `manifest …`, `plan …`, the fetch core's lines, `summary …`; also in logcat under `EpicNative` |
| `beforeReplace(): Boolean` | `()Z` | once, before the first file replaces its destination; `false` or a throw fails the run with nothing replaced |
| `onComplete(success, cancelled, error, result)` | `(ZZLjava/lang/String;Ljava/lang/String;)V` | terminal. `result` (on success) is JSON: `buildVersion`, `launchExe`, `prereqName`, `prereqPath`, `prereqRead`, `deploymentId`, `diskBytes`, `bytesWritten`, `filesWritten`; `error` is `"cancelled"` on cancel |

On-disk protocol: game files are staged in `<gameDir>/.droiddeck-epic-staging/*.stage` (owner
marker `owner` = `DroidDeck Epic staging v1\n`) and renamed over their destination after size and
SHA-1 checks; a paused run keeps each unfinished file there as `*.part` with its written parts in `*.part.list`; reusable chunks live, decompressed, as `<GUID>` (extra copies `<GUID>.<n>`, writes via
`.part`) in `<gameDir>/.chunks` or the scratch directory.

## `com.droiddeck.launcher.stores.amazon.AmazonNative`

Everything Amazon Games runs in the Rust `amazon` module (`src/amazon/`, written from a functional
spec, see `docs/development/amazon-cleanroom.md`): device sign-in values and registration, token
refresh, deregistration, the entitlement library, the download lookup, manifest framing,
decompression and parsing, the install engine, and the launch details (exe, arguments, FuelPump
environment). Tokens are passed in per call and never stored or logged by the library; the Kotlin
facade keeps them in the shared Keystore-sealed account store.

User-Agents (all in `amazon/net.rs`): the sign-in WebView and `api.amazon.com` get a desktop
Firefox string (`BROWSER_USER_AGENT`); the distribution service (`gaming.amazon.com`) gets the
Windows games launcher's `User-Agent: AGSLauncher/1.0.0` plus its non-standard
`UserAgent: com.amazon.agslauncher.win/3.0.9202.1` header; the CDN (manifest and files) gets
`DroidDeck/<versionName>` (`USER_AGENT_PRODUCT`; the version comes from `nativeConfigure`).

| Method | JNI signature | Symbol suffix |
|---|---|---|
| `nativeConfigure(appVersion: String)` | `(Ljava/lang/String;)V` | `nativeConfigure` |
| `nativeCall(op: String, request: String): String?` | `(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;` | `nativeCall` |
| `nativeFetch(op: String, request: String): ByteArray?` | `(Ljava/lang/String;Ljava/lang/String;)[B` | `nativeFetch` |
| `nativeInspect(manifest: ByteArray, request: String): String?` | `([BLjava/lang/String;)Ljava/lang/String;` | `nativeInspect` |
| `nativeInstall(request: String, listener: InstallListener): Long` | `(Ljava/lang/String;Lcom/droiddeck/launcher/stores/amazon/AmazonNative$InstallListener;)J` | `nativeInstall` |
| `nativeCancel(handle: Long)` | `(J)V` | `nativeCancel` |
| `nativeRelease(handle: Long)` | `(J)V` | `nativeRelease` |

Full symbols are `Java_com_droiddeck_launcher_stores_amazon_AmazonNative_<suffix>`. Every call is
synchronous on the calling thread (network calls block: call them off the main thread) except
`nativeInstall`, which returns at once. A Rust panic is caught at the boundary (`null` / 0 result).
The Amazon module writes nothing to logcat itself; install lines reach the app through `onLog`.

### `nativeCall` operations

`request` and the answer are JSON objects. A failure answers `{"error": "…"}`.

| op | request | answer |
|---|---|---|
| `auth.start` | – | `startUrl` (the sign-in page with a fresh PKCE challenge), `userAgent` (for the WebView), `redirectMarker` (`openid.oa2.authorization_code=`), and the attempt's `serial`, `clientId`, `verifier` (kept by the flow object until `auth.register`) |
| `auth.register` | `url` (the return URL) or `code`, plus `serial`, `clientId`, `verifier` | `access_token`, `refresh_token`, `expires_at` (epoch ms), `device_serial`, `client_id`, `display_name`? — the credential record's own key names; `{"error":…, "noCode": true}` when the URL carries no code |
| `auth.refresh` | `refreshToken` | `access_token`, `expires_at` (the refresh token does not change) |
| `auth.deregister` | `accessToken` | `{}` (the answer is not interpreted) |
| `library.entitlements` | `accessToken`, `deviceSerial` | `total` (entitlements before filtering) and `games[]` of `productId`, `entitlementId`, `title`, `sku`, `art`, `hero`, `developer`, `publisher`, `dlc`, `parentProductId` — all pages, de-duplicated by product id, add-ons with a parent hidden (unless that hides everything), sorted by title. A refused token (HTTP 401/403, or 400 `NotAuthorizedException`) answers `{"error":…, "signIn": true}`; any failed page fails the whole call |
| `download.lookup` | `accessToken`, `entitlementId` | `downloadUrl`, `versionId` |
| `download.size` | `accessToken`, `entitlementId` | `bytes` (0 = unknown) |
| `launch.spec` | `gameDir`, `title` | `exe` (relative, `/`, `""` when the folder has no `.exe`), `args[]`, `source` (`fuel` / `score`), `warning` (or null) |
| `launch.env` | `entitlementId`, `sku`, `displayName` | `env[]` of `[name, value]` pairs, in order |

`nativeFetch` op: `cdn.get` (`url`, optional `accessToken` sent as `x-amzn-token`) → the body, or
`null`. `nativeInspect` parses a manifest object: `totalBytes`, `files`, or an error.

### `nativeInstall`

`request` (JSON): `accessToken`, `entitlementId`, absolute `gameDir`, `maxWorkers` (request window,
1–128), `processWorkers` (verify/write workers; the engine uses at least half the CPU cores),
`caBundle` (`""` = the roots rustls ships with).

An unusable request returns `0` after a synchronous `onComplete(false, false, error, "")`.
Otherwise the run happens on a thread named `amazon-install` (background priority) and
`onComplete` fires exactly once, last. `nativeCancel` is idempotent and may be called at any time
before `nativeRelease`; call `nativeRelease` exactly once per non-zero handle, after `onComplete`.
The Kotlin wrapper (`AmazonNative.install`) polls its cancel flag every 250 ms, forwards it once,
and waits for `onComplete` without a time limit: the run removes its `.tmp` files before it
completes.

| `InstallListener` method | JNI signature | When |
|---|---|---|
| `onStatus(text)` | `(Ljava/lang/String;)V` | manifest-stage status lines |
| `onCheck(doneBytes, totalBytes, files, filesTotal)` | `(JJII)V` | the check of files already on disk, at most every 200 ms and at its end |
| `onSizes(pendingBytes, totalBytes)` | `(JJ)V` | once the pending work is known |
| `onProgress(done, total, files, filesTotal)` | `(JJII)V` | bytes of the whole install that are complete (skipped files included); at most every 50 ms plus per placed file; never moves backwards on the native side, but may arrive out of order from several threads |
| `onLog(line)` | `(Ljava/lang/String;)V` | `manifest …`, `resume …`, `engine …`, the fetch core's lines, `summary …` (URL query strings removed) |
| `beforeReplace(): Boolean` | `()Z` | once, before the first file is put in place; `false` or a throw fails the run with nothing replaced |
| `onComplete(success, cancelled, error, result)` | `(ZZLjava/lang/String;Ljava/lang/String;)V` | terminal. `result` (on success) is JSON: `versionId`, `totalBytes`, `fileCount`, `filesWritten`, `bytesWritten`; `error` is `"cancelled"` on cancel, else a user-facing message |

On-disk protocol: each file is written to `<file>.tmp` beside its destination and renamed over it
after the size and SHA-256 checks; leftover `.tmp` siblings of manifest files are removed when a
run starts. A file whose size and SHA-256 already match is skipped. Empty files whose hash is
absent or that of empty input are created without a request. `.amazon_download_in_progress` is
created when a run starts and removed when it succeeds, which also creates
`.amazon_download_complete`; nothing reads either marker.

## `com.droiddeck.launcher.stores.steam.SteamNative`

Steam store engine (`src/steam/`): signs in with the Linux Steam client's saved login, lists the
account's games, plans and downloads a game straight into the Steam library folder the client
uses, then writes what the client writes on completion, so the client sees the game as installed
and does not update or verify it. Kotlin runs downloads and uninstalls only while the Steam client
is closed.

```kotlin
package com.droiddeck.launcher.stores.steam

object SteamNative {
    @JvmStatic external fun nativeSignIn(steamRootsJson: String, registryVdfPath: String, caBundlePath: String): String
    @JvmStatic external fun nativeSignOut()
    @JvmStatic external fun nativeLibrary(): String
    @JvmStatic external fun nativePlan(appId: Int, language: String): String
    @JvmStatic external fun nativeStart(appId: Int, language: String, steamRoot: String, gameLibraryHost: String, gameLibraryGuest: String, libraryRootsJson: String, journalDir: String, caBundlePath: String, maxWorkers: Int, processWorkers: Int, listener: Any): Long
    @JvmStatic external fun nativeCancel(handle: Long)
    @JvmStatic external fun nativeRelease(handle: Long)
    @JvmStatic external fun nativeUninstall(appId: Int, steamRoot: String, gameLibraryHost: String, gameLibraryGuest: String, libraryRootsJson: String): String
}
```

| Method | JNI signature | Symbol suffix |
|---|---|---|
| `nativeSignIn(steamRootsJson, registryVdfPath, caBundlePath): String` | `(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;` | `nativeSignIn` |
| `nativeSignOut()` | `()V` | `nativeSignOut` |
| `nativeLibrary(): String` | `()Ljava/lang/String;` | `nativeLibrary` |
| `nativePlan(appId, language): String` | `(ILjava/lang/String;)Ljava/lang/String;` | `nativePlan` |
| `nativeStart(...)`: Long | `(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IILjava/lang/Object;)J` | `nativeStart` |
| `nativeCancel(handle: Long)` | `(J)V` | `nativeCancel` |
| `nativeRelease(handle: Long)` | `(J)V` | `nativeRelease` |
| `nativeUninstall(appId, steamRoot, gameLibraryHost, gameLibraryGuest, libraryRootsJson): String` | `(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;` | `nativeUninstall` |

Full symbols are `Java_com_droiddeck_launcher_stores_steam_SteamNative_<suffix>`. Every `String`
answer is a UTF-8 JSON object with `"ok": true|false`; a failure carries `"error": "<short
reason>"`. `nativeSignIn`, `nativeLibrary`, `nativePlan` and `nativeUninstall` block on the
network or the disk: call them off the main thread. A Rust panic is caught at the boundary and
answers `{"ok":false,"error":"internal_error"}`.

Paths: "host" paths are Android paths the app can open; "guest" paths are what the Steam client
sees inside proot (what `libraryfolders.vdf` holds). `libraryRootsJson` is a JSON array of
`{"host": "...", "guest": "..."}`, one per library of the active client; it is used to find shared
app folders and `libraryfolders.vdf` entries, and the game library is added to it when missing.

### `nativeSignIn`

`steamRootsJson`: JSON array of host paths of Steam roots (arm64 and x86-64 clients), searched in
order. `registryVdfPath`: host path of `~/.steam/registry.vdf` (`""` = none). `caBundlePath`: see
conventions (used for the CM directory request).

Reads, never writes: `<root>/config/loginusers.vdf` (accounts), `<root>/local.vdf`
(`MachineUserConfigStore/Software/Valve/Steam/ConnectCache`, keys matched case-insensitively; the
entry whose key starts with the 8-hex-digit CRC32 of the lowercased account name), and
`registry.vdf` (`Registry/HKCU/Software/Valve/Steam/AutoLoginUser`). The account is
`AutoLoginUser` when it names a known account, else the one with `MostRecent` `"1"`, else the
newest `Timestamp`. The token is decrypted in memory (key = SHA-256 of the lowercased account
name; AES-256-ECB-wrapped IV, AES-256-CBC + PKCS#7), checked to be a Steam JWT for that
SteamID64, sent once in the CM logon and zeroized. It is never logged, written, renewed or
replaced.

Answers `{"ok":true,"account":"<AccountName>","steamId":"<SteamID64>"}` or
`{"ok":false,"error":…}` with `no_saved_login` (no account in any `loginusers.vdf`),
`remember_me_off` (`RememberPassword` `"0"`, or no ConnectCache entry: signed out),
`decrypt_failed` (the entry does not decrypt to this account's token), or `logon_failed: …`
(`logon_failed: eresult=<n> <name>` when Steam refused the logon, e.g. `eresult=5
InvalidPassword` for an expired token; otherwise a connection reason). One process-wide session:
a second call while it is logged on answers at once with the same account. After logon the
session sends no persona, friends or presence messages.

`nativeSignOut()` logs off and drops the session; it never touches the client's files.

### `nativeLibrary`

`{"ok":true,"games":[{"appId":291550,"name":"Brawlhalla","installDir":"Brawlhalla","type":"game","free":true}…]}`:
every app the account's licenses grant (package 0, the basic Steam license, is skipped) whose
PICS `common.type` is `game`, sorted by name. `free` = appinfo marks it free to play
(`common.freetogame` or `extended.isfreeapp` `"1"`). Loaded once per session (the first call
fetches PICS for every owned app and can take a while). `not_signed_in` without a session.

### `nativePlan`

What `nativeStart` would install for `appId` with `language` (Steam's language names, e.g.
`english`):

```json
{"ok":true,"appId":291550,"name":"Brawlhalla","installDir":"Brawlhalla","buildId":26465982,
 "language":"english","downloadBytes":…,"installBytes":1594368732,
 "depots":[{"depotId":291551,"manifestId":"4224536525702420323","size":1565156559,"dlcAppId":0}],
 "shared":[{"depotId":228988,"fromAppId":228980,"manifestId":"6645201662696499616","size":29212173}]}
```

Selection (what the client picks for a Windows game run through Proton): `config.oslist` empty or
containing `windows`; `config.osarch` empty or `64` (`32` only when no `64` depot exists);
`config.language` empty or the language (english when the game has no depot for the requested
language but has english ones; the answer's `language` is the one used); no `lowviolence`
depots; DLC depots (`dlcappid`) only when the DLC is owned; `depotfromapp`/`sharedinstall`
depots are listed under `shared` with the owning app (e.g. 228980 Steamworks Common
Redistributables), whose public manifest they use. Manifest gids are the public branch's, sizes
come from PICS, `buildId` is the public branch's. `installBytes`/`downloadBytes` include the
shared depots (they are not checked against what is already installed here). Errors:
`not_signed_in`, `not_owned`, `appinfo unavailable`, `no installable Windows depot`. A free app
the account does not own yet is planned without asking for its license.

### `nativeStart`

Parameters: `appId`, `language`, `steamRoot` (host path of the active client's Steam root; its
`steamapps/` and `config/` hold the two `libraryfolders.vdf` copies, `depotcache/` its cached
manifests), `gameLibraryHost`/`gameLibraryGuest` (the library to install into), `libraryRootsJson`,
`journalDir` (app-private directory for resume journals), `caBundlePath`, `maxWorkers` (request
window ceiling; `0` = 16), `processWorkers` (decrypt/decompress/write threads; `0` = half the
cores, 2 to 6; on SD cards the card writes, not the network, tend to be the limit), `listener`.

Returns `0` without any callback when the listener is null, `appId <= 0`, the JavaVM or a global
ref cannot be obtained, `steamRoot`/`gameLibraryHost`/`gameLibraryGuest`/`journalDir` is empty,
or the worker thread cannot be spawned. Otherwise the run is on a thread named `steam-dl`
(background priority) and `onComplete` fires exactly once, last. `nativeCancel` is idempotent and
safe until `nativeRelease`; call `nativeRelease` exactly once per non-zero handle, after
`onComplete`.

| Listener method | JNI signature | When |
|---|---|---|
| `onProgress(doneBytes: Long, totalBytes: Long, phase: String)` | `(JJLjava/lang/String;)V` | `phase` is `preparing` (sign-in check, plan, takeover, keys, manifests; `0`/`0`), `downloading` (the game's depots), `shared` (shared depots) or `registering`. `doneBytes`/`totalBytes` count the depot files (decompressed) of everything this run installs, shared depots included; chunks already correct on disk count as done. At most every 250 ms, plus at every phase change and depot end. |
| `onComplete(ok: Boolean, result: String)` | `(ZLjava/lang/String;)V` | `{"ok":true,"alreadyInstalled":false}` after a download, `{"ok":true,"alreadyInstalled":true}` when the game was already fully installed at the current build (nothing downloaded or written), or `{"ok":false,"error":"…"}`: `cancelled`, `not_signed_in`, `already_running` (a run for the same app is active), `not_owned`, `free_license_refused`, `library_not_in_libraryfolders` (no `libraryfolders.vdf` entry has the guest path), `not_enough_space: need … MiB, have … MiB`, `access_denied: depot <id>`, or a short message for network and disk failures. |

What a run does, in order:

1. Plans as `nativePlan`; a free app the account does not own is first added to the account with
   `CMsgClientRequestFreeLicense`.
2. Takeover check on `<gameLibraryHost>/steamapps/appmanifest_<appId>.acf`: absent → fresh
   install; `StateFlags` has 4 and none of the update/download/validate bits and `buildid` is the
   current build → `alreadyInstalled`; fully installed at another build → files are checked and
   updated in place and the manifest is merged; otherwise (a half-finished client download) the
   manifest, `steamapps/downloading/<appId>` and `steamapps/temp/<appId>` are deleted (and any
   journal for the app) before downloading.
3. Downloads the game's depots in place into `<gameLibraryHost>/steamapps/common/<installdir>`:
   no temp files, no space reservation, each file trimmed to its exact size when its last chunk
   lands. A chunk used several times is fetched once. Files already on disk are read back chunk by
   chunk and only wrong or missing chunks are fetched (resume, update, taking over a client's
   partial files).
4. Shared depots go into the library that holds `appmanifest_<fromAppId>.acf` (else the Steam
   root's own library), folder `steamapps/common/<that app's installdir>`; a shared depot already
   installed at the planned manifest is skipped.
5. Registers: writes `appmanifest_<appId>.acf` (keys in the client's order: `appid`, `Universe`,
   `name`, `StateFlags` 4, `installdir`, `LastUpdated`, `LastPlayed`, `SizeOnDisk` (the game's
   depots), `StagingSize`, `buildid`, `LastOwner` (SteamID64), `DownloadType` 1, `UpdateResult`,
   `BytesToDownload`/`BytesDownloaded` (compressed size of every installed depot, shared
   included), `BytesToStage`/`BytesStaged` (installed size of the game's depots plus the shared
   depots installed in this run), `TargetBuildID`, `AutoUpdateBehavior`,
   `AllowOtherDownloadsWhileRunning`, `ScheduledAutoUpdate`, `InstalledDepots`
   (`manifest`, `size`, plus `dlcappid` for DLC depots), `InstallScripts` when a game depot has
   one, `SharedDepots`, `UserConfig`/`MountedConfig` `language`), merging into an existing file
   (unknown keys, `LastPlayed` and the auto-update settings are kept). Updates the shared app's
   manifest (`SizeOnDisk`, `InstalledDepots`, `InstallScripts` entry such as
   `"228988" "_CommonRedist\\vcredist\\2019\\installscript.vdf"`; created when missing).
   Adds `"<appId>" "0"` under `apps` of the matching entry in both
   `<steamRoot>/steamapps/libraryfolders.vdf` and `<steamRoot>/config/libraryfolders.vdf` (the
   client fills in the size later); nothing else there changes. All files are written through a
   temp file + rename.

Never written: `config.vdf`, `localconfig.vdf`, `registry.vdf` (the client regenerates them) and
`depotcache/` (the client keeps manifests there with decrypted file names, not in the CDN form
this engine caches; it fetches them when it needs them).

On-disk protocol of the journal: `<journalDir>/<appId>/depot.config` (JSON: `buildId`, the
finished manifest per depot or an in-progress sentinel) and `<depot>_<manifest>.manifest` (raw
CDN manifests). A cancelled or failed run keeps it, and the next `nativeStart` for the same app
resumes (finished depots are skipped, the rest is checked on disk); a journal written for another
build is dropped. It is deleted after a successful registration. Nothing extra is ever left in
the game folder.

### `nativeUninstall`

Matches the client's own uninstall: deletes `<gameLibraryHost>/steamapps/common/<installdir>` and
the game's `appmanifest_<appId>.acf`, `<steamRoot>/depotcache/<depot>_<manifest>.manifest` for
the game's `InstalledDepots` and `<steamRoot>/steamapps/shadercache/<appId>`; keeps
`steamapps/compatdata/<appId>` (the Proton prefix, i.e. saves). For each of the game's
`SharedDepots` that no other installed game in any library lists, deletes that depot's files from
the shared app's folder (empty folders stay, as with the client) and removes its
`InstalledDepots` and `InstallScripts` entries and its size from the shared app's manifest. The
file list comes from the client's `depotcache` copy of that manifest, else from the CDN through
the live session; without either the shared depot is left installed and listed in `keptShared`.
Removes the app from `apps` in both `libraryfolders.vdf` copies.

Answers `{"ok":true,"removedShared":[228988],"keptShared":[]}`, or `{"ok":false,"error":…}`:
`not_installed` (no app manifest), `bad installdir in app manifest`, `bad arguments`, or a disk
error.

## Log tags

Lines go to logcat under `GogNative` and `EpicNative` (the Amazon module logs only through
`onLog`). Bannerlator used `BL_GOG_DL` for the GOG engine; the fetch core's line grammar
(`fetch-start`, `fetch-window`, `throughput`, `fetch-end`, `summary`) is unchanged, so its tuning
notes still read against a DroidDeck log.

The Steam module logs under `DroidDeckSteam` and never logs a token; its chunk downloads go
through the fetch core with the label `steam depot=<id>`.
