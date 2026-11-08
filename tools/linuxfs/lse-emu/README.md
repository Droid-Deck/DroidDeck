# LSE-Emu

`lse_emu.c` is an unmodified copy of [SildurFX/LSE-Emu](https://github.com/SildurFX/LSE-Emu)
at commit `8bc9548ac6fd24a8d506b266b17ba8d0e71a9b01` (GPL-3.0-or-later).

It is an `LD_PRELOAD` library that emulates the ARMv8.1 LSE atomic instructions (and `LDAPR`)
on ARMv8.0 CPUs. A SIGILL handler performs each trapped instruction with an LL/SC loop. With
`LSE_EMU_PATCH=1` the first trap at a site writes an LL/SC stub nearby and replaces the
instruction with a branch to it, so later runs do not trap.

The session stages it as `/usr/local/lib/droiddeck-lse/liblse_emu.so` and only lists it in
`/etc/ld.so.preload` when `/proc/cpuinfo` has no `atomics` flag. See
`docs/arm-v8.0-steam-client.md` for why.

To update, copy `lse_emu.c` from a newer upstream commit and change the commit above.
