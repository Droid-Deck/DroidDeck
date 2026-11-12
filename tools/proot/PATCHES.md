# proot patches carried by the app

Built by `tools/proot/build.sh` with the NDK on top of termux/proot `4dba3afb` (Termux's
5.1.107-70 package, the proot the Linux runtime ships at `opt/android-host/proot`; every
`file.c:line` site in that binary matches this commit). talloc is linked in statically from
Samba's release tarball, so the apk carries one self-contained `libproot.so` plus
`libproot-loader.so`, and `LinuxRuntime` prefers them over the runtime's copy.

Ported from WinNative (`feature/proot-enhancements`, e7af0f24):

- `0001-tracee-lookup-by-pid.patch` - tracees are hashed by pid, so each ptrace stop finds its
  tracee without walking one list entry per thread, and terminated tracees are swept only after a
  termination.
- `0002-canon-resolve-parent-at-once.patch` - a clean absolute guest path at least three
  directories deep under the rootfs binding alone has its parent opened once with `O_PATH` and
  taken as canonical when `/proc/self/fd` names the path proot would build, replacing an `lstat`
  per component. The final component of a call that does not follow it is no longer `lstat`ed.
  Extensions still see the parent's host path, and the fast path stays off while the f2fs
  workaround is active.
- `0003-clone3-flags-read-guard.patch` - a thread created with `clone3` keeps the flags it
  inherited when `struct clone_args` cannot be read, instead of being tracked as a fork.

Ported from WinNative (`main`, 53836ca9, "Fix/performance and vac"):

- `0004-seccomp-filter-by-argument.patch` - the seccomp filter traces `prctl` only for
  `PR_SET_DUMPABLE`, `setrlimit` only for `RLIMIT_STACK` and `prlimit64` only when it sets a new
  `RLIMIT_STACK`, the only cases proot acts on; every other call runs without a stop. `uname` is
  traced by the core only on x86_64, the one arch it rewrites; kompat still adds it for
  `--kernel-release`.
- `0005-seccomp-skip-unneeded-sysexit.patch` - a seccomp stop fetches the registers once and
  looks the filter flags up by syscall number, instead of a `PTRACE_GETEVENTMSG` and a second
  register fetch. The flags table is built from proot's list merged with the enabled extensions'
  (kompat and fake_id0 here), so it is exactly what the filter reports. `wait4`/`waitpid` left to
  the kernel and `accept`/`accept4` without a sockaddr skip their exit stop, unless an extension
  replaced the syscall (kompat turning `accept4` into `accept`).
- `0006-clone3-exit-signal.patch` - `clone3` flags and exit signal are read as the 64-bit fields
  of `struct clone_args`, so a `clone3` fork is told apart from a thread when proot decides how the
  child is traced.
- `0007-proc-self-thread-group.patch` - tracees track their thread group, `/proc/self` names it
  instead of the calling thread, and `/proc/thread-self` resolves to `/proc/<tgid>/task/<tid>`.
- `0008-fchmodat2-openat2.patch` - `fchmodat2` paths are translated (honouring
  `AT_SYMLINK_NOFOLLOW`), and syscall numbers past the end of a table are rejected instead of read.
  Its `openat2` answer is replaced by `0019`.
- `0009-tracee-relatives-sweep.patch` - tracees count their children, so a terminating thread
  with no children or ptracees no longer walks every tracee; the per-stop memory collector is
  emptied instead of freed and reallocated.

Added for Flatpak:

- `0010-new-mount-api-enosys.patch` - `open_tree`, `move_mount`, `fspick` and `mount_setattr`
  answer `ENOSYS`. They take paths proot never translated, so libglnx's `open_tree(AT_FDCWD, "/")`
  handed Flatpak a descriptor for the host's root and it tried to create directories there
  (`mkdirat(root): Operation not permitted`); unsupported, libglnx falls back to `openat`.
