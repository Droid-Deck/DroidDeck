# Automatic memory management

The Steam session keeps enough memory free on its own. There are no memory settings: the
session monitor in `droiddeck-session` measures how much memory is available and how fast it is
being taken, and compresses just enough idle memory into zRAM to stay ahead of it.

## What the monitor measures

Each pass reads four things:

- `MemAvailable` from `/proc/meminfo`.
- The fall rate: how much `MemAvailable` dropped since the last pass, plus what the monitor
  itself freed in between, per second. A peak decays by a quarter per pass, so one burst keeps
  the monitor alert for a few seconds without holding it there.
- Memory stall: the change in the `some` total of `/proc/pressure/memory`, as a percentage of
  the time between passes. A missing PSI file reads as no stall.
- zRAM room and saving: free space on the zRAM swap devices in `/proc/swaps` after a reserve
  of one eighth of zRAM (at least 128 MiB), and the measured saving per compressed byte from
  `/sys/block/zram0/mm_stat` (bounded to 1.2:1–6:1, 2.5:1 before 64 MiB has been stored).
  Disk swap is not counted as room: paging to storage is what causes long stalls.

The monitor sleeps five seconds between passes while more than two fifths of RAM is available
and memory is steady, and one second otherwise.

## Levels

| Level | Value | Meaning |
| --- | --- | --- |
| Floor | one eighth of RAM, bounded to 640–1280 MiB | Below this the system is close to killing the session. `lmkd` ended a session on a 7.5 GiB device with 490 MiB available. |
| Target | floor + ten seconds of the fall rate | Where the monitor wants available memory to stay. |
| Burst | two seconds of the fall rate, when that is at least 64 MiB and less than two fifths of RAM is available | Memory that will be taken before the next passes can react. |

The amount to free is the distance to the target or the burst, whichever is larger. The hard
part of it is what available memory is short of the floor, and nothing when it is above. With
a memory stall of at least 1% the hard part is two seconds of the fall rate when that is
larger, whatever the size of the fall and however much memory is available.

## What gets compressed, in order

1. The Steam client and hidden launchers, while a game is running and the client is not known
   to be in front (unreadable focus data counts as not in front), or at any time when there is
   a hard need. Without a hard need the client is left alone for 30 seconds after
   it was read back.
2. Processes of the session that loaded `libddsession.so` (the game, Wine and its services),
   largest first, only for the hard part that the client did not cover. One pass moves at
   most 256 MiB.

Both use the preload's cold-only request (`2 <kB>`): each process pages out only memory it has
not touched recently and stops at the budget, so memory in use stays in RAM and is not read
straight back. The budget is the amount to free divided by the measured saving, limited to the
zRAM room. With less than half the floor available the request ignores heat (`3 <kB>`), moves
up to 1 GiB, and runs at most once every ten seconds.

Two things keep memory from going in and out of zRAM. The preload remembers each 1 MiB chunk
it paged out: when a quarter or more of those pages are resident again at the next visit, the
chunk is hot and is passed over for the next 64 visits, then 128, then 255. And the monitor
reads `pswpin` from `/proc/vmstat`: what was read back from swap, fading over half a minute,
is taken off what one pass may move (1 GiB in all, at most 256 MiB of it from the session). A device
whose working set does not fit therefore stops being trimmed and is left to the kernel, until
the forced level is reached.

A pass that gives back less than a quarter of what the floor is short by is followed by a wait:
five seconds, doubling after each such pass up to a minute. The wait ends early when available
memory drops another eighth of the floor or reaches the forced level, and is forgotten after a
pass that gives back enough or once nothing is needed.

