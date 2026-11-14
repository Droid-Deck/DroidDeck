# Opt-in device-backed low mappings for Wine WoW64

On the tested Pixel PowerVR stack, Vulkan maps host-visible GPU allocations
above 4 GiB. Wine's 32-bit WoW64 Vulkan bridge rejects those pointers. This
caused American Nightmare (202750) to stop at D3D9 vertex-buffer creation even
after the separate PowerVR BC texture profile passed device initialization.

`low32.c` is an experimental native Linux/glibc preload adapter. It is off by
default. It applies only to anonymous-address, non-executable `MAP_SHARED`
mappings of one explicitly selected character device. It retains the original
device FD, offset, protection, flags, and actual GPU backing, and searches low
address space with `MAP_FIXED_NOREPLACE`. It never truncates a pointer, copies
into shadow storage, replaces existing mappings, or disables Wine's guard.
Caller-specified/fixed mappings, regular files, private/anonymous memory and
other devices retain their original behavior. Failure to find a suitable low
hole fails with ENOMEM. The mmap and mmap64 preload chains are independently
resolved and called through their respective RTLD_NEXT symbols;
only resolver bootstrap uses the raw syscall. A compact atomic cursor avoids
wasting large address slots on thousands of small driver control allocations.
If the compact probe collides, a bounded address-range walk of
/proc/self/maps finds page-sized holes and uses NOREPLACE again to handle races.
The walk runs before coarse fallback, so small allocations do not fragment
large available ranges. A game load had 2.3 GB free below 4 GiB but no hole as large as 4 MiB; the
coarse-only version falsely failed even 4 KiB mappings. Mapping failures are
reported even after the ordinary 256-event trace limit.

## Build and verify

Use a native 64-bit Linux/glibc compiler, not the Android NDK or macOS compiler:

```sh
tools/hybris/low32/build.sh /absolute/build-dir /path/to/Vulkan-Headers/include
```

The native library and unit test are always built. GPU probes are optional when
headers are provided; `gpu-copy-native` uses the selected native host compiler
and has the host's architecture, while the Windows x86 probe also needs
i686-w64-mingw32-gcc. Copy a native probe to a device only when their architectures match.
The unit test uses /dev/zero and proves CPU data integrity, collision handling,
concurrency, 2000 simultaneous small mappings, invalid offsets, forwarding and
opt-out. A fragmented-address-space regression reserves the low region, opens
64 off-grid page holes, and verifies all 64 mappings and their data. The old
coarse adapter fails that regression; the address-range fallback passes. An
additional density regression fills 64 existing page holes while preserving a
separate contiguous 16 MiB interval for a later complete data-readback map; the
prior gap-after-coarse implementation fails and the gap-first strategy passes. These
CPU tests do not by themselves prove GPU correctness.
The build also tests both preload orders with a second library that hooks mmap
and mmap64 independently. Opt-out, tracing and adaptation must each preserve
the selected symbol's chain, invalid-offset errno and file offsets above 4 GiB.

`gpu-copy.c` tests actual Vulkan transfer operations on vendor 0x1010. It maps
two 32 MiB device allocations, runs four copies of full deterministic patterns,
including offset/unmap/remap, uses flush/invalidate and barriers/fences, and
compares every word. It can require each full CPU range to fit below 4 GiB:

```sh
./gpu-copy-native 32 --require-low32
# Through a separate Proton ARM64/WoW64 test prefix:
proton run gpu-copy-x86.exe 32 --require-low32 'Z:\path\probe-output.txt'
```

The Windows output file avoids Wine's detached console hiding probe results.
Run with Khronos synchronization validation. Keep raw Steam/account logs local;
export only controlled PROBE results, validation IDs and counts. The tested
memory type was device-local, host-visible and coherent; noncoherent physical
memory is not covered by that run.

## Activation scope

Set only the target game's overrides, after identifying the render device's
`st_rdev` and validating the installed library's hash:

```text
LD_PRELOAD=/absolute/guest/path/libdroiddeck-low32.so
DROIDDECK_LOW32_MODE=low32
DROIDDECK_LOW32_RDEV=<verified decimal st_rdev>
```

Preserve any existing target-game preload rather than replacing it. The tested
Pixel's /dev/dri/renderD128 was character device major 226/minor 128, st_rdev 57984.
Do not hardcode that value for other machines. BC emulation and the repaired
DXVK presentation component remain separate prerequisites; this adapter does
not implement them. No global Steam profile, managed graphics pack, Android
security setting or unrelated game's overrides should change.

Before activating, back up the exact game-environment bytes and only the target
prefix's graphics DLLs, preserving symlinks. Rollback must check for concurrent
edits. Keep original backups in the app; do not copy account files or an entire
Steam prefix to a report. Removing these three overrides disables the adapter.

## Compatibility limits

This initial binary was built on Ubuntu 24.04 ARM64 and needs glibc 2.38. The
tested guest supports it. This is not yet a universal runtime component; other
glibc versions, page sizes, drivers, GPU families, native 32-bit processes and
general game stability require their own tests. Bounded native and Windows GPU
copies prove data integrity for those workloads, not unrestricted gameplay.
Never enable globally based on successful testing of one game.
