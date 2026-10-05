# Steam SD allocation on Thor

Investigation recorded on 2026-10-04. Device measurements and PR states below are a
snapshot of that day's work. All event times are EDT (UTC−04:00).

A fresh 1000xRESIST install on the Thor SD card completed with the native-path and
allocation-fallback patches: 2,216 files preallocated in **6m44s**, followed by a
successful download and commit, **16m36s total**. The observed allocation wrappers
reported zero failures and roughly 7.1 seconds of aggregate execution time.
That wrapper measurement does **not** account for the entire preallocation phase.

Native directory access was substantially faster than Android's FUSE view in a
small controlled probe. Kernel samples during the remaining delay showed exFAT
file-creation and size-update waits. This supports further investigation of SD
metadata writes. It does not establish that PRoot is free, that `dirsync` alone
causes the delay, or that SD now matches internal-storage install speed.

Selected source-log excerpts are preserved in
[the evidence file](evidence/thor-sd-storage-2026-10-04.txt). The original full logs
contain unrelated sessions and are not needed to interpret those excerpts.

## Device and storage

| Item | Observed configuration |
|---|---|
| Device | AYN Thor, Android 13, kernel 5.15.123, 12 GB RAM and 8 GB swap |
| App | Production-signed `com.droiddeck.launcher`, normal app UID 10195 |
| Configured SD library | `/storage/FF7F-F56A/Android/data/com.droiddeck.launcher/files/steam` |
| Native view of the same library | `/mnt/media_rw/FF7F-F56A/Android/data/com.droiddeck.launcher/files/steam` |
| Guest content aliases | `/mnt/droiddeck-sd` and legacy `/mnt/bannerlator-sd` |
| SD filesystem | Native exFAT; its Android `/storage` view reports FUSE |
| Native filesystem magic | `0x2011BAB0`, matching the NDK Linux header |
| Internal comparison location | App-private cache under `/data` |

The native SD mount was observed with these options:

```text
rw,dirsync,nosuid,nodev,noexec,noatime,uid=1023,gid=1023,fmask=0007,dmask=0007,allow_utime=0020,iocharset=utf8,errors=remount-ro
```

The device already had root and SELinux Permissive. Neither state was introduced
by this investigation. Root was used for inspection and temporary experiments.
The validated Steam session ran with the app's normal UID and existing groups,
without a root launcher or an app-specific Magisk root grant.

This distinction does not prove the native path works on an unrooted device with
enforcing SELinux. The production change tests access in the app and retains the
configured path when verification fails.

## Initial report and allocation failure

