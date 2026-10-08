# `libdroiddeckstores.so` — the JNI contract

The Kotlin side of the store download engines mirrors this file exactly. Every symbol the library
exports is listed here with the class it binds to, the JNI signature, when it is called and on
which thread. The engines came from Bannerlator's `bl-steam-client` crate; the method names and
signatures are identical to Bannerlator's `BlGogDownload` / `BlEpicDownload` / `BlAmazonDownload`,
only the package and class names changed.

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
| `nativeStart(...)`: Long | `(I[Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;IIZLjava/lang/String;Ljava/lang/Object;)J` (last parameter is the listener; its static type is the app's choice) | `Java_com_droiddeck_launcher_stores_gog_GogNative_nativeStart` |
| `nativeCancel(handle: Long)` | `(J)V` | `Java_com_droiddeck_launcher_stores_gog_GogNative_nativeCancel` |
| `nativeRelease(handle: Long)` | `(J)V` | `Java_com_droiddeck_launcher_stores_gog_GogNative_nativeRelease` |

`nativeProbe()` always returns `1`; it exists so `isAvailable()` can catch `UnsatisfiedLinkError`
once at startup instead of mid-download.

`nativeStart` parameters, in order:

| # | Parameter | Meaning |
|---|---|---|
| 1 | `kind: Int` | `0` = `KIND_GEN2_CHUNKS`, `1` = `KIND_GEN1_RANGES` (anything else → gen2) |
| 2 | `depotManifests: Array<String>` | gen2: the inflated depot-manifest JSON strings, in fetch order, already filtered by product + language; gen1: the inflated build manifest (one element) |
| 3 | `cdnBase: String` | gen2: the resolved secure-link base (query string intact) or the unauthenticated dependency store base; gen1: `""` (file URLs are in the manifest) |
| 4 | `installDir: String` | absolute install directory |
| 5 | `skipPaths: Array<String>` | relative paths already completed by an earlier run of this same download (secure-link refresh re-run): counted done without re-hashing, no progress event |
| 6 | `caBundlePath: String` | see conventions |
| 7 | `maxWorkers: Int` | window ceiling for this loop (Bannerlator: its download-threads preference, 8, or 1 for redists) |
| 8 | `processWorkers: Int` | inflate/hash/write threads |
| 9 | `sortLargestFirst: Boolean` | base install = `true` (largest files first); DLC / dependency = `false` |
| 10 | `label: String` | log prefix, e.g. `"gog base"`; `""` → `"gog"` |
| 11 | `listener` | see below |

Returns `0` without any callback when: the listener is null, the JavaVM cannot be obtained, the
install dir or manifest list is empty, `cdnBase` is empty for gen2, or the worker thread cannot be
spawned. Otherwise the run is on a thread named `gog-dl` and `onComplete` fires exactly once.

Suggested listener: `com.droiddeck.launcher.stores.gog.GogNative.Listener` (an interface).

| Listener method | JNI signature | When |
|---|---|---|
| `onProgress(bytesDone: Long, bytesTotal: Long, filesDone: Int, filesTotal: Int, file: String, fileBytes: Long, verified: Boolean)` | `(JJIILjava/lang/String;JZ)V` | one file reached its final state. `verified = true` = resume-skip (existing file passed size+MD5; no bytes credited, `fileBytes` = its size); `false` = freshly assembled, size+MD5-verified and renamed (`fileBytes` = its decompressed size). `filesDone` counts both, `bytesDone` counts assembled bytes only. Process-pool / worker thread. |
| `onLog(line: String)` | `(Ljava/lang/String;)V` | engine diagnostics; the same line already went to logcat under the tag `GogNative`. Any native thread. |
| `onComplete(success: Boolean, cancelled: Boolean, linkExpiry: Boolean, error: String, bytesWritten: Long, filesDone: Int)` | `(ZZZLjava/lang/String;JI)V` | exactly once, from the worker thread, last. `linkExpiry = true` = the run died on an HTTP 401/403/404/500, the codes the GOG secure link returns once it expired: refresh the link and call `nativeStart` again with the finished files in `skipPaths`. `error` is `""` on success. |

On-disk protocol (what a resumed run, by either engine, relies on): a file is assembled into
`<file>.bhtmp`, then renamed over `<file>` once its size and MD5 match the manifest; an existing
`<file>` whose size+MD5 match is skipped. The engine writes nothing else.

## `com.droiddeck.launcher.stores.epic.EpicNative`

The chunk-fetch inner loop of an Epic install: fills `<installDir>/.chunks/<GUID>` with verified,
decompressed chunks for the files the manager says are pending. The Kotlin manager still fetches
and parses the manifest API JSON, downloads the manifest, selects files (install tags), runs the
delta/verify pass, assembles files from the chunk cache and does every post-install step. The
engine re-parses the same manifest bytes (ChunksV4 binary or legacy JSON), rebuilds the chunk plan
for the pending files, skips chunks already in the cache, and writes verified chunks with a
`.part` + rename protocol, so the on-disk state is the same whichever side fetched.

| Method | JNI signature | Symbol |
|---|---|---|
| `nativeStart(...)`: Long | `([BLjava/lang/String;[Ljava/lang/String;[IIJLjava/lang/String;IILjava/lang/Object;)J` (last parameter is the listener) | `Java_com_droiddeck_launcher_stores_epic_EpicNative_nativeStart` |
| `nativeCancel(handle: Long)` | `(J)V` | `Java_com_droiddeck_launcher_stores_epic_EpicNative_nativeCancel` |
| `nativeRelease(handle: Long)` | `(J)V` | `Java_com_droiddeck_launcher_stores_epic_EpicNative_nativeRelease` |

There is no `nativeProbe`; Bannerlator probes by catching the `Throwable` from `loadLibrary` /
the first `nativeStart`.

`nativeStart` parameters, in order:

| # | Parameter | Meaning |
|---|---|---|
| 1 | `manifest: ByteArray` | the raw manifest bytes the manager downloaded (binary ChunksV4 or JSON) |
| 2 | `installDir: String` | absolute install directory; the chunk cache is `<installDir>/.chunks` |
| 3 | `cdnPrefixes: Array<String>` | the CDN base URLs from the manifest API answer (`.../ChunksV4/<n>/` style); one or many, distinct hosts widen the window |
| 4 | `pendingFileIdx: IntArray` | indices into the manifest's file list of the files still to fetch (the manager's delta/verify result) |
| 5 | `expectedChunks: Int` | the manager's own needed-chunk count for a cross-check; `-1` = no cross-check |
| 6 | `expectedBytes: Long` | the manager's `Σ max(fileSize, 1)` for a cross-check; `-1` = no cross-check |
| 7 | `caBundlePath: String` | see conventions |
| 8 | `maxWorkers: Int` | window ceiling (Bannerlator: 8) |
| 9 | `processWorkers: Int` | inflate/verify/write threads |
| 10 | `listener` | see below |

