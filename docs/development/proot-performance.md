# proot, glibc and where the time goes

Research notes for `feat/performance-fixes-2` (2026-09-30). The question was whether Steam and the
games should keep running under proot, run directly on glibc, or a mix of the two, and what the
fastest correct setup is. Everything below was measured on a Snapdragon 8 Elite Gen 5 (SM8850, kernel
6.12.38-android16, Android 16) over adb, unless it says otherwise.

For Steam SD allocation measurements on AYN Thor (Android 13), see the
[Thor storage investigation](thor-sd-storage-investigation.md). It separates
observed allocation-wrapper time, FUSE directory access and native exFAT metadata
waits; it does not establish a full-install PRoot overhead percentage.

## Short answer

- **Don't drop proot.** On Android it does two jobs. It translates paths. It also answers every
  syscall that Android's app seccomp policy blocks (`rseq`, `set_robust_list`, `faccessat2`,
  `fchmodat2`, `openat2`, the `set*id` family, `futex_waitv`, ...).
  - Stock Arch glibc without a tracer is killed by `SIGSYS` on its first `pthread_create`. That
    stays true even with an in-process `SIGSYS` handler, because glibc makes those calls with every
    signal blocked.
  - Going "pure glibc" means building Termux-style glibc (relocated prefix, blocked syscalls
    rewritten), an exec shim for every ELF Steam self-updates, a `/proc/self/exe` emulator for
    Chromium, path redirects for `/tmp`, `/dev/shm`, `/mnt/*` and the fake `/proc` and `/sys`
    files, and Wine's hard-coded `/tmp/.wine-<uid>`.
  - That is a project of its own, and its failure mode is a crash instead of a slow call.
- **During gameplay, proot already costs almost nothing**, thanks to patches 0004–0013. GPU submits
  (`ioctl`), `futex`, `read`/`write`, `mmap`, `ppoll`/`epoll`, `sendmsg`/`recvmsg` and
  `clock_gettime` (via the vDSO) all run at native speed under the filter.
- **What proot still costs** is every *path* syscall, `execve`, `brk`, thread and process creation,
  and every Android-blocked syscall: **15–60 µs each**, against ~0.5 µs natively. All of it goes
  through **one tracer thread**, which tops out around 75–95k traps/s. Steam start-up, game loading
  and asset streaming, Proton's Python, and any burst on several threads at once all hit this.
- **Most of that cost is not proot's code but the two cross-core wake-ups per trap.** Any
  out-of-process mechanism pays them: ptrace, and seccomp user-notification too (measured the same).
  The only real fix is to not leave the process.
- **Recommendation: proot plus an in-process fast path.** A preloaded library answers the common
  path calls inside the process. It sends them through a trampoline page that proot's filter lets
  through, and it falls back to proot for anything it can't prove proot would answer the same way.
  - The prototype is in this branch: `tools/proot/fastpath/` and `patches/0014`.
  - Speed: `stat` 25 µs → 0.6 µs, `open` 25 µs → 0.9 µs, missing-file lookups 23 µs → 1.6 µs.
    Eight threads doing `stat` went from 89k/s to 3.6M/s.
  - Correctness: an equivalence suite of awkward paths produced byte-identical results.
  - It only uses syscalls the app policy allows.

## How a session runs today

```
SessionService ─HostProcess─▶ libproot.so (ONE tracer process, nice -6 after 2 s)
  └─ /usr/bin/env -i … droiddeck-session steam
       └─ gamescope ─▶ droiddeck-session (BL_INSIDE) ─▶ steamrtarm64/steam (+ steamwebhelper/CEF tree)
            └─ reaper ─▶ droiddeck-game-env ─▶ droiddeck-proton ─▶ Valve ARM64 Proton (python)
                 └─ wine (arm64ec, FEX loaded in-process as a DLL) ─▶ wineserver, game.exe, …
```

