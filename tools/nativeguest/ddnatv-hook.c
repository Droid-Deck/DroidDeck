#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <stddef.h>
#include <string.h>
#include <stdint.h>
#include <dlfcn.h>
#include <unistd.h>
#include <fcntl.h>
#include <stdarg.h>
#include <spawn.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <dirent.h>
#include <ftw.h>
#include <glob.h>
#include <limits.h>
#include <errno.h>
#include <signal.h>
#include <ucontext.h>
#include <sys/statfs.h>
#include <sys/statvfs.h>
#include <sys/file.h>
#include <sys/socket.h>
#include <sys/utsname.h>
#include <sys/un.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <resolv.h>
#include <utime.h>
#include <sys/time.h>
#include <pwd.h>
#include <grp.h>
#include <shadow.h>
#include <pthread.h>
#include <sys/wait.h>
#include <sys/sendfile.h>
#include <sys/mman.h>
#include <sched.h>
#include <time.h>
#include <sys/xattr.h>
#include <fnmatch.h>
#include <sys/ipc.h>
#include <sys/shm.h>
#include <sys/sysmacros.h>
#include <net/if.h>
#include <sys/inotify.h>

#ifndef RENAME_EXCHANGE
#define RENAME_EXCHANGE (1 << 1)
#endif

// The code that is added to static Go programs (gosys/gosys.c), built by scripts/build-gosys.sh. A
// build without it works as before, only without Go support. It is AArch64 code: the 32-bit hook
// has none (a 32-bit phone cannot run 64-bit programs).
#if defined(__has_include) && defined(__LP64__)
#if __has_include("gosys/gosys_blob.h")
#include "gosys/gosys_abi.h"
#include "gosys/gosys_blob.h"
#define DDNATV_HAVE_GOSYS 1
#endif
#endif

// The hook is one translation unit split into parts by topic, in this order (later parts use
// the static functions of earlier ones).
#include "hook/syscalls.c"
#include "hook/paths.c"
#include "hook/hardlinks.c"
#include "hook/signals.c"
#include "hook/setup.c"
#include "hook/files.c"
#include "hook/fileops.c"
#include "hook/identity.c"
#include "hook/privileged.c"
#include "hook/env.c"
#include "hook/exec_probe.c"
#include "hook/static_programs.c"
#include "hook/go_programs.c"
#include "hook/exec.c"
#include "hook/net.c"
#include "hook/sockets.c"
#include "hook/sysv_shm.c"
#include "hook/posix_shm.c"
