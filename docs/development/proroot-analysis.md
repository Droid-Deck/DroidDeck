# proroot: what it does and what DroidDeck can take from it

Static analysis of [coderredlab/proroot](https://github.com/coderredlab/proroot) v1.2.8
(`c01039ab`, 2026-06-17). The repository holds only five arm64 binaries and a README. The licence is
proprietary, and redistributing modified binaries is not allowed, so only the ideas below are
usable, not the code. The binaries were read with the NDK's `llvm-readelf`, `llvm-nm`,
`llvm-objdump` and `llvm-strings`. `libproroot-stub-loader.so` and `libproroot-bridge.so` still carry
`.symtab`, and `libproroot-runtime.so` exports about 260 names, so the function names below are
proroot's own.

## Architecture

proroot does not use ptrace. Everything runs inside the guest process.

| file | kind | role |
|---|---|---|
| `libproroot.so` | bionic PIE executable | Launcher. Parses `-r/-b/-w/-0/--link2symlink`, writes a config file (`.proroot-config-<pid>`, passed down as `PROROOT_CFG_FD`), and execs the guest through `libproroot-linker.so` or the stub loader. |
| `libproroot-linker.so` | freestanding PIE, run as an executable | A from-scratch glibc dynamic linker ("cleanroom linker") that replaces `ld-linux-aarch64.so.1`. It maps the program and its `DT_NEEDED` libraries, relocates them, sets up static TLS, and stubs `_dl_*` and `__tunable_get_val`. It finds `_rtld_global_ro` through a table of offsets per glibc version ("no offset table for glibc X, patches skipped"). It also installs a SIGSYS handler with an allow-list. |
| `libproroot-stub-loader.so` | freestanding static PIE | Userland `exec`. It maps the target and its `PT_INTERP` itself and builds the stack and auxv. It then **rewrites the `svc #0` sites** in the loaded images (`proroot_svc_scan`, `proroot_svc_patch_apply`, `select_trampoline_for_site`) to branch into per-syscall trampolines (`proroot_tramp_openat`, `_statx`, `_faccessat2`, `_clone3`, `_rt_sigaction`, `_shmget`, ...). Sites it cannot patch fall back to an in-process seccomp `RET_TRAP` filter with a SIGSYS handler ("trampoline fallback, activating trap-mode fallback"). |
| `libproroot-runtime.so` | glibc `LD_PRELOAD` | Hooks about 200 libc entry points: path translation, fake root (`fake_id0_*`), link2symlink, exec routing, SysV shm, netlink, `/proc` and `/sys` views, `brk`, a fake DRM/KMS and GBM device plus libseat for compositors, X11 and Unix-socket fixups. After load it **patches the svc sites in glibc itself** (`patch_libc_syscalls`). |
| `libproroot-bridge.so` | static `ET_EXEC` at `0x7000000000` | The "trampoline" for static guest binaries. It installs a path-trap seccomp filter plus a SIGSYS handler, maps the target, and serves a virtual `/proc` (`status`, `stat`, `cmdline`, `environ`, `mounts`, `mountinfo`, `fdinfo`, `auxv`). |

The executable files all live in `nativeLibraryDir`, which is the only place an app may exec from.
That is why the dynamic linker and the static loader each have their own userland loader. proot
handles the same constraint with `libproot-loader.so`.

## How the in-process syscall interception works

1. **Patching svc sites in glibc** (`patch_phdr_callback`, called through `dl_iterate_phdr`):
   - It scans only an allow-list of objects: `libc.so.6`/`libc-`, `ld-linux`, `libpthread`,
     `librt`, `libdl`. Main executables get patched early only for listed programs (apt, dpkg,
     curl, openssl, node, chromium/electron/playwright, passwd/useradd/...). Other programs are
     patched later (`log_deferred_libc_patch`, `PROROOT_FORCE_EARLY_PATCH`).
   - In each executable `PT_LOAD` it finds `svc #0` (`0xd4000001`). It checks the instructions
     before it for a `mov x8, #nr` it recognises. One fixed glibc `openat` call sequence gets
     special treatment: its `bl` is redirected to a relay (`emit_openat_call_relay_entry`).
   - The site is replaced with a `b`/`bl` to a relay stub on a page allocated within ±128 MiB
     (`alloc_relay_page`). Sites out of range are logged as `bl-out-of-range`; `large-image
     multi-cover tramp` places extra relay pages for big images.
   - **Write path:** it first tries `mprotect(PROT_READ|PROT_WRITE|PROT_EXEC)` on the segment.
     If that is refused, it `pwrite`s the 4-byte instruction through `/proc/self/mem`. Either way
     it then calls `__clear_cache`. The log line counts sites by kind: `patched %d seccomp + %d
     path (%d relay) + %d brk`.
2. **In-process SIGSYS fallback**:
   - A BPF filter allows syscalls whose instruction pointer is inside proroot's own code and
     returns `SECCOMP_RET_TRAP` for the path, exec, clone and wait syscalls from anywhere else.
     The IP check is the same idea as our patch 0014, with TRAP instead of TRACE.
   - The handler emulates the call and writes the result into the signal frame. Guest SIGSYS
     handlers are kept and chained (`proroot_record_user_sigsys`, `invoke_user_sigsys`).
3. **Keeping SIGSYS deliverable**, the objection in option 4 of
   [proot-performance](proot-performance.md):
   - The `sigprocmask` and `pthread_sigmask` hooks call `sigtrap_unblocked_mask`, which
     `sigdelset`s `SIGSYS` (and `SIGTRAP` for some guests) from any `SIG_BLOCK`/`SIG_SETMASK` set.
   - `rt_sigaction` is guarded so the guest cannot replace the handler (`proroot_tramp_rt_sigaction`,
     the `sigaction_guard` trace points).
   - The raw `rt_sigprocmask` that glibc makes internally, for example `pthread_create` blocking
     every signal, goes through a patched svc site. The stub loader and the runtime both compare
     against syscall 135.
4. **Android-blocked syscalls are answered in-process.** The stub loader dispatches
   `set_robust_list`, `rseq`, `faccessat2`, `clone3`, `futex_waitv` and `seccomp`. The runtime
   emulates SysV shm (`emu_shm*`), `set*id`/`get*id` under `-0`, and `brk`
   (`emulate_guest_brk`, over a reserved mapping).

## Compared with DroidDeck

| | DroidDeck (proot + 0001–0018 + `libblfastpath.so`) | proroot |
|---|---|---|
| exec, `PT_INTERP`, `/proc/self/exe` | proot's loader under ptrace, 0.4–0.8 ms per exec | Its own loaders, with no tracer; also needs its own ld.so with a glibc offset table |
| path calls through libc wrappers | fast path, about 0.6–1.9 µs | runtime hooks |
| path calls from glibc internals (ld.so, fts, realpath, nss, `__open_nocancel`) and raw `svc` | **still go to proot (15–60 µs)** | **patched svc sites** |
| Android-blocked syscalls (`set_robust_list`, `faccessat2`, `clone3`, ...) | SIGSYS stop answered by proot (0016 etc.), one ptrace round trip and one signal round trip each | in-process |
| `brk` | proot emulation, a trap per call (mitigated by `top_pad`) | in-process emulation |
| correctness fallback | proot is always there | limited in-process seccomp/SIGSYS handling; no general tracer fallback, so calls outside the filter can run unconfined or be emulated incorrectly |
| long Unix socket paths | fast path `/proc/self/fd/N/name` aliases | short socket aliases (`proroot_create_short_unix_socket_alias`) |
| rseq | `GLIBC_TUNABLES=glibc.pthread.rseq=0` | trampoline |

proroot is not a model for replacing proot. It has no tracer to fall back on, so a missed call
site or an unexpected glibc build silently breaks isolation or correctness. Its own linker depends
on glibc internals for each version. Proton, Wine, FEX and Steam's self-updating ELFs are a much
wider surface than the Node, Python and Chromium workloads proroot was tested on. Its value for
DroidDeck is as a fast path *in front of* proot.

## What is worth taking

1. **Patch glibc's svc sites from `libblfastpath.so`'s constructor.** This is the largest gain
   and the cheapest way to do option 6 in [proot-performance](proot-performance.md) ("a proot-aware
   glibc") without rebuilding glibc.
   - For `libc.so.6` (and `ld-linux-aarch64.so.1` for runtime `dlopen`), rewrite each `svc #0`
     that follows a recognised `mov x8, #nr` for the path syscalls the fast path already handles.
     The rewrite is a `b` to a relay that calls the existing translator.
   - The relay issues the syscall from the `0xffff00000` trampoline page, which 0014 already lets
     through. Anything the translator declines is re-issued from an svc in the relay page, outside
     the trampoline, so proot traps it exactly as it does today. Correctness still rests on proot.
   - This covers fts (`find` over 2.7k files, unchanged today), realpath, nss, `__open_nocancel`,
     and `dlopen` after start-up.
   - Library loading at start-up, before the constructor runs, still traps.
2. **Answer the Android-blocked syscalls through those relays.**
   - `set_robust_list` and `get_robust_list`: keep the head in TLS, as 0016 already does in
     proot.
   - `faccessat2`: answer `ENOSYS`, or translate it when flags are 0, as the wrapper does.
   - `clone3`: answer `ENOSYS` before Android 15, so glibc falls back to `clone`.
   - `futex_waitv`: droiddeck-fsync already handles this.
   - Each one removes a SIGSYS stop plus a ptrace round trip, about 30–50 µs. `set_robust_list`
     alone is one per thread, which matters for Steam/CEF and Wine thread churn.
3. **The `/proc/self/mem` write as a fallback for patching.** If the app domain refuses
   `mprotect(PROT_WRITE|PROT_EXEC)` on rootfs (`app_data_file`) text, i.e. an `execmod` denial,
   a write through `/proc/self/mem` makes the page copy-on-write without an `mprotect`. That page
   is never writable and executable at once. Check this on the Thor before relying on it. The
   same question is still open for the trampoline page (`execmem`).
4. **Keep SIGSYS unblocked if in-process `SECCOMP_RET_TRAP` is ever used.** Strip `SIGSYS` in the
   `sigprocmask`/`pthread_sigmask`/`rt_sigprocmask` paths, and keep the handler installed while
   chaining the guest's. This only becomes safe once item 1 covers glibc's internal
   `rt_sigprocmask`. It is a prerequisite, not a recommendation to switch.
5. **Cache patch sites by build-id.** proroot rescans each image on every exec and defers patching
   for most programs to keep exec cheap. A scan of libc's roughly 1.5 MiB of text is cheap, but a
   per-build-id site table, written once in `/tmp` by the fast path, would make it free.

Not worth taking: the cleanroom ld.so and its glibc offset table; the userland exec loader (proot
already does this); the fake DRM/GBM/libseat device (DroidDeck has its own compositor path);
netlink and SysV shm (`netif.c` and `sysv.c` already cover these); and the long-socket aliasing
(the fast path covers it).

## Open questions before prototyping item 1

- Does the app domain allow `mprotect(RWX)` on a private mapping of an `app_data_file`? Does a
  `/proc/self/mem` write work under the app's SELinux context and proot's own `ptrace` attach?
  `/proc/self/mem` writes need `PTRACE_MODE_ATTACH` on self, which is always allowed.
- How many `libc.so.6` path syscall sites fall outside a recognisable `mov x8, #nr` window? For
  example, `syscall()` takes the number in a register, which needs the existing `syscall()` hook.
- Patched pages become private copies, about 4 KiB per patched page per process. Measure RSS
  across the Steam/CEF tree.
- Equivalence: run `bench/equiv.py` plus a `find`/`realpath`/nss workload with `PROOT_FP_STATS`.
  Compare the trap counts from patch 0018 before and after.

## First implementation and Thor validation (2026-10-27)

`tools/proot/fastpath/svc-patch.h`, included by `fastpath.c`, implements the first
slice of item 1. It runs automatically in fresh processes using the replacement
`libblfastpath.so` when the ordinary fast-path configuration and tracer-key check
succeed. Set `PROOT_FP_PATCH_SVC=0` to disable it for troubleshooting or comparisons;
`PROOT_FP_PATCH_SVC=1` also explicitly enables it. It skips processes that already have multiple threads,
and declines SVE/SME hardware because the relay preserves the base ARM64 register state.

The scanner visits executable segments of the loaded `libc.so.6` and
`ld-linux-aarch64.so.1`. It only patches `openat` and `newfstatat` sites with an
unshifted `movz x8/w8, #nr` within the preceding eight instructions. Intervening
instructions must belong to a small allow-list of argument moves/sign-extension,
immediate moves, stack loads/stores and NOPs that leave x8 unchanged. Branches,
calls, x8 writes and unknown instructions stop recognition. This includes libc's
non-adjacent O_CREAT sequences. Register-number dispatchers and other syscall
numbers remain under PRoot. No glibc version offsets, proroot code, or modified
proroot binaries are used.

Each replacement is a four-byte `b` into a nearby relay. Relay pages are filled while
writable, then changed to RX before publishing any branch. Text writes use
`/proc/self/mem`; failure leaves the site unpatched. The relay preserves x1–x30, all
32 SIMD registers, NZCV, FPCR/FPSR and the caller's `errno`, returning a raw kernel-style
result in x0. It calls the existing path translator, using the existing allowed
trampoline for translated calls. Declined and recursive calls execute an ordinary
`svc` in this library, where PRoot still intercepts them. No SIGSYS handler or new
seccomp filter is installed. `PROOT_FP_STATS=1` adds `fastpath-svc` exit counters.

The connected AYN Thor was running runtime r9, glibc 2.43, with a 4 KiB page size in
`u:r:untrusted_app_27:s0`. `bench/svc-probe.c`, run through the app's guest bridge,
confirmed all of the following while PRoot was attached:

- An RX private mapping of the rootfs libc accepted `mprotect(RWX)`.
- `/proc/self/mem` accepted a changed byte in that RX mapping; readback and restoration
  succeeded, and the backing file stayed unchanged.
- An anonymous RW mapping could be changed to RX.
- libc contained 415 `svc #0` sites: 327 with an adjacent number load, 76 with a
  number load only within the preceding eight instructions, and 12 with neither.
  The linker contained 56 sites, 45 adjacent and 11 nearby-only. Nearby counts are
  scan statistics, not proof that the number remains live at the syscall.

The initial adjacent-only scanner patched nine sites; the final scanner patched
14, including file-creation paths, across libc and the linker. It used two relay
pages in standalone validation processes. `bench/svc-check.c` exercises the
actual emitted relay and checks raw success/error results, errno, x1–x30, q0–q31,
NZCV, FPCR/FPSR, four simultaneous threads, the multi-thread patching guard, and fork.
Null arguments and unsupported flags fall back to PRoot. The instruction decoder
and exact ±128 MiB branch limits also have a host regression test.

Default-policy validation on the Thor confirmed that an unset `PROOT_FP_PATCH_SVC`
and explicit `1` both patched 14 sites; explicit `0` left all 415 libc and 56 linker
syscall sites unchanged. The register/thread/fork check also passed with the
environment flag unset.

Five alternating pairs of `bench/svc-workload.c` runs compared the same replacement
library with patching off/on, with fast-path stats enabled in both. The workload
gets `fstatat` and `__open64_nocancel` directly from libc, bypassing the preload's
public wrappers, and makes 5,000 of each call against a prepared regular file:

| median | wrappers only | svc patching |
|---|---:|---:|
| internal `fstatat` | 38.6 µs | 3.0 µs |
| internal open + close | 39.3 µs | 5.8 µs |
| voluntary context switches over both loops | 10,000 | 0 |

### SD allocation and writes

`bench/svc-storage.c` separates the operations relevant to Steam downloads. Three
alternating pairs ran on internal `/tmp` and the selected `/mnt/droiddeck-sd` library,
with Steam idle and no simultaneous local Steam download. Each fresh directory held 64 long-named files
extended to 256 KiB, followed by 1,000 libc-internal stats and a 64 MiB sequential
write followed by `fdatasync`. Both modes used the same replacement library; only
`PROOT_FP_PATCH_SVC` changed. Medians on SD were:

| phase | wrappers only | svc patching |
|---|---:|---:|
| create/extend/close 64 files | 1.500 s | 1.486 s |
| 1,000 internal stats | 61.2 ms | 5.4 ms |
| 64 MiB write calls | 43.9 ms | 40.9 ms |
| final `fdatasync` | 2.514 s | 2.539 s |

Internal allocation fell from 7.1 ms to 1.9 ms; internal stats fell from 39.5 ms
to 2.5 ms. Internal `fdatasync` varied from 36 to 164 ms across individual runs.
The patched SD workload recorded 1,065 internal calls handled in-process, covering
64 file creations, 1,000 stats and the streaming-file creation. Per-phase medians
are independent and should not be added as a median whole-run duration.

The SD metadata gain does not establish faster Steam downloads. File creation
improved by only a few percent, and write/flush speed did not improve in this probe.
The remaining SD cost is much larger than the saved syscall-interception time.
The earlier [Thor SD investigation](thor-sd-storage-investigation.md) likewise
identified native exFAT metadata waits; the current benchmark does not independently
attribute those waits to a particular mount flag. A real Steam install comparison
must separate preallocation, transfer/staging and final commit before claiming an
end-to-end improvement.

### Real Steam downloads to SD

Four fresh installs of owned, initially uninstalled **7 Billion Humans** (792100)
used library folder 1, `/mnt/droiddeck-sd`, and were uninstalled between trials.
Each downloaded 228,935,584 bytes and staged 237,717,350 bytes in 19 files, ending
at BuildID 8016557, depots 792101/5052600457178655633 and
792104/8245917369753676198. Every trial logged zero reused/stored/delta bytes at
the start and completed with `No Error`. Existing installed games were retained.

The repeat off/on pair used the exact same replacement library, SHA-256
`ab605c021aee271cd49fa1cb337d7ff0c762dbac198b711e9818639c0cd4b17d`.
The first off run used the earlier adjacent-only build, with patching disabled.
Only the patching environment flag changed for the final pair. Times below come
from Steam's one-second-resolution `content_log.txt`; total starts at Reconfiguring
and ends at scheduler completion, excluding session startup:

| trial | patching | preallocate | download/stage | commit to completion | total |
|---|---|---:|---:|---:|---:|
| first baseline | off | 1 s | 123 s | 1 s | 135 s |
| first patched | on | <1 s | 47 s | 1 s | 50 s |
| repeat baseline | off | <1 s | 30 s | 1 s | 33 s |
| repeat patched | on | <1 s | 29 s | 2 s | 32 s |

Reconfiguration accounts for the remaining total time. The first baseline
overlapped another Steam client's network download; that client's telemetry must
not be mistaken for the Thor's. Later samples were filtered to local client `0`.
The repeat pair reached similar network rates, roughly 7–8 MB/s while transferring.
All four attempts logged the same unsupported large-file preallocation retry.

**This does not demonstrate a useful SD-download speedup.** The apparent initial
135→50 second gain disappeared when baseline was repeated. The final 33 versus
32 seconds is within this small experiment's timing/network variability. This
19-file title also does not settle performance for games with thousands of loose
files. Metadata calls clearly improve, while SD creation/writeback remain the
stronger next targets. No network settings, SD mount flags or root privileges
were changed for these tests.

The internal-call equivalence workload, including fts, realpath, reentrant NSS,
runtime dlopen, missing paths and fallback errors, matched `PROOT_FP_OFF=1` at both
the default parent-cache TTL and TTL 0. The existing `equiv.py` also matched after
normalizing random/thermal read payloads to their lengths, volatile PID values in
the `/proc` directory listing, and tolerating Android's
denied `/dev` directory read. Its root fixtures were relocated into a temporary tree.

`equiv2.py` used temporary root/SD fixtures, normalized `/proc/<self-pid>` output,
and tolerated the SD filesystem's unsupported symlink creation. Its patched output
matched the wrapper-only output at both TTLs. **It did not fully match PRoot:**
binding a Unix socket in the selected SD bind returned EPERM with the existing
wrappers, whereas PRoot succeeded. This mismatch reproduced with svc patching off;
it is a separate existing fast-path socket issue. The suite is not a clean full
equivalence pass. `equiv3.py` was not run because it rewrites live NSS configuration.

A fresh Steam session using the override and `PROOT_FP_PATCH_SVC=1` rendered its
library UI. Sonic Mania (584400) gained focus and rendered its title screen. This
is startup smoke coverage, not gameplay or a Steam startup-speed measurement.
The installed PRoot pack emitted no profiling start/counter records despite the
queued profiling setting; the profiler report had `hasCounters: false`. That is a
measurement gap, not evidence of zero remaining traps.

### Expanded relay coverage (before the safety follow-up)

The subsequent revision keeps patching enabled by default and shares the existing
wrapper translators with 20 path syscall numbers: openat, newfstatat, statx,
readlinkat, faccessat, mkdirat, unlinkat, renameat, renameat2, symlinkat, fchmodat,
utimensat, truncate, statfs, getxattr, lgetxattr, listxattr, llistxattr,
inotify_add_watch and getcwd. This is an allowlist of safely recognized sites, not
a claim that every glibc implementation contains every site. Unsupported paths,
flags, instructions and inaccessible pointers still use the original PRoot route.
Input/output copies use process_vm_readv/writev rather than dereferencing arbitrary
guest pointers. The device matrix checks error priority, protected buffers and a
NUL-terminated path ending at a page boundary.

The app forwards its Android SDK as `PROOT_FP_ANDROID_API`. With a known SDK,
set_robust_list retains the head in TLS and returns ENOSYS, matching patch 0016;
self get_robust_list uses that head. Uncaptured heads and other TIDs fall back to
PRoot. This does not implement kernel robust-mutex owner-death recovery. faccessat2
returns ENOSYS for glibc's fallback. clone3 returns ENOSYS only before API 35;
it is never executed from the C relay, since successful clone3 would change the
child's stack. The [Android 14 allowlist](https://android.googlesource.com/platform/bionic/+/refs/heads/android14-release/libc/SECCOMP_ALLOWLIST_COMMON.TXT)
omits clone3 and the [Android 15 allowlist](https://android.googlesource.com/platform/bionic/+/refs/heads/android15-release/libc/SECCOMP_ALLOWLIST_COMMON.TXT)
includes it. Unknown platforms leave these sites untouched. Existing droiddeck-fsync
handling of futex_waitv is unchanged.

Build-ID tables in `/tmp/.droiddeck-svc-v1-<api>-<build-id>` cache relative site
offsets. Every hit revalidates executable-segment bounds, alignment, the current
SVC opcode and the recognized syscall number. Missing, malformed, truncated,
overly permissive or symlinked tables trigger a rescan; writes use a private
temporary file and atomic rename. Images without a readable GNU build-ID still
scan normally. `PROOT_FP_SVC_CACHE=0` disables caching for comparison. Changes to
the accepted instruction/syscall set must bump the cache version.

On the Thor's glibc 2.43 / Android 13, 49 sites patch across libc and the loader.
Warm cached discovery took 60–63 microseconds in two samples versus roughly
0.6–0.7 milliseconds for the earlier uncached scan; patch publication took about
0.28 milliseconds. These are constructor phases, not whole Steam startup times.
Five alternating expanded-library pairs measured median internal stat at 39.5→4.9
microseconds and open/close at 41.2→6.4 microseconds, with 10,000→0 voluntary
context switches over both loops. The earlier SD/download tables above describe
the initial 14-site revision and must not be attributed to this expanded build.

Five alternating pairs of `svc-threads` additionally measured thread create/join
at 138→113 microseconds, libc faccessat at 51.6→14.7 microseconds, and libc getcwd
at 46.8→4.1 microseconds. Each run creates 128 threads and executes 2,000 calls
to each libc entry point, bypassing the exported preload wrappers with dlsym.
The access call includes glibc's faccessat2 fallback.

Repeated `/usr/bin/true` launches expose the startup tradeoff. Five batches of
40 children per mode measured medians of 3.834 ms with wrappers only, 4.882 ms
with uncached patching and 4.731 ms with cached patching, including subprocess
launch/wait and enabled exit statistics. Discovery across the 200 launches per
patched mode fell from a median 496 to 59 microseconds; publishing patches took
261/267 microseconds. The cache removes scanning work, but constructor setup and
patching still cost time for very short programs. No overall exec speedup is claimed.

Three alternating storage pairs with the expanded library measured:

| SD phase | wrappers only | expanded relays |
|---|---:|---:|
| create/extend/close 64 files | 1.556 s | 1.514 s |
| 1,000 internal stats | 73.2 ms | 12.5 ms |
| 64 MiB write calls | 54.2 ms | 55.1 ms |
| final fdatasync | 2.799 s | 2.730 s |

These phase medians do not establish a general write-speed improvement. Internal
allocation was 7.1→3.6 ms and internal stats 49.8→7.7 ms; the SD filesystem's
allocation and flush waits remain much larger than the interception savings.

### Expanded-build Steam SD comparison

Owned, initially uninstalled Half-Life (70) supplies a larger file tree: 4,525
installed files / 589,603,955 bytes, BuildID 15961492. Each fresh trial initially
scheduled 294,238,640 download bytes, 589,603,955 staged bytes and 4,488 preallocated
files on `/mnt/droiddeck-sd`. Steam reported successful installation in every run.
Local client `0` telemetry was selected. Times use one-second content-log timestamps;
total runs from the first main-app Reconfiguring event to scheduler completion.

| trial | patching | initial preallocate | transfer/stage window | final commit | total |
|---|---|---:|---:|---:|---:|
| first baseline | off | 90 s | 52 s | 204 s | 350 s |
| expanded, default setting | on | 94 s | 48 s | 184 s | 326 s |
| repeat baseline | off | 97 s | 69 s | 175 s | 341 s |

The patched and repeat-baseline trials use the same final library SHA-256
`af4f66680dcfbd5002f641ffe206cdc7bd89400be169d3ef21c4e0ee7f979589`.
The first baseline used the preceding build with patching disabled; the only
subsequent production change corrected explicitly cleared robust-list TLS state.
All initial download records showed zero stored/reused/delta bytes. Shader updates
interrupted the main download in each trial; the transfer window includes those
pauses. On the repeat baseline, reconfiguration retained two small files already
downloaded during that same trial, so the final resumed job scheduled 294,236,672
download / 589,599,865 stage bytes and committed 4,572 entries rather than 4,574.
This is not pre-existing payload reuse; the earlier fresh schedule is retained
in the measurement records.

The observed whole-install difference does not establish a reliable SD speedup:
the repeat baseline committed faster than the patched run, while its transfer
window was slower. Allocation also did not improve. The 326 versus 341–350 second
totals combine network, shader scheduling and SD variability. The syscall gains
are demonstrated independently; these samples do not show that they fix Steam's
SD allocation/commit bottleneck. The test title was removed between trials; an
interrupted first uninstall left a repair queue, which was cleared by removing
only that task-created title while Steam was stopped before the subsequent fresh
trial. Existing installed games were retained.

The expanded ABI matrix passes raw return values, errno, all x1–x30 and q0–q31,
NZCV and non-default FPCR/FPSR, four concurrent threads with distinct robust heads, fork, 100
signals on an alternate stack, deferred cancellation, cancellation/cleanup of a
blocked libc FIFO open, clearing/restoring a robust head, and 32 thread create/join
cycles. Constructor patching still refuses a process with multiple live threads.
Host tests exercise corrupted cache entries, invalid offsets/opcodes, mismatched
API/version, unsafe file modes, symlinks and truncation. The Android unit test
checks API forwarding. The existing preload/agent selection passed 79 tests with
two skips before the cache regression test was added.
API-selection probes patch 45 sites with an unknown SDK, 49 with API 33 and 48
with simulated API 35; unset and explicit 1 agree, and explicit 0 leaves the
original sites intact. This does not substitute for testing on Android 15 hardware.

CI at 40b7c480 passed 1,427 Python/native tests (49 skips), 2,630 Android tests and
162 Rust host tests, plus APK compilation. A local release APK built successfully,
passed runtime-payload/native-library checks and was signed with the installed
release certificate. The full local Windows JVM run reported 150 failures / 2,568
tests (eight skips), including Unix-path, symlink and permission assumptions;
the four ProotFastPath tests passed in both local variants. The passing full
suite evidence is the Linux CI run, not a clean Windows full-suite result.

Internal fts/realpath/NSS/dlopen and the normalized first equivalence suite still
match PRoot at both cache TTLs. The write suite still matches wrapper-only output
and reproduces the same existing SD Unix-socket mismatch described above.
No in-process RET_TRAP filter is installed, so the proposed SIGSYS mask/handler
machinery is not applicable. Startup loader traps before the constructor and
raw calls in arbitrary guest ELFs remain outside the patch scope. Additional
glibc builds, Android versions, unwind behavior, Steam/CEF tree RSS and matching
patch-0018 tracer measurements remain coverage gaps.

Build the tools with the same ARM64 glibc toolchain as the fast path:

```sh
aarch64-linux-gnu-gcc -O2 -Wall -Wextra -pthread \
  -o svc-check tools/proot/bench/svc-check.c -ldl
aarch64-linux-gnu-gcc -O2 -Wall -Wextra \
  -o svc-probe tools/proot/bench/svc-probe.c -ldl
aarch64-linux-gnu-gcc -O2 -Wall -Wextra \
  -o svc-workload tools/proot/bench/svc-workload.c -ldl
aarch64-linux-gnu-gcc -O2 -Wall -Wextra \
  -o svc-storage tools/proot/bench/svc-storage.c -ldl
aarch64-linux-gnu-gcc -O2 -Wall -Wextra -pthread \
  -o svc-threads tools/proot/bench/svc-threads.c -ldl
```

Run `svc-probe` inside the app's guest environment, not from `adb shell`.
`svc-check` embeds the fast path and requires its normal enabled tracer environment.
For that embedded-library check, start with PROOT_FP_PATCH_SVC=0 when the session
also preloads a fast-path library (including through /etc/ld.so.preload). The check
then enables its own copy at main before creating threads, keeping another copy
from owning the glibc patch sites. The other tools use the normal preload.
For `svc-workload`, pass a prepared directory containing `file` and a `link` symlink
to it; add any second argument to enable timing. Use the documented library override
workflow in [proot-performance](proot-performance.md) for a fresh session. No extra
environment setting is needed; use `PROOT_FP_PATCH_SVC=0` for the unpatched comparison.
`svc-storage` takes a new, unused directory name; it creates and removes its own test
files and directory. It writes 64 MiB plus 16 MiB of file extensions per run.

### Safety follow-up (2026-10-28)

Path patching remains enabled by default. Robust-list registration/query sites are
now deliberately excluded: a device regression test reproduced disagreement
between the relay's private TLS head and an unpatched raw get_robust_list call in
the preceding implementation. Both directions now use the existing PRoot/kernel
route, including cleared heads. This preserves the host's actual return policy:
the installed Thor runtime accepts set_robust_list with zero, whereas the removed
emulation returned ENOSYS. PRoot patch 0016 can instead return ENOSYS when Android
blocks registration; neither policy is replaced by private relay state.

The build-ID cache is version 2 because the accepted syscall set changed. The
current glibc 2.43/API-33 build patches 47 sites rather than 49. faccessat2 and
pre-Android-15 clone3 fallback acceleration remain; droiddeck-fsync is unchanged.
The earlier 138-to-113-microsecond thread result belongs to the retired robust-list
implementation and must not be claimed for the current code.

Relays now save the original post-SVC program counter and supply unwind metadata
for the saved caller registers. Device tests force an unwind from inside the
translator through a patched libc frame and verify caller cleanup. A signal handler
also exits with siglongjmp; a later call recovers the abandoned recursion guard
and resumes acceleration. These tests supplement deferred and blocked-FIFO
cancellation; they are not a claim of arbitrary asynchronous-cancellation safety.

Compiler stack-usage output identified multiple nested PATH_MAX-sized frames.
Before entering them, a small dispatcher checks the active alternate signal stack
and cached writable stack mapping. Alternate-stack calls and calls with less than
96 KiB remaining use PRoot. Main-stack growth respects the current RLIMIT_STACK
and leaves space above the preceding mapping. The implementation adds only a few
TLS words, not a large private translation stack. Tests cover a restricted thread
stack, normal/alternate-stack signals, main-stack growth and a reduced/unavailable
stack limit. User-managed stacks and arbitrary mid-helper non-local exits remain
broader compatibility risks, not proven consequence-free cases.

On Thor/Android 13, the embedded and shared-PIC matrices pass on glibc 2.43;
the shared-PIC matrix also passes with a separately loaded glibc 2.39. They cover register/FP/errno preservation, mixed raw
and relay robust calls, pointer and page boundaries, readlink buffers of target
length minus one through plus two, thread churn, fork, signal/non-local exits,
forced unwinding and limited stack space. Readlink preserves PRoot's existing
translated-link length behavior; bytes beyond the reported payload are unspecified.

Test-process seccomp filters separately deny opening /proc/self/mem, nearby relay
mappings, executable mprotect and patch writes. All four device cases leave zero
patched sites and a working original libc syscall. Host injection additionally
checks that one failed site write permits other sites to install. Concurrent
atomic symlink replacement passes 3,000 internal stat/readlink iterations at both
zero and default cache TTLs. These are bounded tests; they do not prove every
possible filesystem race or reproduce a real SELinux execmod denial.

Five new alternating pairs measured:

| operation | wrappers only | hardened relays |
|---|---:|---:|
| libc-internal stat | 50.1 us | 5.9 us |
| libc-internal open/close | 51.9 us | 7.1 us |
| libc faccessat | 52.0 us | 20.2 us |
| libc getcwd | 51.7 us | 4.6 us |
| thread create/join | 129 us | 144 us |
| complete tiny exec/wait | 5.04 ms | 5.96 ms |

The metadata gains survive the safety checks. This run shows no thread-creation
gain, and the roughly 0.9 ms tiny-exec cost remains. The earlier SD download and
flush results are historical measurements of the preceding build; no new claim
that SD media writes or whole Steam installs become reliably faster follows from
these syscall results. Android 15+ ARM64 and 16 KiB-page hardware remain untested.

Three alternating pairs also measured session start through the Steam Big Picture
Home page becoming ready, using its DOM/Recent Games content rather than merely
the compositor's first frame. Off runs took 48.76/43.47/47.02 seconds; on runs took
43.43/45.25/50.40 seconds. The 47.02-to-45.25-second medians sit within overlapping
run-to-run variation and do not establish a reliable whole-client startup win.
These use the signed bundled-library build without overrides; the final stack-gap
guard only increases the reserved distance above other mappings.

At Home-ready plus 20 seconds, three one-second-spaced process-memory samples per
run gave Steam/CEF-only median PSS of 1,223.1 MiB off and 1,233.9 MiB on. Including
their active Python/Wine descendants gave 1,243.7/1,254.6 MiB. The five Steam/CEF
processes were selected by executable name; descendants were included by parent
PID. Per-run Steam/CEF medians ranged 1,217.9–1,240.9 MiB off and
1,194.8–1,244.9 MiB on. The roughly 11 MiB median increase is smaller than this
variation, but it is not evidence of zero memory cost. PSS avoids counting shared
pages in full for each process. Startup and memory comparisons used one unchanged
APK; no downloads or games were initiated during them.

CodeRabbit follow-up: the workload now compares canonical paths, so relative and
absolute fixture arguments agree. All SVC bench tools keep their checks active
under NDEBUG; their clock, syscall and thread operations cannot silently disappear.
Both cases were rebuilt with -DNDEBUG and checked on Thor. The memory-file-denial
test now blocks only O_RDWR opens and asserts the single-thread check still works,
so it reaches the intended /proc/self/mem failure. The initial proroot comparison
also distinguishes its limited in-process fallback from a general tracer.
The request to return to default-off was addressed through the interruption/unwind
fixes and tests while retaining the requested default. The proposed readlink count
change was not applied because exact boundary tests reproduce the current PRoot
behavior; changing that inherited policy belongs in a separate correctness change.