The [linked Discord report](https://discord.com/channels/1552707793247539220/1555632840388116491/1556271955076710560)
included a newer diagnostic ZIP with `storage.log`. Unlike the earlier capture
that lacked allocation instrumentation, this capture established:

- The selected secondary library was removable and its exposed filesystem was
  FUSE.
- Steam called mode-zero `fallocate` and received `EOPNOTSUPP` (errno 95).
- That observed failure returned in 212 microseconds. It was not itself a
  multi-minute blocked call.

A Thor baseline capture likewise showed `fallocate` failing with errno 95 in
310 microseconds. These establish an unsupported allocation API on the tested
paths. They do not attribute the later Steam delay to any particular fallback.

The suggestion that glibc's `posix_fallocate` emulation accounts for the whole
hang was not established by those logs. The tested Steam calls included
`fallocate`; directory operations and other calls occur outside that wrapper.

## Published patches and the tested build

The main base was `26cb9e2f038cdbab4cdc8670d2f64c18d1d90875`. These heads were
checked again while preparing this report; all three storage PRs were open with
successful Build APK checks and clean merge states. This is a dated snapshot,
not a statement about their future merge status.

| PR | Tested head | Behavior |
|---|---|---|
| [#221](https://github.com/Droid-Deck/DroidDeck/pull/221) | `7962c19283bfb0f18a5c7672e08a879814a3d5a1` | Verify a native SD content alias and handle safe case aliases in the path fast path |
| [#229](https://github.com/Droid-Deck/DroidDeck/pull/229) | `4628ae4c92cc5813013537165a0e95886e6de904` | Use a verified truncate fallback for unsupported selected-library allocation |
| [#233](https://github.com/Droid-Deck/DroidDeck/pull/233) | `5c6765233ae5405d7bd4c1b29f20c9e8343a1fed` | Preserve the complete fast-path binding table, including later private overrides |
| [#225](https://github.com/Droid-Deck/DroidDeck/pull/225) | `0b5908d6d63080f625b51bab3439e2e68466631e` | Repair repeated failed Steam client starts; reviewed separately, excluded from this storage build |

### Native content path and case handling: #221

For a configured `/storage/...` library, the launcher checks the corresponding
`/mnt/media_rw/...` candidate. It requires usable directory permissions and
performs a write/read/delete identity probe through both views. Failure keeps
the original configured path.

Only the shared SD content bindings change. The configured path remains the
library's identity for private prefixes, migrations and other private storage.
This avoids moving existing saves or runtime state when selecting a faster view.
Removable-volume detection also uses the original configured path because
Android's volume API need not recognize the native alias.

The C fast path permits case-only canonical-parent differences on known
FUSE/FAT/exFAT filesystems only after checking directory components without
following symlinks. It also handles the supported `F_OK` plus
`AT_SYMLINK_NOFOLLOW` case. Ambiguous paths still fall back to PRoot.

The final #221 diff was four files, 98 insertions and 20 deletions, including
28 new test lines. The experimental directory cache was removed from that diff.

### Allocation fallback: #229

The launcher first creates a temporary 1 MiB file on the selected content path,
extends it, and checks that `st_blocks` reports at least 2,048 512-byte blocks.
The file must also be cleaned up successfully. The fallback is enabled only for
a removable library that passes this reservation probe.

For file descriptors on the selected library device, the wrapper tries the real
allocation API. On mode-zero `fallocate` returning `EOPNOTSUPP`, it extends the
file with `ftruncate`. It validates the range, detects overflow and avoids
shrinking an existing file. Other modes and other errors retain their normal
behavior. The POSIX wrapper tries kernel allocation first to avoid unnecessary
libc emulation and preserves POSIX error-return and `errno` semantics.

This is a capability-tested fallback, not unconditional successful allocation
reporting. A failed extension still returns an error. The optimization operates
independently of the optional diagnostic toggle.

The reservation probe and one successful full install do not replace separate
future tests for ENOSPC, interrupted installs or other devices/filesystems.

### Complete binding table: #233

The real Steam session had **96 bindings**. The old C limit of 64 silently
dropped later mappings. Several of those were private overrides inside the
shared SD root, so truncation could send an operation to the wrong location or
return `ENOENT`.

#233 raises capacity to 256 and makes both the launcher and C implementation
disable the fast path if a complete table cannot be represented. Overlong paths
also cause fallback rather than partial mapping. This is a correctness fix as
well as a requirement for trusting the storage fast path.

Marker files existed only in private directories. A normal app-process probe
read them through these guest paths:

| Guest path under the library | Before #233 | After #233 |
|---|---|---|
| `SteamLinuxRuntime_4-arm64` | Correct private marker | Correct private marker |
| `steamlinuxruntime_4-arm64` | Correct private marker | Correct private marker |
| `proton experimental (arm64)` | `ENOENT` | Correct private marker |
| Legacy `steamapps/shadercache` | `ENOENT` | Correct private marker |

Each successful read returned the 17-byte `private-override\n` marker. The
temporary markers and helper executable were removed afterward.

### Deployment and checks

The full fresh install used #221 plus #229. #233 was then integrated locally,
tested, built and installed for the binding regression and game-launch check.
It was not merged on GitHub during this investigation.

The combined integration commit was
`f5d3e93b8eb8912ca3834f7447a6498c95250177`. The production-signed APK was installed
in place with `adb install -r`; its SHA-256 was:

```text
099a0d7e76b9462b3b238c6f1abeb03570ec9708ace3092adf178ec2bee63e2a
```

`tools/build_local.sh` completed for the combined build. The release unit tests
for `SecondaryLibraryTest` and `ProotFastPathTest` passed. The latter covers
96/256 bindings, exceeding capacity, and overlong paths. #225 was reviewed for
scope; its tests were not run as part of this storage validation.

## Fresh real Steam install

Game: **1000xRESIST**, app 1675830, depot 1675831, BuildID 21498291. Steam reported
6,582,351,824 download bytes and 7,997,085,707 staging bytes, approximately 8 GB.

| Event | Time | Evidence |
|---|---|---|
| Fresh preallocation began | 17:31:20 | Steam entered `Preallocating` |
| Preallocation completed | 17:38:04 | 2,216 files, 7,626 MB in Steam's log |
| Download/staging began | 17:38:06 | `Downloading,Staging` |
| Commit began | 17:45:25 | 2,230 updated files |
| Fully installed | 17:47:55 | `Fully Installed`, BuildID 21498291 |
| Scheduler completed | 17:47:56 | `No Error` |

Preallocation took **404 seconds**. Start to scheduler completion took
**996 seconds**. Live counters showed 2,216 allocation calls, zero allocation
failures and about **7.1 seconds aggregate allocation-wrapper time**. The saved
diagnostic detail log is capped at the first 64 allocation details per process;
that file alone cannot reproduce the live aggregate counter observation.

The final manifest had `StateFlags=4`, `UpdateResult=0`, all expected download
and staging bytes completed, and `SizeOnDisk=7997085707`. The completed executable
was under `steamapps/common/1000xRESIST`; the active staging directory was gone.

Transfer rates were mostly 117–132 Mbps, about 15–16.5 MB/s. Two root metadata
experiments ran during downloading, so this is not a clean network/card
throughput benchmark. No matched full internal-storage install was performed.

The run's `storage.log` reported native exFAT and a passed truncate reservation
probe. Recorded allocation details used `api=fallocate_ftruncate`, with
`result=0` and `api_error=0`.

## What the metadata measurements show

Metadata is filesystem bookkeeping: names, directory entries, sizes and space
allocation. Steam's `Preallocating` phase includes work outside the observed
allocation wrappers, including creating and locating files.

### Directory reads through native and FUSE paths

A helper ran in a normal app process and listed the same stable asset directory
from a saved staging tree through both host paths. It used the existing fast-path
syscall trampoline to bypass PRoot path translation while retaining the app's
Android permissions. The read buffer was 128 KiB.

| View | Elapsed | Successful `getdents64` calls | Directory bytes |
|---|---|---|---|
| Native exFAT | About 2.3 ms | 2 | 154,920 |
| Android FUSE | About 501 ms | 112 | 154,872 |

The views returned slightly different byte counts. This was a single
native-first pass, not a repeated benchmark with fully controlled cache state.
It demonstrates a large cost difference for that directory read, not a
corresponding multiplier for a full Steam install.

An earlier root listing during concurrent file creation measured roughly
5.56 seconds through FUSE and 9.25 milliseconds native. Its directory was
changing, so it is weaker evidence and is not used as a controlled comparison.

### File creation and size updates

During the fresh native-path Steam run, sampled kernel stacks included:

```text
__sync_dirty_buffer -> exfat_update_bh -> exfat_init_ext_entry
    -> exfat_add_entry -> exfat_create -> path_openat

__wait_on_buffer -> exfat_update_bhs -> exfat_free_dentry_set
    -> __exfat_write_inode -> exfat_truncate -> do_sys_ftruncate
```

These were live samples, not a continuous attribution profile. They show time
waiting in the native filesystem's creation/update paths during the delay.

A root-only helper created a temporary directory, then 64 long-named files using
`open(O_CREAT|O_EXCL|O_WRONLY, 0600)`, `ftruncate(fd, 262144)` and `close`. It timed
the creation and truncate calls separately and removed its files afterward.

| Location/run | Sum of open times | Sum of truncate times | Whole loop |
|---|---|---|---|
| Native SD, original mount | 6.707435 s | 0.084806 s | 6.794807 s |
| Native SD, later repeat | 25.181255 s | 0.309577 s | 25.493210 s |
| Internal app cache, live observation | About 7.88 ms | About 0.498 ms | About 9.1 ms |

The SD results varied substantially, and these root probes do not reproduce the
normal app's complete Steam workload. The measured internal probe is not a full
internal-install benchmark. Within these probes, file creation cost far more
than the observed truncate calls.

### `dirsync` and the failed remount comparison

The [Linux mount documentation](https://man7.org/linux/man-pages/man2/mount.2.html)
defines `MS_DIRSYNC` as synchronous directory changes. Combined with the observed
exFAT buffer waits, this makes synchronous SD metadata updates a strong lead.
It remains an inference about the remaining overall delay.

A toybox remount attempt returned `Invalid argument`. A direct mount-syscall
experiment changed the root view, but Steam's observed mount table still showed
`dirsync`. A subsequent 64-file probe took 2.112288 seconds; because the effective
flag change was not established and timings were variable, this is **not proof
that removing `dirsync` improves Steam allocation**. The Linux documentation also
notes that attempts to change `MS_DIRSYNC` during a remount are silently ignored.

Original effective options were restored and checked in root and app/process
views. No remount or root-launch behavior is included in the production patches.

### What remains attributable to PRoot

One sampled Steam worker was executing an `openat` through the fast-path
trampoline at `0xffff00020`, with an absolute native host path. That establishes
that some operations bypassed PRoot's path translation in the actual session.
Other syscalls still use PRoot.

There was no matched PRoot-versus-direct full preallocation comparison. The
fraction of the 404 seconds attributable to PRoot is therefore unknown. The
earlier [PRoot performance notes](proot-performance.md) concern different hardware
and workloads and cannot supply that missing percentage.

Seven seconds in public allocation wrappers does not mean the remaining
397 seconds are unrelated to allocation: libc-internal/direct-syscall bypasses,
explicit size changes and file-creation work may occur outside those counters.

## Experiments that did not become the fix

- **Directory caching:** eager scans held up directory open; creation-driven
  invalidation repeatedly reread growing directories. The capped design could
  also read and discard a large directory before rereading it. A lazy variant
  still left 182 files taking 4m49s while the allocation wrappers accounted for
  only 0.879 seconds. These were interrupted/resumed runs, not a matched A/B
  benchmark. The final #221 snapshot removes the cache entirely.
- **Allocation fallback alone:** a FUSE-path attempt was still slow after
  unsupported allocation calls were handled. A later eager-cache resumed run
  accumulated roughly 8.36 seconds in 1,435 successful allocation calls over
  about 36 minutes of wall time. Successful wrappers alone were insufficient.
- **Launching Steam through root:** experimental wrappers failed compositor
  startup. They did not produce allocation measurements and were reverted.
- **Changing mount flags:** effective removal of `dirsync` from Steam's view was
  not demonstrated. The apparent probe speedup is not a validated fix.
- **Replacing PRoot or reformatting storage:** no such replacement, adoptable
  storage conversion or ext4 loop-image deployment was validated here.

The early FUSE runs used partial staging state. Their file counts and durations
must not be compared to the fresh 2,216-file run as a precise speedup ratio.

## Post-install launch and separate memory failure

After deploying #221 + #229 + #233, the installed SD game was launched through
Steam. First-run Proton prefix initialization preceded the game window.

At **18:10:45**, the compositor changed from Steam Big Picture to **1000xRESIST**
and received the game's 1280×720 GPU frames. A screenshot showed the game loading
screen and spinner. A main menu or sustained gameplay was not validated.

At **18:13:48.454**, Android recorded the foreground app's exit as reason 3,
`LOW_MEMORY`, with main-app PSS 227 MB and RSS 344 MB. Those figures describe that
process, not the complete native process tree or its peak memory use. Subsequent
gamescope/reaper shutdown messages followed the parent being killed.

This was after the install had completed. It is a separate unresolved launch/
memory issue and does not undo the successful allocation/download result.
Post-kill free-memory readings cannot establish peak memory pressure.

## Remaining questions and next useful tests

1. Run the same fresh game install on internal and secondary storage with the
   same manifest, awake device and no competing benchmarks. Compare preallocation,
   download/staging and commit separately.
2. Match the file-creation/extension workload through PRoot and through the normal
   app's direct syscall path. Keep UID, filesystem, names and sizes identical,
   repeat runs and record background I/O. This can quantify the remaining tracer
   contribution without changing storage semantics.
3. Establish an actual controlled difference in synchronous directory behavior
   before attributing a speedup to mount flags. A remount return code alone is
   insufficient; this is separate from a portable app patch.
4. Validate native-path fallback under enforcing SELinux and on unrooted devices,
   plus allocation behavior near ENOSPC and after interruption. Preserve the
   configured-path fallback if either access or reservation verification fails.
5. Investigate the low-memory exit with process-tree/cgroup memory and Android
   kill evidence during loading. Treat it separately from install performance.

At this point, one fresh SD install and the binding regression are validated.
Internal-speed parity, the exact PRoot share, a `dirsync` remedy, broad device
coverage and stable gameplay remain unproven.

## Evidence inventory and handoff state

The device captures used for the final measurements were:

- `2026-10-04-62-app-raw-probe`: normal-app native/FUSE directory probe.
- `2026-10-04-64-steam`: fresh complete allocation/download/commit.
- Probe sessions before and after #233: private binding markers.
- `2026-10-04-67-steam`: combined-build launch, followed by Android's low-memory
  kill; the exit reason was obtained separately with `dumpsys activity exit-info`.

Local originals during the investigation were under
`/tmp/droiddeck-storage-live/`: `direct-content-completed.log`,
`direct-storage-completed.log`, `app-raw-probe-session.log`,
`bind-probe-before.log`, `bind-probe-after.log`,
`metadata-mount-comparison.log`, `metadata-syscall-comparison.log`,
`game-launch-exit-info.txt`, `combined-233-build.log` and
`combined-233-tests.log`. The repository evidence file preserves the selected
excerpts even if those temporary local files disappear. Live-only counters,
kernel samples and the internal metadata timing are identified above rather
than represented as saved continuous traces.

The combined signed build and completed SD install remained on Thor. Storage
diagnostics remained enabled. Temporary probe files, executables and obsolete
partial staging were removed after validation. Original effective mount options
were restored; no app-specific Magisk root policy remained. The primary checkout's
unrelated `SessionService.kt` modification was preserved.
