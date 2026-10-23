# NTFS on USB drives

Android cannot mount NTFS: its volume daemon has no NTFS support, so plugging in an NTFS drive
gives a "corrupted or unsupported" notification named after the drive's USB bridge (often
"JMicron Tech"). The file manager instead lists every USB mass-storage device and opens it in
`UsbDriveActivity`, which reads the drive itself over the USB host API. No root is needed and
nothing is formatted.

The volume is opened read-only, to browse it and copy files off it. Steam libraries and games
cannot run from it: the Linux runtime under PRoot only sees filesystems the kernel mounted.

## How the drive is reached

1. `UsbDrives.open` claims the mass-storage interface with `claimInterface(force = true)`, which
   disconnects Android's `usb-storage`/`uas` driver from the whole disk, and selects the
   bulk-only alternate setting that UAS bridges (JMicron, ASMedia, Realtek) also expose.
2. `app/src/main/cpp/ntfs/usbms.c` speaks SCSI over USB Bulk-Only Transport on the
   connection's usbfs descriptor. Drives that only offer UAS are refused with a message.
3. `parttab.c` reads MBR (including logical partitions), GPT, or a filesystem written to LBA 0,
   and probes each boot sector for NTFS, exFAT or FAT. Microsoft Reserved partitions are hidden.
4. `ntfsvol.c` hands libntfs-3g an `ntfs_device_operations` that reads the partition through a
   4 MiB block cache, with writes refused.
5. Closing the screen reconnects the kernel driver, so Android probes the disk again and any
   partition it can mount comes back.

Opening a drive while Android has a USB volume mounted asks first, since that volume goes away
until the screen is closed.

## libntfs-3g

ntfs-3g 2022.10.3 (GPL 2.0 or later, compatible with DroidDeck's GPL 3.0). Only `libntfs-3g` is compiled: not the FUSE
driver, `libfuse-lite` or ntfsprogs. The APK carries its license text under
`assets/licenses/ntfs-3g`.

| Source | SHA-256 |
| --- | --- |
| [tuxera/ntfs-3g tag 2022.10.3](https://github.com/tuxera/ntfs-3g/archive/refs/tags/2022.10.3.tar.gz) | `8bd7749ea9d8534c9f0664d48b576e90b96d45ec8803c9427f6ffaa2f0dde299` |

The tag archive is used rather than `tuxera.com/opensource/ntfs-3g_ntfsprogs-2022.10.3.tgz`,
which answers 403 to CI runners. Every file compiled here is byte-identical between the two;
the release tarball only adds autotools-generated files this build does not use.

Upstream's `configure` cannot run under the NDK, so `tools/ntfs3g/ntfs3g-config.h` replaces the
generated `config.h` with what bionic provides at API 26. It also sets
`NO_NTFS_DEVICE_DEFAULT_IO_OPS`, which leaves out `unix_io.c`, as the device is never a block
node.

## Building

`tools/build_local.sh` and CI build it with `tools/ntfs3g/build.sh <dir>` and pass the result
to Gradle as `-Pntfs3gDir=<dir>`. The output holds `arm64-v8a/libntfs-3g.a`, the headers with
the config under `include/ntfs-3g`, and `licenses/ntfs-3g-COPYING`. For an Android Studio
build, run the script on Linux or macOS with `NDK` set and add
`droiddeck.ntfs3gDir=/path/to/dir` to the untracked `local.properties`.

Without that directory, `libdroiddeck_usbdrive.so` is not built, the file manager shows no USB
drives, and `tools/release/check_android_libraries.py` fails the APK.