Planning (manifest parse + plan + cross-check) runs synchronously on the calling thread, so a
plan failure returns `0` BEFORE any fetch; the manager then runs its own fallback loop. On `0`,
`onComplete(false, reason, 0)` has already been called synchronously (except for a null listener).
On success, `onPlan` has been called synchronously before `nativeStart` returns, then the fetch
runs on a new thread.

Suggested listener: `com.droiddeck.launcher.stores.epic.EpicNative.Listener` (nested interface,
as in Bannerlator).

| Listener method | JNI signature | When |
|---|---|---|
| `onPlan(chunksTotal: Int, bytesTotal: Long, chunkDir: String)` | `(IJLjava/lang/String;)V` | once, synchronously inside `nativeStart`, before any fetch: the plan the engine derived |
| `onProgress(bytesDone: Long, bytesTotal: Long, chunksDone: Int, chunksTotal: Int)` | `(JJII)V` | per accounted chunk (cached-skip or fetched); process-pool thread |
| `onLog(line: String)` | `(Ljava/lang/String;)V` | engine log line, already in logcat under `EpicNative`; any native thread (also synchronously from `nativeStart` for the start line) |
| `onComplete(success: Boolean, error: String, bytesCredited: Long)` | `(ZLjava/lang/String;J)V` | terminal, exactly once (worker thread; or synchronously when `nativeStart` returns `0`) |

