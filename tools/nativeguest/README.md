# Native userland (no proot)

`libddnatv-hook.so` is an `LD_PRELOAD` library that lets the glibc rootfs run directly on Android:
it maps the guest's absolute paths into the rootfs (plus the mounts proot used to provide, from
`DDNATV_BINDS`), starts every program through the rootfs's `ld.so`, and answers the system calls
Android's seccomp policy kills a process for (`SIGSYS` handler). There is no tracer. The app side
is `runtime/NativeGuest.kt`; the setting is Performance > "Run without proot (experimental)".

The hook is derived from the Apache-2.0 `libcortex-hook` of
[ssfuisu/cortex](https://github.com/ssfuisu/cortex) (see `LICENSE`), renamed (`cortex` -> `ddnatv`,
same length: the code compares environment names by length) and extended with:

- `DDNATV_BINDS` mounts, `DDNATV_PRELOAD` (what `/etc/ld.so.preload` does for proot), and mapping of
  guest paths inside `LD_PRELOAD`;
- POSIX `shm_open`/`shm_unlink` through `/dev/shm`;
- starting Wine without its static preloader, which opens files with raw system calls.

Build: `tools/nativeguest/build.sh out.so` with an aarch64 glibc cross compiler.

## Testing under Android's seccomp filter

Some devices (eng builds, `setenforce 0` ROMs) run apps with no seccomp filter, so a hook that
passes there can still die with SIGSYS on a stock phone (`GUEST_EXIT 159`). `seccomp-sim.c` applies
a filter that answers the calls Android blocks with SIGSYS, then runs the program:

```
aarch64-linux-gnu-gcc -static -O2 -o seccomp-sim tools/nativeguest/seccomp-sim.c
# copy it into the rootfs, then name it for every native launch:
echo /usr/local/bin/seccomp-sim > <rootfs>/etc/droiddeck/seccomp-sim
```

`grep Seccomp: /proc/<pid>/status` should then show `2` for the session's processes. Empty the file
to turn it off. `seccomp-sim -a NR,NR PROGRAM` leaves the listed calls allowed, to find which one
kills a program.
