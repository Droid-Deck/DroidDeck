/*
 * noexec.c: FEX runs an x86 program and its libraries where they lie, on noexec shared storage too.
 * FEX maps them with the guest's PROT_EXEC, which the kernel refuses for a file on a noexec mount,
 * but it never runs those pages itself - it runs its own translation of them - so a mapping or an
 * mprotect refused for that alone is made without PROT_EXEC. Only in FEX's own processes.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/statvfs.h>
#include <unistd.h>

/* NOEXEC_TEST: tools/tests builds it for the host. */
#if defined(__aarch64__) || defined(NOEXEC_TEST)

static int in_fex(void) {
  static int known = -1;
  if (known < 0) {
    char exe[512];
    ssize_t n = readlink("/proc/self/exe", exe, sizeof exe - 1);
    exe[n > 0 ? n : 0] = 0;
    const char *name = strrchr(exe, '/');
    name = name ? name + 1 : exe;
    known = !strcmp(name, "FEX") || !strcmp(name, "FEXInterpreter") || !strcmp(name, "FEXLoader");
  }
  return known;
}

/* After an mmap failed (fsync.c's mmap asks): whether to map it again without PROT_EXEC. */
__attribute__((visibility("hidden"))) int bl_noexec_retry(int prot, int flags, int fd) {
  int error = errno;
  struct statvfs vfs;
  int retry = (error == EACCES || error == EPERM) && (prot & PROT_EXEC) && fd >= 0 && !(flags & MAP_ANONYMOUS) &&
              in_fex() && fstatvfs(fd, &vfs) == 0 && (vfs.f_flag & ST_NOEXEC);
  errno = error;
  return retry;
}

static int (*real_mprotect)(void *, size_t, int);

/* Looked up before anything runs: Wine calls mprotect from its signal handlers, where dlsym is unsafe. */
__attribute__((constructor)) static void noexec_init(void) {
  real_mprotect = (int (*)(void *, size_t, int))dlsym(RTLD_NEXT, "mprotect");
}

int mprotect(void *addr, size_t len, int prot) {
  if (!real_mprotect) noexec_init();
  int result = real_mprotect(addr, len, prot);
  if (result && errno == EACCES && (prot & PROT_EXEC) && in_fex()) {
    result = real_mprotect(addr, len, prot & ~PROT_EXEC);
    if (result) errno = EACCES;
  }
  return result;
}

#else

__attribute__((visibility("hidden"))) int bl_noexec_retry(int prot, int flags, int fd) {
  (void)prot, (void)flags, (void)fd;
  return 0;
}

#endif
