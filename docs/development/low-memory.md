# Low-memory reclaim and userspace compression

The Steam session monitor uses the existing `libblsession.so` page-out request to reclaim
private anonymous launcher memory. The default remains a request 20 seconds after game focus,
with five minutes between requests. With client compression enabled, low system headroom can
also trigger a request while a game is loading, before its first focused window.

Every five seconds the monitor reads `MemAvailable`, swap headroom and memory PSI. Low
headroom means less than one fifth of total RAM (bounded to 512–1536 MiB), or at least 1%
`some avg10` memory stall with less than one third of RAM available. Pressure requests target
only the launcher processes already recognized by the monitor, never the game. They require
at least 128 MiB launcher RSS and enough free swap to cover its entire RSS while leaving
one fifth of swap (at least 256 MiB) free. The regular automatic client request uses the
same swap guard. An explicit session RAM cap retains its existing behavior.

Pressure requests are at least 30 seconds apart. Reclaiming less than 16 MiB doubles the
retry delay, up to five minutes. After game exit, speculative launcher readback is deferred
until the session is under any explicit RAM cap, pressure has subsided, and available RAM
can cover twice the launcher's swap while leaving the headroom reserve. Normal demand paging
continues while readback is deferred; Steam does not have to wait for the monitor to recover.
Missing memory counters disable automatic pressure reclaim and speculative readback. Missing
PSI alone still permits the `MemAvailable` fallback. No root settings, swap sizes, compression
algorithms, Android process priorities or game-specific settings are changed.

A bounded live test of the pressure helper on the FIT during Caravan SandWitch loading
requested page-out at 20:52:37 UTC, 18 seconds before the installed timed reclaimer. Aggregate
launcher RSS fell from 608 to 283 MiB across that request; this is summed process RSS, not a
measurement of net physical RAM saved. The game reached its main menu at 60 FPS. The test ran
the candidate monitor functions alongside the old APK's monitor, not a newly built APK;
the old monitor's later request is visible separately at 20:52:55 in the session log. This
does not establish gameplay performance or resolve Stellar Blade's larger memory failure.

Continuing an existing Caravan save reproduced a whole-session low-memory kill with the
pressure helper running. Android recorded `ApplicationExitInfo` reason `LOW_MEMORY`, and
`lmkd` killed the foreground app with `oom_score_adj=0` because the device was not responding.
The last half-second sample before the kill still had 2,172,612 KiB swap free. Over the
captured interval, system unevictable memory grew from 1,955,932 to 3,236,276 KiB and shmem
from 1,690,276 to 2,965,900 KiB, while the game's private swap reached 1,916,136 KiB. The
pressure helper found the launcher already largely reclaimed: its first request at Continue
reduced aggregate launcher RSS only from 263 to 255 MiB and backed off. Thus launcher reclaim
does not resolve this load failure, and this run did not exhaust swap. The unevictable/shmem
growth needs allocation attribution before treating it as a driver or GPU-memory diagnosis.

## Low-RAM devices: compressing idle game memory

On devices with at most 13 GiB `MemTotal` (every 12 GB phone and handheld and below), the
monitor also compresses the game session itself when memory runs out. The trigger is absolute
headroom, not a percentage: Android's `lmkd` killed a session on a 7.5 GiB device with
490 MiB still available, so a 95% used threshold (about 400 MiB left on 8 GB) is already past the
kill. A world load added 1 GiB of GPU memory in about ten seconds, and compressing 1.4 GiB took
2.6 seconds, so the request has to leave room for both.

Performance > Compress idle game memory turns this off (`BL_ZRAM_IDLE=0`); launcher compression
and the RAM cap are unaffected.

The session request runs when available memory falls below a quarter of RAM (bounded to
768–2560 MiB, 1.85 GiB on an 8 GB device), or below one and a half times that with at least 1%
`some avg10` memory stall. One request pages out every session process that loaded
`libblsession.so`: the game, Wine and its services, plus the launcher processes unless their
compression is turned off, under a single wait. It runs before the launcher-only check, which
can wait up to 12 seconds for acknowledgements. While a game runs and available memory is under
twice that level, memory is read every second instead of every five. Requests are at least 30 seconds apart and need the same swap reserve as launcher
requests. When the session has read back more than half of what the previous request compressed,
that memory is in use: the request is skipped and the wait doubles, up to five minutes. A
request whose memory stayed compressed resets it to 30 seconds. Below half the headroom the
wait is ignored and only the 30-second spacing applies. The state resets when the game exits. Devices above 13 GiB keep the
launcher-only behaviour.

Measured on a Retroid Pocket Duo (7.5 GiB, kernel 6.6, 6 GiB lzo-rle zram plus a 4 GiB swap file)
entering the world in World of Warcraft: Forever, one run each:

