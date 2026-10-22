/*
 * Hand-written replacement for libntfs-3g's autotools config.h, matching what
 * bionic provides at API 26 on aarch64. Upstream's configure cannot run under
 * the NDK, so only the macros libntfs-3g's own sources test are defined here.
 */

#define HAVE_BYTESWAP_H 1
#define HAVE_CLOCK_GETTIME 1
#define HAVE_CTYPE_H 1
#define HAVE_ENDIAN_H 1
#define HAVE_ERRNO_H 1
#define HAVE_FCNTL_H 1
#define HAVE_FFS 1
#define HAVE_GETTIMEOFDAY 1
#define HAVE_INTTYPES_H 1
#define HAVE_LIMITS_H 1
#define HAVE_LINUX_FD_H 1
#define HAVE_LINUX_FS_H 1
#define HAVE_LINUX_HDREG_H 1
#define HAVE_LOCALE_H 1
#define HAVE_MBSINIT 1
#define HAVE_REALPATH 1
#define HAVE_SETXATTR 1
#define HAVE_STDARG_H 1
#define HAVE_STDDEF_H 1
#define HAVE_STDINT_H 1
#define HAVE_STDIO_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRING_H 1
#define HAVE_STRSEP 1
#define HAVE_SYSLOG_H 1
#define HAVE_SYS_IOCTL_H 1
#define HAVE_SYS_MOUNT_H 1
#define HAVE_SYS_PARAM_H 1
#define HAVE_SYS_STAT_H 1
#define HAVE_SYS_SYSMACROS_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_SYS_XATTR_H 1
#define HAVE_TIME_H 1
#define HAVE_UNISTD_H 1
#define HAVE_WCHAR_H 1

/* bionic carries major()/minor()/makedev() in <sys/sysmacros.h>, not <sys/mkdev.h>. */
#define MAJOR_IN_SYSMACROS 1

/*
 * The volume is reached over USB bulk transfers, never through a block device
 * node, so the library must not compile its unix_io/win32_io backends; the
 * transport in app/src/main/cpp/ntfs supplies ntfs_device_operations instead.
 */
#define NO_NTFS_DEVICE_DEFAULT_IO_OPS 1

/*
 * mntent.h exists in bionic but /proc/mounts can never describe this volume,
 * so ntfs_check_if_mounted() is left with its portable no-op branch.
 */
/* #undef HAVE_MNTENT_H */
/* #undef HAVE_HASMNTOPT */