- `0012-android-hardlink-denial.patch` - Android's SELinux policy denies apps hard links, so
  `linkat` fails with `EACCES` and Flatpak could not create its repo (`Creating repo: linkat:
  Permission denied`). `O_TMPFILE` answers `EOPNOTSUPP`, so libglnx writes a named temporary file
  and renames it, and a denied link answers `EPERM`, on which ostree's checkout copies instead.
- `0017-android-execstack-denial.patch` - the same policy denies apps executable stacks, so the
  `mprotect(PROT_READ | PROT_WRITE | PROT_EXEC | PROT_GROWSDOWN)` with which `ld.so` makes the main
  stack executable for an object whose `PT_GNU_STACK` asks for it fails with `EACCES`, and the
  program stops before `main` ("cannot enable executable stack as shared object requires:
  Permission denied", `libQt5Gui.so.5` in ONLYOFFICE's Flatpak). The filter traces `mprotect` only
  for that exact request, and a denied one answers success: the stack stays non-executable, as
  Android keeps it anyway, and objects that only carry the flag load.
Added by DroidDeck:

- `0011-kompat-utsname-only.patch` - `--kernel-release` (the guest's `DroidDeck` hostname) loads
  kompat, whose filter traps `futex`, `fcntl`, `epoll_pwait`, `pselect6`, `pipe2`, `eventfd2`,
  `socket` and more, and which strips `AT_SYSINFO_EHDR` on every `execve`, so glibc runs without
  the vDSO. When the virtual release is not older than the real kernel and the hwcap is left
  alone, every one of those handlers is a no-op: kompat now traces only `uname`, `sethostname`
  and `setdomainname` and leaves the auxv as the kernel wrote it. On an SD 8 Gen 2 guest this
  took a futex ping-pong from 467 to 97 us, `epoll_pwait` from 60 to 0.8 us and `fcntl` from
  40-107 to 0.4 us, and `clock_gettime` back to the vDSO.
- `0012-fake_id0-identity-only.patch` - `-i uid:gid` (for Xwayland's setgid/setuid before it runs
  xkbcomp) loads fake_id0, whose filter traps every `fstat`/`newfstatat`/`stat` (entry and exit),
  every `sendmsg` (all Wayland, X11, Chromium and PulseAudio traffic), `socket`, `getsockopt`, the
  `get*id` family and the chown/chmod family. When the ids given are the ones proot really has and
  are not 0, every one of those handlers is a no-op; fake_id0 now traces only the `set*id` family
  and the xattr permission fixups, and leaves set-user-ID bits alone on `execve` (Android mounts the
  app's data `nosuid`, so the kernel would not honour them either), which keeps the ids fixed. On an
  x86_64 host build under the same options: `fstat` 41.7 -> 1.3 us, `sendmsg` 18.0 -> 2.5 us.
- `0013-seccomp-ioctl-by-request-and-kernel-exit-stops.patch` - every `ioctl` stopped on entry and
  exit, which is every GPU submit and wait. The filter now traces only the requests enter.c and
  exit.c act on (`TCSETSF`, the four termios2 requests, `FICLONE`) and allows the rest at once, and
  the ioctl block is emitted first (after WinNative 53836ca9, which traces only the termios2 ones).
  The `faccessat2` exit stop (termux 5ba8b95, for glibc's ENOSYS fallback on kernels before 5.8) and
  the `statx` one (emulation on kernels before 4.11) are dropped on kernels 5.8 and newer; patch
  `0015` keeps it on 4.11–5.7 to return the mount ID added in 5.8.
  Host build: `ioctl` 31.6 -> 0.5 us; `stat`/`statx`/`faccessat2` about 44 -> 28 us (one stop).
- `0015-statx-mnt-id-fdinfo.patch` - kernels before 5.8 can complete `statx` without the mount ID
  that libglnx requires while chasing an open descriptor. PRoot keeps the exit stop on those
  kernels and fills only a missing ordinary `STATX_MNT_ID` for `AT_EMPTY_PATH` after pinning the
  tracee descriptor, matching its device and inode to the successful statx result, and reading
  `mnt_id` from the pin's fdinfo. A later close/reuse of the tracee fd cannot redirect that read;
  a concurrent reuse for the same inode through a different bind mount is indistinguishable at
  the exit stop. It does not synthesize `STATX_MNT_ID_UNIQUE`.
- `0016-robust-list-from-seccomp.patch` - Android's app policy refuses `set_robust_list` and
  `get_robust_list`. proot still answers the first with `ENOSYS` but keeps the head each thread
  offered (cleared on `execve`, none in a new task), and answers the second for the calling thread
  with it. SteamVR's `vrclient.so` asks for the head through libc's `syscall()`, which
  `libblsession.so`'s robust.c never sees: `libopenvr_api.so` opens it `RTLD_DEEPBIND`. Steam
  aborted on "futex robust_list not initialized by pthreads" as soon as SteamVR was registered.

Prototype (inert unless `PROOT_FASTPATH` is set; see `docs/development/proot-performance.md`):

- `0014-fastpath-trampoline.patch` - with `PROOT_FASTPATH`, every `SECCOMP_RET_TRACE` in the filter
  is preceded by a check of the caller's address, and a syscall made from the fast path's trampoline
  page (`fastpath/fastpath.c`, `0xffff00000`) runs without a stop: the tracee already translated it.
  The check comes after the syscall-number dispatch, so untraced syscalls stay constant-ALLOW and keep
  the kernel's seccomp action cache (checking first cost every syscall the full filter: +0.5 ms an
  exec). `chdir`/`fchdir`, still emulated, also move the kernel's cwd to the host directory, and the
  first tracee starts at `-w`'s, so relative lookups inside the tracee resolve where proot would.
  SM8850 (adb shell): `stat` 25.3 -> 0.6 us, `open+close` 25.3 -> 0.9 us, ENOENT 22.9 -> 1.6 us,
  8 threads `stat`ing 89k -> 3.6M/s; equivalence suite (`bench/equiv.py`) byte-identical to proot.

- `0019-openat2-emulation.patch` - `openat2` without `resolve` flags runs as a translated `openat`
  with the same flag and mode checks as the kernel. Any `resolve` flag still answers `ENOSYS`,
  because PRoot cannot confine symlinks or mounts, so callers keep their own confined fallback.
- `0022-fake-root-statx-owners.patch` - with `0013` dropping the `statx` exit stop, fake root never
  rewrote the owner `statx` returns, so files read as owned by the app's uid while the process was
  root. Rust's `metadata` and GNU `stat` use `statx`, so uutils' `test -w` (Ubuntu's `/usr/bin/test`)
  said root could not write to its own directories and apt's AppStream hook never built Discover's
  catalog. fake_id0 keeps that exit stop when it fakes ids; identity-only mode is unchanged.
- `0023-reused-pid-new-child.patch` - when a thread that is not its process's leader calls `execve`,
  the kernel retires that thread's ID without reporting an exit, so PRoot keeps its entry. Once the
  IDs wrap (32768 on many Android kernels) a new child can get that ID, and PRoot aborted on the
  assertion that a new child has no state, ending the whole session. Chromium-based programs such as
  Steam's client do this. PRoot now retires the stale entry and sets the new child up from scratch.
- `0024-execveat-absolute-path.patch` - `execveat` with an absolute path and neither `AT_EMPTY_PATH`
  nor `AT_SYMLINK_NOFOLLOW` runs as `execve`. The kernel ignores the directory descriptor for such a
  path, and PRoot refused the call unless the descriptor was `AT_FDCWD`. FEX starts every child of an
  emulated program with `execveat(fd, "/proc/self/exe")`, so x86 programs in a Flatpak sandbox, which
  does not carry the session's preloads, could not start other programs.
- `0025-fastpath-trampoline-sigsys-enosys.patch` - Android 10 and older refuse `statx` with
  `SIGSYS`. A `statx` from the fast path's trampoline page already carries the host path, but
  proot's `SIGSYS` emulation translated it again as a guest path (`<rootfs>/<rootfs>/...`) and
  returned `ENOENT`, so every existing file looked missing to Rust's `metadata` and GNU `stat`:
  uutils `mkdir -p` on an existing directory failed with "Already exists" and Steam setup stopped
  (Pico 4, Android 10). A refused syscall from the trampoline now gets `ENOSYS` without
  translation or extension handling, and the fast path falls back as it already does for
  `statx` (`newfstatat`) and `memfd_create`. Syscalls outside the trampoline and sessions
  without `PROOT_FASTPATH` are unchanged. `bench/statx-trap.c` traps `statx` and checks an
  existing directory still reads as one.
- `0026-arm64-kernel-program-layout.patch` - on ARM64, PRoot put its loader at 128 GiB, a PIE
  program at 192 GiB and its interpreter at 252 GiB. Android kernels give a process a 39-bit
  (512 GiB) address space, so those three split the low half into holes of 64 GiB or less, and a
  program that reserves one large contiguous range there could not get it. The Linux kernel loads
  a PIE program at two thirds of the address space (`ELF_ET_DYN_BASE`, 0x5555555000 with 39 bits)
  and keeps the range below it free; the loader, program and interpreter now sit there too, which
  leaves 64 GiB to 336 GiB free as on a native kernel. Switch emulators that run guest code
  natively reserve 256 GiB above 64 GiB for guest memory: under PRoot the reservation failed and
  the first guest instruction faulted forever. 48-bit address spaces and 32-bit programs are
  unchanged in practice.
- `0027-guest-seccomp-sigsys.patch` - PRoot treated every seccomp `SIGSYS` as Android's app policy
  refusing a call: it emulated the few it knows and answered the rest with `ENOSYS`, so a filter
  the guest installs itself never reached its own handler. Firefox's content sandbox traps
  `openat` and opens files through a broker in the parent; every font open came back `ENOSYS`,
  and each tab crashed in `gfxFontGroup::GetDefaultFont`. Android's policy traps with
  `SECCOMP_RET_DATA` 0, while Firefox's and Chromium's filters tag their traps, so a tagged
  `SIGSYS` is now delivered to the guest. A trap from the fast path's trampoline still gets
  `ENOSYS`, because its arguments are host paths; the fast path then stops for that process and
  repeats the call through a plain `svc`, which the guest's filter traps with the guest path.
  `bench/guest-trap.c` installs a tagged `openat` trap and checks its handler answers the call.
- `0028-guest-seccomp-keep-sigsys-unblocked.patch` - a seccomp trap that fires while `SIGSYS` is
  blocked makes the kernel reset the `SIGSYS` handler to `SIG_DFL` (`force_sig_seccomp`), even
  though PRoot answers the call and swallows the signal. glibc starts every thread with all signals
  blocked and calls `set_robust_list`, which Android's policy traps, so a process with a filter of
  its own lost its handler to a new thread, and the next trap of that filter killed it with signal
  31. Firefox's content processes died this way soon after `Sandbox: using seccomp tsync`. Two
  changes keep `SIGSYS` unblocked:
  - A new thread starts with `SIGSYS` unblocked: at its first stop PRoot clears it from the
    thread's mask (`PTRACE_GETSIGMASK`/`PTRACE_SETSIGMASK`), and glibc then restores the mask the
    thread is meant to have. Firefox sets its handler before its filter, so a thread started in
    between, or started before either, still reset it, and Firefox stopped at its own check that
    the handler is still set (`SIGSYS not already set to a siginfo handler?`, signal 11). A thread a
    debugger in the guest holds at that stop is unblocked too. Forked processes are left alone,
    since no restore follows. This adds two `ptrace` calls per thread and
    no stop; 2000 thread starts measured the same before and after (62.5 us each, median of 9).
  - When the guest installs a filter (`seccomp(SECCOMP_SET_MODE_FILTER)` or `prctl(PR_SET_SECCOMP)`,
    the only cases the filter now traces), PRoot first installs one through the same call, on the
    same threads and with `TSYNC` when the guest asks for it, that traces `rt_sigprocmask` with
    `SIG_BLOCK` or `SIG_SETMASK` and a set, and drops `SIGSYS` from those masks. Firefox's content
    filter allows `rt_sigprocmask`, so the guard keeps firing after the install. The guest's call
    then runs as a chained syscall. Its flag is committed only if the call succeeds: a filter the
    kernel rejects, or a `TSYNC` that cannot sync a thread (it returns that thread's ID), leaves no
    flag, and the guard's stops then change nothing. PRoot installs its own filter before the first
    `execve`, so installs before it are skipped, and a guest install is tracked even when PRoot's
    own one failed. A call without a filter, such as the probes Firefox's `SandboxInfo` makes in
    every process, is left alone. Tasks without a filter of their own take no new stops; with one,
    a thread start takes four, and a headless Firefox screenshot takes 68 in all. Without PRoot's
    own filter the masks are filtered at the stops it already takes.

  Not covered: a handler whose `sa_mask` blocks `SIGSYS` making an Android-refused call, masks set
  through raw syscalls in processes without a filter, 32-bit guests (the guard matches the native
  ABI only), and on Android 14 and older a process's first thread created between its handler and
  its filter, since glibc then tries `clone3` once with every signal blocked. An outer wrapper that
  installs a filter of its own, such as a test harness trapping `set_robust_list`, is guarded too,
  which hides the races on a host unless the harness sits outside the guard.
  `bench/guest-trap.c` starts a thread between two trapped opens under a filter shaped like
  Firefox's. `bench/guest-trap-race.c` runs Firefox's order (handler, a wait until workers have started
  threads, a check that the handler is still set, a `TSYNC` filter, trapped opens) in 20
  processes; under the guest's own `strace -f` it covers a debugger holding new threads. `bench/guest-trap-tsync.c`, `bench/guest-trap-exec.c` and
  `bench/guest-seccomp-install.c` cover a `TSYNC` install with threads already running, survival
  across `execve`, empty-filter probes, rejected filters and a `TSYNC` that cannot sync. On an
  x86_64 host build with a `set_robust_list` trap outside the guard, `guest-trap-race.c` passes
  20/20, and fails 20/20 at the handler check with only the guard. Firefox 158's headless
  screenshot with the content sandbox on renders exactly as without PRoot, with and without the
  fast path. With `PROOT_NO_SECCOMP=1` on x86_64 a content process stops at Firefox's
  `Illegal mixing of system call ABIs` check instead, a check arm64 does not have.

Old kernel fixes:

- `0015-lost-fork-events.patch` - works around a problem reported on Xiaomi's msm 4.14 kernel:
  the kernel sometimes reports a new process or thread but gives its ID as zero. Without that ID,
  PRoot cannot finish setting it up, which can leave the session stuck. The patch searches `/proc`
  for the child. If exactly one matches, PRoot uses its existing setup code. If several threads
  match, it waits for the creating thread's `clone` call to return and uses the child ID returned
  by the kernel instead. The original clone flags are preserved. When the kernel supplies a valid
  ID in the notification, normal setup is unchanged. No timers or background polling are added.
  Completely missing notifications and ambiguous matches for separate processes are not recovered.

Development diagnostics:

- `0018-syscall-handler-profile.patch` - `PROOT_PROFILE=1` enables per-syscall entry/exit handler
  counts, wall/CPU time, periodic cumulative JSON totals, and bounded slow-handler samples on
  stderr. `profile.h` is copied into the source tree by `build.sh`. It adds no ptrace stops;
  handler timing excludes tracee kernel time and scheduling before handler entry. See
  `docs/development/proot-performance.md` for the capture and library-override workflow.