| | No session page-out | Session compressed at character select |
| --- | --- | --- |
| available before Enter World | 1,569–1,800 MiB | 2,329 MiB |
| game resident / in zram before Enter World | 1,919 / 13 MiB | 409 / 1,523 MiB |
| GPU (KGSL) memory during the load | 0.97 → 1.99 GiB | 0.96 → 1.99 GiB |
| lowest available | 491 MiB | 623 MiB |
| result | `LOW_MEMORY` kill of the whole session 58 s after Enter World | in the world at 35 FPS after three minutes |

The killed run had 7.4 GiB of swap free at the time: reclaim did not keep up with the GPU
allocation, not swap capacity. Little Nightmares at the same settings compressed from 1.41 GiB to
0.25 GiB resident in 2.6 seconds (1.1 GiB stored in about 0.5 GiB of zram) and read back 70 MiB in
the following minute at its menu and 440 MiB while loading the next area; frame rate stayed at
44–46 FPS with one or two short frame-time spikes. These are single runs, not averages.

On the PR build without any manual step, the monitor compressed WoW before character select and
again during the load; the game entered the world and stayed there at 33 FPS. Once in the world,
available memory settled near 1 GiB, under the headroom, and the first version of the policy
compressed 200–400 MiB every 30 seconds while the game read back about 15 MiB/s. The
read-back check therefore compares against the last request instead of all compressed memory.

A second run on that build showed the trigger arriving too late: character select sat at
1.5–1.6 GiB available, just above the original one-fifth headroom, so nothing was compressed
there. During the load, available memory crossed the headroom 9 seconds after Enter World, but the
launcher check ran first and waited 12 seconds for acknowledgements; the game request started at
21 seconds, moved about 200 MiB, and `lmkd` ended the session at 30 seconds. The quarter-of-RAM
level and the game-first single request address both.

Without root an app cannot write its memory cgroup's `memory.reclaim` (owned by `system`),
`process_madvise` on another process returns `EPERM`, `PAGEMAP_SCAN` is absent before Linux 6.7,
and KGSL's per-process GPU reclaim `state` is root-only. Page-out therefore runs inside each
process through the preload, and GPU memory itself cannot be compressed from userspace.

## CRAM and the bounded pager experiment

[Meta's CRAM proposal](https://lpc.events/event/20/contributions/2424/) uses memory compression
hardware to allow byte/cacheline reads without software decompression faults. A userspace
software pager cannot reproduce that hardware property. This is separate from Valve's device
memory cgroup prioritization work.

`tools/experiments/anonymous-memory-pager.c` is an opt-in feasibility probe, not a preload or
runtime component. Build it for the guest architecture with a Linux compiler:

```sh
cc -O2 -Wall -Wextra -Werror -pthread tools/experiments/anonymous-memory-pager.c -ldl -o /tmp/anonymous-memory-pager
/tmp/anonymous-memory-pager 64
```

It requires `liblz4.so.1` and `UFFD_USER_MODE_ONLY`. The requested arena is bounded to
1–256 MiB. Half its pages contain a repeating byte and half contain pseudorandom bytes;
these are synthetic data, not a game-memory compression estimate. The probe registers only
its own arena, packs compressed or incompressible pages into a separate store, discards the
original physical pages, then restores them on demand with `UFFDIO_COPY`. It checks the full
checksum and subsequent writes. A separate child checks kernel access to an evicted buffer.
The compressed store is retained until the probe ends; this is one eviction/restore cycle,
without hot/cold selection, concurrent eviction, lifecycle hooks or game integration.

On the Pocket FIT Elite's Android 6.6.82 kernel, tested from DroidDeck's unprivileged guest:

| Measurement | Result |
| --- | --- |
| unrestricted `userfaultfd` | `EPERM` |
| userspace-only creation and API negotiation | available |
| arena / compressed payload | 64 MiB / 33,767,424 bytes |
| process RSS before / after eviction | 68,864 / 36,340 KiB |
| restored pages / checksum / writes | 16,384 / intact / intact |
| resident scan / compress and evict / faulted scan | 46.851 / 17.289 / 446.524 ms |
| kernel pipe write from evicted buffer | `EFAULT` |

These timings include the full checksum scan and are one synthetic run, not a comparison
against zram or a game-performance result. The normal unrestricted pager interface was denied;
`/dev/userfaultfd` was also inaccessible. The kernel-access result is a hard integration
constraint: a transparent heap-wide pager cannot assume every access comes from userspace.
Wine/FEX signal handling, syscall buffers, shared and GPU memory, mapping changes, concurrent
writes and allocation overhead would all need a correctness design before enabling such a
pager in a real game. The experiment is deliberately excluded from runtime builds.