- Every process in the tree, from the session script to the game, is a tracee of the same proot.
- The options are `--kill-on-exit`, `--kernel-release=…` (kompat, for the hostname), `-i uid:uid`
  (fake_id0, for Xwayland's `setgid`/`setuid`), and about 25 binds: `/dev`, `/proc`, `/sys`, fake
  `/proc` and `/sys` files, the GPU node, `/dev/shm`, storage and the game libraries.
- `/etc/ld.so.preload` loads `libblsession.so` (the `tools/linuxfs/preload/*.c` shims) into every
  guest process.
- The Flatpak sandboxes (`BwrapSpawner`) already run in **separate** proot instances, started from
  the Android side.

### What proot does per traced syscall

The tracee runs into proot's seccomp filter, which returns `SECCOMP_RET_TRACE`. Then:

1. The kernel stops the tracee and wakes the tracer: a cross-core IPI, and possibly an exit from a
   deep idle state.
2. The tracer reads the registers (`PTRACE_GETREGSET`) and reads the path (`process_vm_readv`).
3. It canonicalises the path against the rootfs and bindings: an `lstat` per component, or one
   `O_PATH` open plus a `/proc/self/fd` readlink with patch 0002.
4. It writes the host path into a scratch area in the tracee (`process_vm_writev`) and updates the
   registers.
5. It resumes the tracee, which is another cross-core wake-up.

Calls flagged `FILTER_SYSEXIT` stop a second time on the way out, for example to translate the
result of `readlink` or `getcwd`. Android-blocked syscalls arrive as a `SIGSYS` signal stop instead,
and proot emulates them (`src/tracee/seccomp.c`). Fork, clone, exec and thread exit each add
`PTRACE_EVENT_*` stops.

## Measurements

The tools are in `tools/proot/bench/`; `run-device.sh` explains the device layout.

**Caveat:** `adb shell` is *not* under the app seccomp filter. Mechanism costs are the same, but
syscalls that Android blocks only for apps (see below) can't be reproduced there.

**Caveat:** the phone in Doze (screen off) runs everything about 7× slower. All numbers below were
taken with the screen awake.

### Per call, as a session runs proot today

`sysbench` in the patched (`patched`) and unpatched (`vanilla`) columns, using the session's own
proot options and a similar set of binds.

| call | direct glibc | termux proot (vanilla) | this repo's proot (0001–0013) | + fast path (0014) |
|---|---|---|---|---|
| getppid / futex / ioctl / pread (untraced) | 0.1–0.4 µs | futex **16.6**, fstat **32.8**, ioctl **30.5** µs | 0.2–0.4 µs | 0.2–0.4 µs |
| stat, deep absolute path | 0.8 µs | 41.7 | 25.3 | **0.6** |
| open + close | 1.7 µs | 40.4 | 25.3 | **0.9** |
| access | | 27.2 | 26.2 | **1.0** |
| fstatat relative to a dirfd | | 45.5 | 26.8 | **1.9** |
| stat of a missing file | | 38.9 | 22.9 | **1.6** |
| readlink /proc/self/exe | | 42.8 | 38–43 | (proot) |
| getcwd / uname / brk | | 31–35 | 30–33 | (proot) |
| memfd_create | | 20.4 | 18.6 | (proot) |
| pthread create+join | | 58 | 48 | 48 |
| fork + exec + wait | ~1.1 ms | 1.16 ms | 0.70–0.78 ms | +0.2–0.4 ms (see open items) |

The patches already in the repo pay off: kompat (0011) and fake_id0 (0012) took `futex`, `fstat` and
`sendmsg` off the trap list, and 0013 did the same for `ioctl`.

### What one trap costs, by mechanism (`mechbench`; the handler does nothing)

| mechanism | cost |
|---|---|
| native syscall | 0.1 µs |
| seccomp filter of 0 / 50 / 150 / 300 compares, allowed syscall | **no difference** (kernel ≥5.11 caches constant decisions per syscall number) |
| ptrace + seccomp, tracer and tracee on the **same core** | 6.2 µs (6.7 µs with register get/set) |
| ptrace + seccomp, **unpinned** (as the app runs it) | 15.5 µs |
| ptrace + seccomp, different cores (same cluster / across clusters) | 34–36 / 42–61 µs |
| ptrace with a spinning tracer, different cores | 20 µs (the tracee's side still has to wake) |
| seccomp user-notification (the "modern proot" design) | 5.7 µs same core, 34–58 µs across, 43 µs unpinned; `SYNC_WAKE_UP` doesn't help |
| **in-process SIGSYS** (`SECCOMP_RET_TRAP`) | **0.83 µs** |
| openat2 `RESOLVE_IN_ROOT` (kernel-side chroot lookup) | 1.0 µs, but **blocked in apps** |

Takeaways:
- The tracer's own work is about 8 µs of the ~14 µs same-core trap. Everything above that is
  scheduling.
- Moving to seccomp user-notification would be a rewrite for no gain.
- The filter's length doesn't matter. What matters is not breaking the kernel's cache:
  - **Any check on an argument or the instruction pointer must come after the syscall-number
    dispatch.** The first version of 0014 checked the address first. That made every syscall in
    the session run the whole filter, and cost +0.5 ms per exec.
  - Patches 0004 and 0013 already follow this rule.

### One tracer for everything (`parstat`: N threads doing `stat`)

| | 1 thread | 4 threads | 8 threads |
|---|---|---|---|
| one proot | 37k/s, 27 µs | 74–95k/s, 42–54 µs | 68–89k/s, **90–118 µs** each |
| two proots, 2 threads each | | 187k/s total, 21 µs | |
| proot + fast path | 1.64M/s, 0.6 µs | 2.76M/s | **3.60M/s, 2.2 µs** |

A burst of path calls on several threads therefore queues behind the single tracer. CEF loading
assets, Proton's Python, a game streaming files on worker threads and the shader-cache threads all
produce such bursts.

### CPU placement

The workload ran with `taskset`; no fast path. Times are per traced call, and the last column is one
fork + exec.

| affinity | stat | exec |
|---|---|---|
| default | 25.8 µs | 750 µs |
| one core | 14.0 µs | 370 µs |
| the two prime cores | 14.6 µs | |
| the six-core cluster | 25.9 µs | |

The scheduler's wake-affine placement helps when it can put the tracer and the tracee together, and
spreading them apart hurts. **Never pin the tracer away from its tracees.** Today `raiseTracer`
only renices it, which is correct.

### Whole programs

Times are in ms, each with proot's ~40 ms start-up already subtracted.

| | direct glibc | vanilla proot | this repo's proot | + fast path |
|---|---|---|---|---|
| python3: 10 stdlib imports | 75–89 | ~130 | ~110 | ~90 (878 of 879 path calls answered in-process) |
| tar of 2.7k files | 10–40 | ~180 | ~80 | ~20 |
| 200 × fork+exec of `true` | 230 | ~600 | ~500 | ~500 |
| find over 2.7k files | 16–23 | noisy | noisy | unchanged: glibc's fts calls internal entry points |

## Android's app seccomp policy decides more than proot does

The app filter is generated from bionic's `SYSCALLS.TXT`, minus the blocklists, plus the
allowlists. It is the same for every app whatever its `targetSdk`, and anything not listed gets
`SECCOMP_RET_TRAP`, a `SIGSYS`. Filters stack, and the strictest one wins: a blocked call never
reaches proot's `RET_TRACE`. It arrives as a `SIGSYS` stop, and proot answers it (usually `ENOSYS`).

Checked against bionic `android16-release`:

| syscall | app policy | who calls it |
|---|---|---|
| `rseq`, `set_robust_list` | **blocked** | glibc, at the start of every thread, while all signals are blocked |
| `faccessat2` | **blocked** | glibc's `faccessat()`, which tries it on **every** call before falling back, so 2 round trips (`access()` calls the plain `faccessat` syscall directly) |
| `fchmodat2`, `openat2` | **blocked** | newer glibc, Flatpak/libglnx, FEX's rootfs lookups (`libblsession.so` turns `openat2` into `openat`) |
| `setuid`, `setgid` and the rest of the family | **blocked** | Xwayland (hence `-i`) |
| `futex_waitv`, `io_uring_*`, `landlock_*` | **blocked** | Proton's fsync (droiddeck-fsync answers `futex_waitv` in `libblsession.so`), some engines |
| `clone3` | blocked before Android 15 | glibc `pthread_create` |
| `close_range` | blocked on Android 12 | |
| `statx`, `process_vm_readv`/`writev`, `memfd_create`, `pidfd_*`, `seccomp` | allowed | |

On top of the ptrace round trips, every one of these costs a signal round trip through proot.

## Every traced syscall: what it needs and whether it can bypass proot

This is the filter of termux proot at `source.env` plus patches 0001–0013. "Fast path" means the
call can be answered in-process with the 0014 design.

| syscalls | why proot traps them | can it bypass proot? |
|---|---|---|
| `openat`/`open`/`creat`, `newfstatat`/`stat`/`lstat`, `statx`, `faccessat`/`access`, `readlinkat` (exit), `mkdirat`, `unlinkat`, `renameat(2)`, `symlinkat`, `linkat`, `fchmodat`, `fchownat`, `utimensat`, `truncate`, `*xattr`, `inotify_add_watch`, `name_to_handle_at` | path translation (guest → host), plus exit fixups for `readlink` and `rename` | **Yes, through libc wrappers.** The prototype covers open, stat, statx, access, readlink, opendir and fopen; the write family is next. Raw `svc` and glibc's internal callers (ld.so, fts, realpath, nss) still go to proot. |
| `chdir`/`fchdir`, `getcwd` (exit) | proot emulates the cwd; until 0014 the kernel's cwd never moved | With 0014 the kernel's cwd follows the guest's. `getcwd` stays in proot (cheap, rare). |
| `execve`/`execveat` | runs the loader, maps `PT_INTERP` inside the rootfs, shebangs, tracks `/proc/self/exe` | **No.** This is the core of what proot is for. Costs 0.4–0.8 ms per exec; the session scripts already avoid exec-heavy loops (`nap`). |
| `brk` (entry and exit) | heap emulation: programs are mapped by proot's loader, so the kernel's brk area is the loader's | No (`PR_SET_MM` needs `CAP_SYS_RESOURCE`). **Fewer calls:** `GLIBC_TUNABLES=glibc.malloc.top_pad=…` grows the heap in larger steps. |
| `bind`, `connect`, `accept(4)`, `getsockname`, `getpeername` | `sun_path` of Unix sockets | Same-path binds (runtime dir, files dir) need no translation, but the filter can't see the address. Possible fast-path candidate. |
| `wait4`/`waitpid`, `ptrace` | ptrace emulation inside the guest (breakpad, gdb) | Could become opt-in; then `wait4` is free (one stop per wait today). |
| `prctl(PR_SET_DUMPABLE)`, `setrlimit`/`prlimit64(RLIMIT_STACK)` | loader and stack fixups | Already narrowed to these arguments (0004). |
| `ioctl(TCSETSF, termios2, FICLONE)` | Android pty policy, FICLONE `EACCES` | Already narrowed to these requests; GPU ioctls are free (0013). |
| `uname`, `sethostname`, `setdomainname` | the `DroidDeck` hostname (kompat) | Rare. Keep. |
| `set*id`, `*setxattr` | fake_id0 identity mode (0012) | Rare. Keep. |
| `memfd_create` | Qt JIT and php workarounds (string argument) | Every Wayland `wl_shm` buffer and Chromium shared memory pays one stop. Could be dropped if the DroidDeck runtime doesn't need those workarounds. |
| `statfs` (exit) | fakes tmpfs for `/dev/shm` | Rare. |
| `open_tree`, `move_mount`, `fspick`, `mount_setattr`, `openat2` | answered `ENOSYS` (0008, 0010) | Fine. |
| Android-blocked calls (previous table) | `SIGSYS` emulation | **Avoidable at the source:** `GLIBC_TUNABLES=glibc.pthread.rseq=0` removes one stop per thread. The rest need a patched glibc. |
| clone/fork/vfork/exec/exit events | tracking tracees | Needed while proot traces. |

## Options weighed

1. **Pure glibc, no proot.** Rejected as the main path, for the reasons in the short answer.
   - Closest prior art: huntergdavis/steamclienttermux, which runs `steamrtarm64` on a patched
     Termux glibc 2.44 with an exec shim and a `/tmp` and `/dev/shm` redirect preload.
   - They report Steam's first window 7× faster and about 5% more FPS, with proot having used 60–65%
     of a core.
   - **They still run Proton and the games under proot.**
   - DroidDeck also has four package names (`tools/release/variants.txt`), so a relocated prefix
     would need four builds or Winlator-style padded path rewriting.
2. **Hybrid by process (Steam in proot, game on glibc, or the reverse).** The game side is the
   *harder* half. Wine re-execs itself and `wineserver`, which hits `PT_INTERP`. Its server
   directory is hard-coded under `/tmp`, and Proton's esync and fsync use `shm_open` (`/dev/shm`)
   and `futex_waitv`, which is blocked (droiddeck-fsync emulates it in the session preload, which a
   glibc-side game would have to load too). A game's steady state is already mostly untraced, so the
   gain is in loading. Not worth it before the fast path.
3. **Seccomp user-notification instead of ptrace.** It measures the same as ptrace, and it can't
   rewrite arguments, so every call would have to be emulated. No.
4. **In-process `SIGSYS` for everything** (0.8 µs). The kernel resets a blocked `SIGSYS` to the
   default action and kills the process (`force_sig_seccomp`, `HANDLER_CURRENT`). glibc and Wine
   block all signals around exactly these calls. Unsafe without also trapping `rt_sigprocmask`. No.
5. **proot plus an in-process fast path (this branch).** Recommended. Details below.
6. **A proot-aware glibc**, the follow-up to 5. Rebuild the rootfs's own glibc (same version, same
   Arch package) with two changes:
   - its internal path entry points (`__open_nocancel`, `__fstatat64`, ld.so's opens) go through the
     fast path, which covers ld.so, fts, realpath and nss;
   - it never makes the Android-blocked calls, as Termux's `fakesyscall.json` does.

   This removes the remaining library-loading traps and all the `SIGSYS` round trips. The rootfs is
   versioned (`linuxfs-rN`), so glibc rebuilds stay under control.
7. **On the tracer side, with no in-process changes:**
   - **A second proot for the game tree.** Measured 2.5× trap throughput, and Steam/CEF bursts no
     longer stall the game. `BwrapSpawner` already shows the pattern: a stand-in process in the
     session relays stdio, the exit status and signals to a proot started from the app.
   - Leave the tracer unpinned (above).
   - Set `GLIBC_TUNABLES=glibc.pthread.rseq=0:glibc.malloc.top_pad=16777216` in the session
     environment.

## The fast path (prototype)

The code is `tools/proot/fastpath/fastpath.c`, preloaded into every guest process. On the proot side
it needs `patches/0014`, enabled with `PROOT_FASTPATH=1`.

- **Trampoline.** The library maps a 4 KiB executable page at the fixed address `0xffff00000`. It
  holds `mov x8,x0 … svc #0; ret`. With `PROOT_FASTPATH`, proot's filter checks the caller's address
  just before each `RET_TRACE` and allows syscalls coming from that page.
- **Mapping a path.** The library takes the literal guest path and finds the longest binding whose
  guest path is a prefix of it; otherwise the path is under the rootfs.
  - It opens the *parent* host directory `O_PATH` and requires `/proc/self/fd` to name exactly that
    path. That proves no component on the way was a symlink, so proot would have walked the same
    directories.
  - The last component is never followed: a symlink there goes to proot.
- **Missing files.** For a missing file, the deepest existing ancestor is verified the same way, and
  the next component must not exist even as a symlink. Then `ENOENT` is exact.
- **What always goes to proot:**
  - `..`, a trailing `/`, `/proc`, `/dev/fd` and `/dev/std*`;
  - `O_TMPFILE`, `AT_EACCESS`, and any flag not handled;
  - any doubt at all. "Go to proot" means calling the real libc function, so proot decides.
- **Relative paths.** Patch 0014 keeps the kernel's cwd at the guest cwd's host directory. Relative
  and dirfd lookups are mapped through `/proc/self/{cwd,fd/N}`.
- **Verified-parent cache.** Verified parents are cached for 2 s (`PROOT_FP_TTL_MS`). That is the
  same trade-off `preload/pathcache.c` already makes for the Steam client. A parent directory turned
  into a symlink inside that window would be missed; `PROOT_FP_TTL_MS=0` verifies every call, at
  2.0 µs instead of 0.6 µs.
- **Allowed syscalls only.** It uses only `openat`, `newfstatat`, `statx`, `faccessat`, `readlinkat`
  and `close`, all allowed for apps. A first version used `openat2 RESOLVE_IN_ROOT`, which the app
  policy blocks.
- **Equivalence check.** `bench/equiv.py` runs stat, lstat, readlink, read, listdir and access over
  awkward paths, from several working directories and through a dirfd. Its output is byte-identical
  with and without the fast path, at TTL 0 and at TTL 2000. The paths include:
  - absolute and relative symlinks into binds, `..` symlinks, dangling links and loops;
  - files inside binds, nested binds (`/dev/shm` inside `/dev`) and `/proc`;
  - missing paths behind symlinks.

### Open items before shipping it

- **Validate inside the app**, not just from adb. Three things to check:
  - The app domain allows an anonymous `PROT_EXEC` page (`execmem`). If not, map a page of the
    library file itself at the fixed address instead, as proot's loader does for every program.
  - Nothing claims `0xffff00000` first.
  - The stub's syscalls pass Android's filter. They should: all are allowed.
- **Integrate into `libblsession.so`** rather than a second preload. `opens.c` and `pathcache.c`
  already define `open` and `access`, so their "real" call should become the fast path. That also
  removes the second library's load cost, about 0.2–0.4 ms per exec that is still unexplained (see
  the exec row above).
- **Wire the launcher.** The app already knows the binds (`LinuxRuntime.binds`):
  - set `PROOT_FASTPATH=1` in proot's environment;
  - pass `PROOT_FP_ROOT` and `PROOT_FP_BINDS` to the guest;
  - add a Performance toggle next to "Run proot without seccomp", with the fast path off whenever
    seccomp is off.
- **Cover the write family** (mkdir, unlink, rename, symlink, chmod, utimens) and Unix-socket
  `connect`/`bind` for same-path binds. Then measure a real Steam start in the app, a game load,
  and `PROOT_FP_STATS` hit rates for steam, steamwebhelper, wine and wineserver.
- **FEX**, inside Proton as a DLL, translates x86 path syscalls through glibc's `syscall()`, not the
  wrappers, so they still trap. A `syscall()` hook could route `openat` and `newfstatat` there too.
