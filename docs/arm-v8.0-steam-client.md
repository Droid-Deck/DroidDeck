# Steam client on ARMv8.0 CPUs

## The crash

Valve's native arm64 Steam client stopped starting on ARMv8.0 CPUs after the April 15 2026
build (client version 1776387948). The process dies with SIGILL right away, the session log
shows `"$steam_root/steamrtarm64/steam" ... Illegal instruction` and `steam exited rc=132`,
and the screen stays black.

The faulting instruction is an ARMv8.1 LSE atomic. The GDB trace in
[ValveSoftware/steam-for-linux#13288](https://github.com/ValveSoftware/steam-for-linux/issues/13288)
stops on `ldaddal x1, x0, [x0]`, with `swpa` and `casal` next to it. Earlier builds were most
likely compiled with `-moutline-atomics`, which picks LL/SC or LSE at run time. Newer builds
inline LSE. Valve has not replied on that issue, so the cause comes from the reporter's
disassembly, not from Valve.

Affected cores are the ARMv8.0 ones without LSE: Cortex-A35, A53, A57, A72 and A73. A55, A75
and later have it. On Android that covers the Snapdragon 4xx/6xx parts built on A53/A73 (for example the
Snapdragon 685 with Adreno 610 in
[Droid-Deck/DroidDeck#380](https://github.com/Droid-Deck/DroidDeck/issues/380)), plus the
Nintendo Switch, Raspberry Pi 4 and older, and the Jetson Nano outside Android.
`/proc/cpuinfo` shows it: the `Features` line has no `atomics`.

LSE is an ARMv8.1 feature. LSE-Emu users report the current client running on a Cortex-A57,
so nothing later than LSE seems to be required yet.

## What other projects do

- [SildurFX/Switchdeck](https://github.com/SildurFX/Switchdeck) (Switch, L4T Linux) lets the
  client update once, then overlays `linuxarm64`, `steamrtarm64`, `steamui` and a `steam.cfg`
  from the April build. It works there because the install script controls the whole system.
  For us a pinned client is replaced by the self updater on its next start, which is why #380
  was closed.
- [SildurFX/LSE-Emu](https://github.com/SildurFX/LSE-Emu) is a preload library by the same
  author, written to run the current arm64 client on the Switch. A SIGILL handler performs
  each LSE instruction with an LL/SC loop. With `LSE_EMU_PATCH=1` the first trap at a site
  writes an LL/SC stub within branch range and replaces the instruction with a `b` to it, so
  the site runs natively after that. It covers the full LSE set plus `LDAPR`. The author
  reports Steam, FEX, Proton games and ARMSX2 running on a Cortex-A57, with ARMSX2 close to
  native ARMv8.0 speed once patched. GPL-3.0, same as this repo.
- [Azkali/proton-arm64-a57](https://github.com/Azkali/proton-arm64-a57) rebuilds Proton 11
  arm64 (Wine, DXVK, vkd3d-proton, FEX's WoW64 and ARM64EC DLLs) with
  `-march=armv8-a -mtune=cortex-a57`. Stock Proton 11 arm64 also SIGILLs on ARMv8.0
  (`wineboot` rc=132), so the client is not the only problem for games.
- Some installers fall back to Valve's x86-64 client under FEX on CPUs without LSE. We already
  have that path (`BL_STEAM_CLIENT_ARCH=x86_64`, `droiddeck-steam-x64`), but it is slower and
  FEX itself has to be built without LSE.
- [FrankNat996/armor](https://github.com/FrankNat996/armor) notes a separate problem: the arm64
  client's ELF segments are 4K aligned, so it SIGSEGVs at once on 16K page kernels.

## This change

The session vendors LSE-Emu (`tools/linuxfs/lse-emu`, upstream commit `8bc9548`). CI and
`tools/build_local.sh` build it as `usr/local/lib/droiddeck-lse/liblse_emu.so` with
`-march=armv8-a -mno-outline-atomics`. The build fails if the library contains LSE
instructions itself.

When `/proc/cpuinfo` reports any core without `atomics`, `SessionFiles` puts the library first
in the rootfs `/etc/ld.so.preload`. `BwrapSpawner` already turns that file into `LD_PRELOAD`
for sandboxed processes. On CPUs with LSE nothing changes: the file is staged but not
preloaded, and the library would also stay idle on its own because it checks `HWCAP_ATOMICS`.

`droiddeck-session` sees the library in `ld.so.preload` and exports `LSE_EMU=1` and
`LSE_EMU_PATCH=1`, so the Steam client and everything it starts get the emulation. With a
session debug folder it also sets `LSE_EMU_LOG=$BL_DEBUG_DIR/lse_emu.log`, and `session.log`
gets a line starting with `== cpu: no LSE atomics`.

The app targets SDK 28, so the app domain still has `execmod` and the in-place patching can
make file-backed code pages writable. If the target SDK goes up, `mprotect` on those pages is
refused, every site logs `mprotect-failed`, and execution stays on the slower trap path.

## Tests

`tools/tests/test_lse_emu.py` builds the library and a program that uses every LSE family
(`ldadd` in all four sizes, `ldclr`, `ldeor`, `ldset`, `swp`, `ldsmax`, `ldumin`, `casal`,
`caspal`) from four threads, and runs it under `qemu-aarch64 -cpu cortex-a57`:

- without the library the program dies with SIGILL
- trapping alone runs every form and keeps the threaded counters exact
- patching runs every form and patches every site, single threaded
- without `LSE_EMU=1`, and on `-cpu neoverse-n1`, the library stays out of the way
- a SIGILL handler the program installs itself still runs for an unrelated `udf`

Threaded patching segfaults under qemu-user, writing to the stub arena, which is mapped and
writable. qemu write-protects pages it has translated code from, and that races when other
threads run code on them. It needs checking on real hardware.

## Testing on a device

On an ARMv8.0 phone or handheld (no `atomics` in `/proc/cpuinfo`):

1. Install the test APK and start a Steam session.
2. `session.log` should have `== cpu: no LSE atomics` and no `steam exited rc=132`.
3. In the session debug folder, `lse_emu.log` should show `start`, `patched` and `exit` lines.
   `cut -d' ' -f2 lse_emu.log | sort | uniq -c` summarises it. `unhandled` means an
   instruction the library does not cover, and `mprotect-failed` or `no-arena` mean sites left
   on the trap path.
4. Compare UI responsiveness against an ARMv8.2 device, and note any `lse_emu: unhandled`
   lines in `session.log`.

## Still open

- Games. Proton 11 arm64 and Valve's FEX build need the same treatment. The preload reaches
  them when they inherit `LD_PRELOAD`, but pressure-vessel and static binaries can drop it,
  and FEX installs its own signal handlers with raw syscalls. An ARMv8.0 Proton build like
  Azkali's is the cleaner fix.
- In #380 the session first died with SIGILL in the app's own `wl-compositor` thread, inside
  `turnip-sdk36/vulkan.ad08XX.so`, which is the A8xx driver build loaded on an Adreno 610.
  Picking the 6xx/7xx Turnip fixed it, and Auto picks that build once the driver release list
  is loaded. Nobody has checked yet whether our bundled driver builds avoid LSE. The preload
  cannot help there because the compositor is a bionic thread in the app process.
- JIT code (anonymous memory) is never patched and always traps.
- If Valve starts requiring something past LSE, the library prints
  `lse_emu: unhandled pc=... insn=...` and the client crashes as before.
