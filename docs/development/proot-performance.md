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
  - It ships: `tools/proot/fastpath/` and `patches/0014` (see [The fast path](#the-fast-path)).
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
| readlink /proc/self/exe | | 42.8 | 38–43 | in-process (`/proc/self/fd/N`: **4.4**) |
| getcwd / uname / brk | | 31–35 | 30–33 | **1.0** / **0.006** / (proot) |
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
| `openat`/`open`/`creat`, `newfstatat`/`stat`/`lstat`, `statx`, `faccessat`/`access`, `readlinkat` (exit), `mkdirat`, `unlinkat`, `renameat(2)`, `symlinkat`, `linkat`, `fchmodat`, `fchownat`, `utimensat`, `truncate`, `*xattr`, `inotify_add_watch`, `name_to_handle_at` | path translation (guest → host), plus exit fixups for `readlink` and `rename` | **Yes, through libc wrappers**, all of these but `linkat`, `fchownat`, setting or removing xattrs, `name_to_handle_at` and renaming a directory. Raw `svc` and glibc's internal callers (ld.so, fts, nss) still go to proot; `libblaudit.so` keeps ld.so's failing lookups away from it. |
| `chdir`/`fchdir`, `getcwd` (exit) | proot emulates the cwd; until 0014 the kernel's cwd never moved | With 0014 the kernel's cwd follows the guest's. `getcwd` stays in proot (cheap, rare). |
| `execve`/`execveat` | runs the loader, maps `PT_INTERP` inside the rootfs, shebangs, tracks `/proc/self/exe` | **No.** This is the core of what proot is for. Costs 0.4–0.8 ms per exec; the session scripts already avoid exec-heavy loops (`nap`). |
| `brk` (entry and exit) | heap emulation: programs are mapped by proot's loader, so the kernel's brk area is the loader's | No (`PR_SET_MM` needs `CAP_SYS_RESOURCE`). **Fewer calls:** `GLIBC_TUNABLES=glibc.malloc.top_pad=…` grows the heap in larger steps. |
| `bind`, `connect`, `accept(4)`, `getsockname`, `getpeername` | `sun_path` of Unix sockets | Through the fast path's libc wrappers, unless the host path does not fit `sun_path` or a blocking `accept` has nothing waiting. |
| `wait4`/`waitpid`, `ptrace` | ptrace emulation inside the guest (breakpad, gdb) | Could become opt-in; then `wait4` is free (one stop per wait today). |
| `prctl(PR_SET_DUMPABLE)`, `setrlimit`/`prlimit64(RLIMIT_STACK)` | loader and stack fixups | Already narrowed to these arguments (0004). |
| `ioctl(TCSETSF, termios2, FICLONE)` | Android pty policy, FICLONE `EACCES` | Already narrowed to these requests; GPU ioctls are free (0013). |
| `uname`, `sethostname`, `setdomainname` | the `DroidDeck` hostname (kompat) | `uname`/`gethostname` are answered in-process from the first answer (libX11 asks for each connection). |
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

## The fast path

The code is `tools/proot/fastpath/fastpath.c` (`libblfastpath.so`, in `/etc/ld.so.preload`) and
`tools/proot/fastpath/audit.c` (`libblaudit.so`, an `LD_AUDIT` module). On the proot side it needs
`patches/0014`. `ProotFastPath.kt` hands both sides the rootfs, the binds and a key derived from
them; the library answers nothing unless the process tracing it carries the same key.

- **Trampoline.** The library maps a 4 KiB page at `0xffff00000` holding `mov x8,x0 … svc #0; ret`.
  proot's filter lets syscalls from that page through. Only calls the app policy allows go through it.
- **Resolution, as proot's `canonicalize()` does it.** A guest path is walked component by
  component: `lstat` of each host path, symlinks read and followed in guest terms, the longest guest
  binding wins (the last of two for the same path). Intermediate directories are cached by their
  literal path for 2 s (`PROOT_FP_TTL_MS`); anything that renames, removes a directory or makes a
  symlink drops the cache. A relative path starts from `/proc/self/cwd` or `/proc/self/fd/N`.
  The call is then made on the host path, with `O_NOFOLLOW` or `AT_SYMLINK_NOFOLLOW` where the walk
  already followed the last link.
- **proot's quirks are reproduced, not avoided:**
  - a binding whose host path did not resolve when proot started is dropped, as proot drops it;
  - a binding onto `/proc/self/...` names proot's own descriptor (`/dev/null` in a session);
  - a directory above a binding that is missing on the host is proot's glue;
  - a missing intermediate component is `ENOENT` whatever the kernel would say, but a last
    component proot cannot stat is left to the kernel (sysfs and `/proc` nodes the app may not stat);
  - link text is detranslated as `detranslate_path()` does it: from the rootfs only the rootfs
    prefix goes, from a binding text into that same binding takes its guest path, `/proc` links get
    proot's own answers (`exe`, `cwd`), and `readlink` writes its result exactly as proot does,
    including a buffer the kernel cut short;
  - `/proc/<pid>/fd/N` named last is followed by the kernel, not proot;
  - a `dirfd` that is a pipe or a socket is `ENOTDIR`;
  - `rmdir("dir/")` removes `dir`, never a link's target; `.` and `..` are left to proot.
- **Covered:** `open`/`openat`/`creat`/`fopen` (and the `_2` and 64 forms), the `stat` family
  including glibc's pre-2.33 `__xstat` entry points (the Steam client uses them), `statx`, `access`,
  `readlink`, `realpath`, `getcwd` (and their `_FORTIFY_SOURCE` forms), `mkdir`, `unlink`/`rmdir`,
  `rename` of files, `symlink`, `chmod`, `utimens`/`utimes`, `truncate`, `statfs`/`statvfs`,
  `getxattr`/`listxattr`, `inotify_add_watch`, Unix `connect`/`bind`/`accept`, `getsockname`/
  `getpeername`, `shm_open`/`shm_unlink`, `get_nprocs`, `uname`/`gethostname`, `isatty`, and
  `getpwnam`/`getpwuid` (and `_r`) when `nsswitch.conf` sends `passwd` to `files` first with no action
  after it and the entry is in `/etc/passwd`, read as strictly as glibc reads it, buffer sizes
  included (Xwayland looks its user up for every client).
- **Left to proot:** anything the library cannot prove proot would answer the same way. That
  includes `O_TMPFILE`, unknown flags, a binding name with two guest paths, renaming a directory
  (proot moves the cwd records of every process under it), a blocking `accept` with nothing
  waiting, and a named socket whose host path does not fit `sun_path`. `PROOT_FP_LOG=<file>` logs
  each call left to proot with the program, call and path, which is how the remaining ones were
  found.
- **Library lookups.** ld.so finds a library by opening it in each directory of its search path in
  turn, and each failed open is a stop. A game's environment puts the game's own directory,
  `linuxarm64`, Steam's `*/video` directories and Proton's library directories ahead of `/usr/lib`:
  in an Among Us launch 72% of ld.so's opens failed. `libblaudit.so`'s `la_objsearch()` skips a
  candidate that proot would certainly fail with `ENOENT`: its directory walked as `canonicalize()`
  walks it (rootfs symlinks followed, nothing inside a binding followed, no glue, not `/proc`, `/dev`
  or `/sys`) and the name missing there, or a directory on the way missing (Proton's
  `lib/x86_64-linux-gnu` and `lib/i386-linux-gnu` on ARM64). Two ld.so details shape it:
  - a skipped candidate counts as an open that failed with ld.so's current `errno`, and ld.so gives
    up on the whole search path unless that is `ENOENT`/`EACCES`, so each lookup's first candidate
    is always opened for real;
  - the default directories are never skipped, so a library that is nowhere still fails with the
    same `dlerror()` text.

  It has no libc (an audit module gets a namespace of its own, and a libc there would be searched for
  along the same path) and maps the trampoline itself. `droiddeck-proton` sets `LD_AUDIT` for the
  game's tree when the fast path is on.
- **Locale archive.** With `LANG=C.UTF-8` and no locale archive, every glibc program made about 30
  failed opens at start-up. `droiddeck-session` builds `/usr/lib/locale/locale-archive` once per
  glibc package (`localedef --add-to-archive`, which needs `link()`: `DROIDDECK_LINK_RENAME=1` makes
  the preload fall back to a non-replacing rename).

### Checking it

`tools/proot/bench/equiv.py`, `equiv2.py` and `equiv3.py` run every covered call over awkward paths
(links into and out of binds, glue, `/proc` links of every kind, deleted files, pipes and sockets,
long host paths, trailing slashes, buffers of every size around a link's length, unstatable nodes,
`.`/`..`) under `run-device.sh fpoff` and `fastpath`. The outputs must match line for line: 222, 507
and 924 lines, all identical (the third includes `/etc/passwd` and `nsswitch.conf` variants
glibc must read the same way: comments, `+`/`-` lines, a bad or huge uid, a duplicate, CRLF, an
extra field, actions, `files` second, every `_r` buffer size around the line length). `fpbench` times the calls.

### Results

RedMagic (SM8850), 2026-10-09, the same Steam library and prefix. Timings are from builds without
the profiler, both on CI's proot; round 1 of each is left out (it re-stages the rootfs, and on this
branch builds the locale archive once).

| | `main` (8e1a676f) | this branch |
|---|---|---|
| Among Us (945360), launch to game window, rounds 2–4 | 11.95 / 11.95 / 11.95 s | **7.73 / 7.82 / 7.71 s** |
| Steam start to ready, rounds 2–4 | 7.01 / 6.94 / 6.97 s | 6.93 / 5.14 / 6.94 s |

A game launch is 4.2 s (35%) shorter, and the game runs as before (60 FPS on its menu). Steam's own
start is paced by the client and its network checks and does not move.

Four more games, the same way (a fresh client for each launch, three launches per build, median):

| launch to game window | `main` | this branch | saved |
|---|---|---|---|
| Palworld | 16.5 s | 12.1 s | 4.5 s (27%) |
| Fallout: New Vegas | 12.1 s | 7.7 s | 4.4 s (36%) |
| Stardew Valley | 34.1 s | 29.6 s | 4.5 s (13%) |
| FINAL FANTASY VII REMAKE INTERGRADE | 22.1 s | 16.5 s | 5.6 s (25%) |

Mortal Kombat X opened no window on either build and is left out.

With the profiler (`PROOT_PROFILE`), the Among Us launch plus a minute of play went from 279,560
stops and 5.2 s of tracer time to 23,919 stops and 0.48 s; 9–12k of what is left are Wine's
signals. A Steam start's first 90 s cost 0.78 s of tracer time, of which ld.so's opens are 0.33 s.

| per call (µs) | proot | fast path |
|---|---|---|
| `realpath` | 240 | 1.0 |
| `realpath` through a `c:` link | 312 | 1.0 |
| `realpath /proc/self/fd/N` | 408 | 9.9 |
| `stat` / `open+close` | 26 / 35 | 2.3 / 3.0 |
| `stat` of a missing `dir/` | 24 | 0.9 |
| `connect`+`accept`, Unix socket | 35 | 3.7 |
| `getpwuid` | 41.5 | 4.6 |
| `uname` / `isatty` | 28 / 29 | 0.006 / 0.17 |

### What is left

- **Signals.** Every signal delivered to a tracee is a stop. Wine's `SIGUSR2`/`SIGBUS` traffic was
  9–14k stops in a Steam start and ~12k in a minute of Among Us, about a third of what remains.
  Not avoidable while proot traces.
- **ld.so's own opens.** Each library found is an open, and so is the first candidate of each lookup
  (Proton puts Steam's `ubuntu12_64/video` first): a skipped candidate fails with ld.so's `errno` as
  it stands, which is only known to be `ENOENT` after a real failure in the same lookup.
- **glibc's internal opens**: NSS lookups the passwd shortcut leaves alone (groups, hosts), locale
  and gconv loading, `opendir`. A proot-aware glibc (option 6) would cover them, and ld.so's opens
  with them.
- **Per exec:** proot's loader opens, `/etc/ld.so.preload`, ld.so's `uname`, `set_robust_list`
  (`SIGSYS`) and `brk`. A proot patch letting the loader open host paths from its own address range
  would take two stops off every exec.
- **`fchdir`**: proot keeps the cwd record, so it has to see each change (Wine changes directory
  around many file operations).
