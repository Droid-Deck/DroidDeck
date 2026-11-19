# tools/dxvk-swap — install the pinned DXVK into one prefix, prove it loaded

Host-side companion to `tools/dxvk` (the pinned, patched DXVK build). It swaps
the built `d3d11.dll`/`dxgi.dll`/`d3d10core.dll` into the **Megabonk** prefix
only (Steam app id `3405340`), keeps a timestamped backup, and can prove from a
`proton run` trace that Wine loaded the swapped DLL and that the build logged
its compatibility relaxations.

It never launches a game, never touches another compatdata directory, and
needs no root. `--dry-run` validates every precondition and prints every file
operation without writing.

## Usage

```sh
# inspect the prefix and planned operations
tools/dxvk-swap/dxvk-swap.sh swap \
  --prefix /path/to/steamapps/compatdata/3405340/pfx \
  --dlls /tmp/oc/dxvk-dlls --variant aarch64 --dry-run

# back up the prefix's system32 DLLs, then install the staged ones
tools/dxvk-swap/dxvk-swap.sh swap \
  --prefix .../compatdata/3405340/pfx \
  --dlls /tmp/oc/dxvk-dlls --variant aarch64

# run a Windows probe through `proton run` and check what actually loaded
tools/dxvk-swap/dxvk-swap.sh probe \
  --prefix .../compatdata/3405340/pfx \
  --dlls /tmp/oc/dxvk-dlls --variant aarch64 \
  --proton /path/to/Proton-GE/proton \
  --exe /tmp/d3d11probe.exe

# restore the files recorded in the newest backup
tools/dxvk-swap/dxvk-swap.sh revert --prefix .../compatdata/3405340/pfx
```

`--prefix` may be the `pfx` directory or its parent. The prefix path must
contain `compatdata/3405340`; `--allow-any-prefix` exists only for a reviewed
exception. `--dlls` defaults to the host staging area `/tmp/oc/dxvk-dlls`; pass
`--variant aarch64` for the ARM64 prefix, `arm64ec` for an ARM64EC one.

## Guarantees the script enforces before writing

* **Prefix identity** — the resolved path must be
  `…/compatdata/3405340/pfx`. A different app id is refused before a backup or
  copy happens.
* **Machine type** — the PE machine of the staged `d3d11.dll` must equal the
  one already in the prefix's `system32` (`0xaa64` ARM64, `0x8664` x86-64 /
  ARM64EC). `dxgi.dll` and `d3d10core.dll` are checked the same way if they
  exist. A mismatch refuses the swap with both machine names in the message.
  A prefix with no `system32/d3d11.dll` is refused too, because its machine
  type cannot be verified (`--allow-missing-dlls` for a deliberately fresh
  prefix).
* **Staged build identity** — all three DLLs must agree on machine type; the
  `SHA256SUMS` beside the build is checked for each file when present, and the
  staged `d3d11.dll` must contain both reviewed markers:
  `mali-open: dxvk compat relaxation: geometryShader absent, allowed` and the
  same line for `shaderCullDistance`. A stock DXVK build (no markers) is
  refused, so the script cannot install something that has not passed
  `tools/dxvk/check_compat_relaxation.py`.
* **Post-install check** — each target is copied to a hidden temporary file in
  `system32`, renamed into place, and re-hashed; any readback mismatch aborts.

## Backup layout

`<compatdata>/dxvk-backup-YYYYmmdd-HHMMSS/`

```
d3d11.dll dxgi.dll d3d10core.dll   originals, byte-for-byte (cp -p)
staged/…                           the exact files that were installed
MANIFEST                           origin prefix, app id, timestamp, source
                                   directory, PE machine, staged SHA-256
```

`revert` defaults to the newest `dxvk-backup-*` beside the prefix, restores
only the files present in the backup, and refuses a backup whose `MANIFEST`
names another prefix (override: `--force`). It prints every step with
`--dry-run`.

## What `probe` proves

`probe` runs the executable through the Proton script (`proton run <exe>`), not
raw `wine`, with:

```
WINEPREFIX=<prefix> WINEDEBUG=+loaddll DXVK_LOG_PATH=<log dir>
DXVK_LOG_LEVEL=info STEAM_COMPAT_DATA_PATH=<…/compatdata/3405340>
```

and then reads the captured output:

1. a `trace:loaddll:load_native_dll … L"C:\\windows\\system32\\d3d11.dll"`
   line must exist (a `load_builtin_dll` line alone fails: that is Wine's
   builtin, not DXVK), and
2. the DXVK log must contain
   `mali-open: dxvk compat relaxation: geometryShader absent, allowed` **and**
   the `shaderCullDistance` line, which only the reviewed shim emits.

The probe refuses to start unless all three installed DLLs hash-match the
staged build, so it cannot accidentally test a prefix that Proton has already
re-populated.

### Proton re-copies DXVK

Proton's own prefix update copies its dist DXVK over `system32` on some launch
paths. If that happens after a probe, the script says so: the load evidence
from that run is still valid, but the prefix no longer holds the staged DLLs.
Re-run `swap` before playing (or use the launch path that skips the copy).
`probe` will refuse the next time until `swap` runs again.

## Tests

```sh
tools/dxvk-swap/tests/test_dxvk_swap.sh
```

Host-only: synthetic PE headers, fake prefix under a temp directory and a fake
`proton` that emits `+loaddll`/DXVK log lines. Covers the app-id and
machine-type refusals, the marker refusal, dry-run (no writes), swap, probe
(native vs builtin, missing relaxations, prefix no longer staged), and revert
(byte identity, cross-prefix refusal). No Android device or real Proton is
needed; the real DLLs were additionally dry-run-verified on the host.
