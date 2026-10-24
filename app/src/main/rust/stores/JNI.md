# `libdroiddeckstores.so` — the JNI contract

The Kotlin side of the store download engines mirrors this file exactly. Every symbol the library
exports is listed here with the class it binds to, the JNI signature, when it is called and on
which thread. The GOG engine and the shared fetch core came from Bannerlator's
`bl-steam-client` crate (method names and signatures match Bannerlator's `BlGogDownload`, only the
package and class names changed). The Epic module (`src/epic/`) and the Amazon module
(`src/amazon/`) are DroidDeck's own, each written from a functional spec (see
`docs/development/epic-cleanroom.md` and `docs/development/amazon-cleanroom.md`); their JNI
surfaces below are new.

Load it once with `System.loadLibrary("droiddeckstores")`. The library is built by Gradle
(`buildRustStores`, see `app/build.gradle`) and is absent from a `-PskipRust=true` build, so every
call site must be ready for `UnsatisfiedLinkError` on `loadLibrary` or on the first `external`
call and degrade to "store unavailable".

## Conventions that hold for all three engines

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

`nativeProbe()` always returns `1`; it exists so `isAvailable()` can catch `UnsatisfiedLinkError`
once at startup instead of mid-download.

`nativeStart` parameters, in order:

| # | Parameter | Meaning |
|---|---|---|
| 1 | `kind: Int` | `0` = `KIND_GEN2_CHUNKS`, `1` = `KIND_GEN1_RANGES` (anything else → gen2) |
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
SHA-1 checks; reusable chunks live, decompressed, as `<GUID>` (extra copies `<GUID>.<n>`, writes via
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

## Log tags

Lines go to logcat under `GogNative` and `EpicNative` (the Amazon module logs only through
`onLog`). Bannerlator used `BL_GOG_DL` for the GOG engine; the fetch core's line grammar
(`fetch-start`, `fetch-window`, `throughput`, `fetch-end`, `summary`) is unchanged, so its tuning
notes still read against a DroidDeck log.
