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