Bannerlator's `BlEpicDownload.run(...)` wraps this in a blocking call that polls an
`AtomicBoolean` cancel flag every 250 ms, calls `nativeCancel` on cancel, waits up to 5 s for
`onComplete`, then `nativeRelease` in `finally`. That shape is worth keeping.

## `com.droiddeck.launcher.stores.amazon.AmazonNative`

The file-fetch pool of an Amazon Games install: one streamed HTTP GET per manifest file, SHA-256
checked, written as `<dest>.tmp` and renamed. The manager keeps manifest/auth/markers/post-install
and hands the engine a JSON plan.

| Method | JNI signature | Symbol |
|---|---|---|
| `nativeStart(...)`: Long | `(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IILjava/lang/Object;)J` (last parameter is the listener) | `Java_com_droiddeck_launcher_stores_amazon_AmazonNative_nativeStart` |
| `nativeCancel(handle: Long)` | `(J)V` | `Java_com_droiddeck_launcher_stores_amazon_AmazonNative_nativeCancel` |
| `nativeRelease(handle: Long)` | `(J)V` | `Java_com_droiddeck_launcher_stores_amazon_AmazonNative_nativeRelease` |

`nativeStart` parameters, in order:

| # | Parameter | Meaning |
|---|---|---|
| 1 | `planJson: String` | a JSON array, one object per manifest file: `{"relPath": "...", "url": "https://...", "size": <long>, "sha256hex": "..."}` (`sha256hex` may be `""` = no hash check). Every manifest file goes in: the engine applies the size-based resume-skip itself. |
| 2 | `installDir: String` | absolute install directory |
| 3 | `caBundlePath: String` | see conventions |
| 4 | `maxWorkers: Int` | window size; `<= 0` = the engine default (8, Amazon's own `MAX_PARALLEL`) |
| 5 | `processWorkers: Int` | sync verify+write threads (`<= 0` → 1) |
| 6 | `listener` | see below |

Returns `0` when the listener is null (no callback), or when `planJson` / `installDir` is empty
(`onComplete(false, "empty plan or install dir", 0)` fires synchronously first), or when the
listener `GlobalRef` cannot be taken (no callback). The engine keeps the `JavaVM` it takes from
the first `nativeStart`.

Suggested listener: `com.droiddeck.launcher.stores.amazon.AmazonNative.Listener` (an interface).

| Listener method | JNI signature | When |
|---|---|---|
| `onProgress(bytesDone: Long, bytesTotal: Long, filesDone: Long, filesTotal: Long)` | `(JJJJ)V` | once up front with the resume-skipped credit, then after each committed file (and as pieces stream in); `bytesDone` includes skipped files. Native threads. |
| `onLog(line: String)` | `(Ljava/lang/String;)V` | one diagnostic line (`engine=rust …`, `fetch-window …`, `summary …`). The Amazon engine does NOT write to logcat itself: the Kotlin side is the one place that logs these, so each line appears once. |
| `onComplete(success: Boolean, error: String, bytesWritten: Long)` | `(ZLjava/lang/String;J)V` | exactly once, when the run succeeds, fails or is cancelled (`error = "cancelled"`). |

Bannerlator's `BlAmazonDownload.runBlocking(...)` polls a cancel probe every 100 ms, calls
`nativeCancel` once, and `nativeRelease` in `finally` after `onComplete`; on thread interrupt it
cancels and waits up to 30 s for the run to wind down before releasing.

## Log tags

Lines go to logcat under `GogNative` and `EpicNative` (the Amazon engine logs only through
`onLog`). Bannerlator used `BL_GOG_DL` / `BL_EPIC_DL` / `BL_AMAZON_DL`; the line grammar
(`fetch-start`, `fetch-window`, `throughput`, `fetch-end`, `summary`) is unchanged, so its tuning
notes still read against a DroidDeck log.