Requests go to one process at a time. The monitor waits for that process's acknowledgement
(the kB it paged out) before the next, reads `MemAvailable` again, and stops once the budget is
met or available memory is back at the level the pass was started for. A process that has not
started within half a second, or has not finished when the pass (client and session together)
has waited five seconds in total or six seconds have gone by since it began, is skipped for a
minute, and a late answer from it is discarded. A process is known by its number and start time, so a new process that got the
number of a skipped one is asked. Processes with less
than 8 MiB of anonymous memory are skipped. While the app pages the session out for suspend, or
reads it back on resume, it holds the monitor off with `/tmp/.dd-zram.hold`. A page-out writes
`1` into that file. A read-back writes `2 0`, which asks for nothing, and puts `0` into
`/tmp/.dd-zram.hold.<pid>` for each process it chose. A signalled process reads its own hold file
first, then the shared one, and the control file only when both are missing or empty. A trim
request the monitor wrote at the same moment cannot replace the app's, and a process that was
not chosen does not read back, even if it only now starts the page-out request it was sent
before the suspend. The suspend page-out answers `1`
in `/tmp/.dd-zram.ack.<pid>` and a trim answers its kB in `/tmp/.dd-zram.trim.<pid>`, so the
monitor never removes or reads an answer the app is waiting for, and the app never takes a
trim's answer for its own. A trim budget smaller than a chunk covers the stretch of the chunk
that holds that many resident pages, and the next request continues from where it ended. With less than 64 MiB of zRAM room nothing is requested and the
session log says so once a minute.

With no shortage nothing is compressed. A game that fits in memory is never touched, and Steam
is no longer compressed on a timer.

## Reading the client back

Once the client has been compressed, it is read back in one request when the game has exited,
or the client has been in front of the game for five seconds, and at least 30 seconds have
passed since the last request, and available memory covers the client's swap plus the target
and the floor. Missing or unreadable focus data never counts as the client in front. Demand
paging alone left the Steam library and menus blank for about half a minute in that case.
Suspending the session while it is asleep, hidden or the screen is off still pages everything
out, and resuming reads it back when available memory is at least twice what those processes
have in swap at that moment and, after it, 1 GiB or a tenth of RAM, whichever is larger, stays
available.

## Threads

When a worker can be started, page-out runs outside the game's threads. The preload's signal
handler starts a `zr-worker` thread for the request, which does the `madvise` calls and exits once no request
is left, so a process that is not paging out has no thread the preload added. Programs that
check they are single-threaded before they fork or call `unshare(CLONE_NEWUSER)`, such as
Chromium's zygote, keep working. The worker is a bare `clone` with the flags of a normal
thread, on a stack the preload maps itself, and makes only raw system calls, so starting it
from the signal handler is safe and seccomp filters that only allow thread clones accept it. A
process that has a single thread, or whose signalled thread added a seccomp filter after the
process started, as browser sandboxes do, gets no worker. When there is no worker, the process trims inline in steps of at most 32 MiB per request and
looks at no more than 512 MiB of its address space each time. The page-out for a suspend and the read-back
after it are not limited there: they run once, while the process is being stopped or
started, and cover all of it. The monitor is a
shell loop in the session, outside every game process.

The session log gets a line once at least 16 MiB has moved since the last one, at most every
five seconds:
`memory: available N MB, falling N MB/s, compressed N MB of the client and N MB of the session`.

No root settings, swap sizes, compression algorithms, Android process priorities or
game-specific settings are changed.

## Measurements behind the levels


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

Without root an app cannot write its memory cgroup's `memory.reclaim` (owned by `system`),
`process_madvise` on another process returns `EPERM`, `PAGEMAP_SCAN` is absent before Linux 6.7,
and KGSL's per-process GPU reclaim `state` is root-only. Page-out therefore runs inside each
process through the preload, and GPU memory itself cannot be compressed from userspace.

## Page-out and read-back around a suspend

A hidden or screen-off suspend stops the session at once. It pages the session out first only
when `MemAvailable` is below twice the reserve, the larger of 1 GiB and a tenth of RAM. Otherwise
the session stays resident while it is stopped, and every ten seconds of uptime the app reads
`MemAvailable` again. Once it falls below that level, the app moves the guest clock forward by
the stopped time, continues the session with audio still suspended, pages it out and stops it
again. If the session does not settle in that stop, the app stops it again every ten seconds
until it does, so it stays suspended as the service reports. A resume ends the checks and the
retries and cuts a running page-out short. A short screen-off with memory
to spare therefore moves nothing into zram, and nothing has to come back when the screen turns on.
Getting it back costs much more on some phones than on others: after a 12 s screen-off a Poco F7
read 690 MB back in 6.7 s while the phone was waking up.

After the resume, the processes that were paged out are read back largest first, as long as
their swap fits the budget: half of `MemAvailable`, while leaving the reserve available.
Only the chosen processes get a read-back request. Processes that do not fit keep their memory
in zram and fault it back on demand, so one large process no longer stops the read-back of every
other one.

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
